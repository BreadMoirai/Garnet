package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class BreakpointHit(
    val simTime: SimTime,
    val specId: String,
    val breakpointLabel: String,
)

class SpecRunner(
    val spec: RedstoneSpec,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1

    var frozenAt: SimTime? = null
        private set
    var pendingBreakpointHit: BreakpointHit? = null
        private set

    fun start() {
        LOGGER.debug("[SpecRunner#start] starting spec '{}'", spec.id)
        applyInputsAt(SimTime.START)
    }

    fun resume() {
        LOGGER.debug("[SpecRunner#resume] resuming spec '{}' frozen at {}", spec.id, frozenAt)
        frozenAt = null
    }

    fun clearPendingBreakpointHit() { pendingBreakpointHit = null }

    fun resetCircuit() { snapshot.restore(level) }

    /** Returns true once the spec has reached its lifespan and the run is complete. */
    fun onPhase(phase: Phase): Boolean {
        if (frozenAt != null) return false
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return false
        if (ticksElapsed >= spec.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] spec '{}' finished after {} ticks", spec.id, ticksElapsed)
            return true
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkBreakpointsAt(simTime)
        return false
    }

    private fun applyInputsAt(simTime: SimTime) {
        val userInteractionTime = if (simTime.phase == Phase.START_OF_TICK)
            simTime.copy(phase = Phase.USER_INTERACTION) else null
        for (input in spec.inputs) {
            val (_, condition) = input.entries.find {
                it.first == simTime || (userInteractionTime != null && it.first == userInteractionTime)
            } ?: continue
            val pos = worldPos(input.pos)
            LOGGER.debug("[SpecRunner#applyInputsAt] {} applying condition to {}", simTime, pos)
            applyCondition(condition, pos)
        }
    }

    /**
     * Applies an input [condition] to the block at [pos] for replay.
     *
     * Generic path: flattens the condition into property name/value pairs and
     * issues a single [setBlock] with flag 3 (block + neighbor updates).
     *
     * Block-type-specific dispatch: certain blocks expose interaction methods
     * that produce side effects beyond simple state mutation — most notably
     * `ButtonBlock.press`, which schedules an auto-depower tick. Recording
     * captures both the press *and* the natural depower as separate I/O
     * events; replaying the press via raw `setBlock` skips the schedule,
     * producing timing drift relative to the original recording. For these
     * blocks we route through their player-input methods to preserve
     * scheduler-driven timing.
     *
     * NOTE: when a block-type-specific path is taken (e.g. [ButtonBlock.press])
     * we return early and DO NOT also issue the generic setBlock, since the
     * specific path already mutates state. The spec's later input entry for
     * the depower transition will fire after the natural schedule has already
     * run; both attempts to set `powered=false` collapse into a no-op.
     */
    private fun applyCondition(condition: StateCondition, pos: BlockPos) {
        val currentState = level.getBlockState(pos)
        val mods = mutableListOf<Pair<String, String>>()
        flattenToProperties(condition, mods)
        if (mods.isEmpty()) return

        if (tryApplyAsPlayerInteraction(currentState, pos, mods)) return

        // Generic fall-through: setBlock with merged property values.
        var state = currentState
        for ((name, value) in mods) {
            val property = state.block.stateDefinition.getProperty(name) ?: continue
            @Suppress("UNCHECKED_CAST")
            state = applyProperty(state, property as Property<Comparable<Any>>, value)
        }
        if (state != currentState) level.setBlock(pos, state, 3)
    }

    /**
     * Attempts to apply [mods] using a block-specific player-interaction path
     * (so that side effects like scheduled ticks fire as they would for a real
     * player action). Returns `true` if the dispatch handled the change and the
     * caller should NOT fall through to the generic setBlock path.
     *
     * Currently handled:
     *  - [ButtonBlock]: `powered` false→true triggers [ButtonBlock.press],
     *    which sets powered=true, fires neighbor updates, and schedules the
     *    depower tick. The `powered`=true→false transition is left to the
     *    natural schedule (the caller's later input entry for that SimTime
     *    becomes a no-op once the schedule has fired).
     *
     * Extension points: when adding more block types, prefer methods that go
     * through the same code path a player would take (e.g. `LeverBlock.pull`,
     * `BlockState.useWithoutItem`) so neighbor notifications, scheduled ticks,
     * and game events all fire identically to the original recording.
     */
    private fun tryApplyAsPlayerInteraction(
        state: BlockState,
        pos: BlockPos,
        mods: List<Pair<String, String>>,
    ): Boolean {
        val block = state.block
        if (block is ButtonBlock) {
            val targetPowered = mods.firstOrNull { it.first == "powered" }?.second?.toBoolean() ?: return false
            val currentPowered = state.getValue(BlockStateProperties.POWERED)
            if (targetPowered && !currentPowered) {
                LOGGER.debug("[SpecRunner#applyCondition] press button at {}", pos)
                block.press(state, level, pos, null)
                return true
            }
        }
        return false
    }

    private fun flattenToProperties(condition: StateCondition, out: MutableList<Pair<String, String>>) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { flattenToProperties(it, out) }
            is StateCondition.BoolProperty -> out += condition.name to condition.value.toString()
            is StateCondition.IntProperty -> out += condition.name to condition.value.toString()
            is StateCondition.EnumProperty -> out += condition.name to condition.value
            is StateCondition.BlockType -> LOGGER.warn("[SpecRunner] BlockType condition cannot be applied as input, ignoring")
            else -> LOGGER.warn("[SpecRunner] Unsupported condition type '{}' in flattenToProperties, ignoring", condition::class.simpleName)
        }
    }

    private fun checkBreakpointsAt(simTime: SimTime) {
        for (bp in spec.breakpoints) {
            if (!bp.enabled) continue
            if (evaluateCondition(bp.condition, level, worldPos(bp.pos))) {
                LOGGER.debug("[SpecRunner#checkBreakpointsAt] breakpoint '{}' hit at {}", bp.label, simTime)
                frozenAt = simTime
                pendingBreakpointHit = BreakpointHit(simTime, spec.id, bp.label.ifEmpty { bp.pos.toString() })
                return
            }
        }
    }

    private fun <T : Comparable<T>> applyProperty(state: BlockState, property: Property<T>, valueStr: String): BlockState =
        property.getValue(valueStr).map { state.setValue(property, it) }.orElse(state)

    fun evaluateCondition(condition: StateCondition, worldPos: BlockPos): Boolean =
        com.breadmoirai.redstonespecs.runner.evaluateCondition(condition, level, worldPos)

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
