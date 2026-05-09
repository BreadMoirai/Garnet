package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.dsl.StateCondition
import com.breadmoirai.redstonespecs.dsl.evaluateCondition
import com.breadmoirai.redstonespecs.data.inputs
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.level.block.state.properties.Property
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class SpecRunner(
    val spec: RedstoneSpec,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1
    var isComplete: Boolean = false
        private set

    fun start() {
        LOGGER.debug("[SpecRunner#start] starting spec '{}'", spec.id)
        applyInputsAt(SimTime.START)
    }

    fun resume() { /* no-op; breakpoints removed */ }

    fun resetCircuit() { snapshot.restore(level) }

    /** Returns true once the spec has reached its lifespan and the run is complete. */
    fun onPhase(phase: Phase): Boolean {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return false
        if (ticksElapsed >= spec.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] spec '{}' finished after {} ticks", spec.id, ticksElapsed)
            isComplete = true
            return true
        }
        applyInputsAt(SimTime(ticksElapsed, phase))
        return false
    }

    private fun applyInputsAt(simTime: SimTime) {
        val userInteractionTime = if (simTime.phase == Phase.START_OF_TICK)
            simTime.copy(phase = Phase.USER_INTERACTION) else null
        for (input in spec.inputs) {
            if (input.time != simTime && input.time != userInteractionTime) continue
            val pos = worldPos(input.pos)
            LOGGER.debug("[SpecRunner#applyInputsAt] {} applying condition to {}", simTime, pos)
            applyCondition(input.condition, pos)
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
     */
    private fun applyCondition(condition: StateCondition, pos: BlockPos) {
        val currentState = level.getBlockState(pos)
        val mods = mutableListOf<Pair<String, String>>()
        flattenToProperties(condition, mods)
        if (mods.isEmpty()) return

        if (tryApplyAsPlayerInteraction(currentState, pos, mods)) return

        var state = currentState
        for ((name, value) in mods) {
            val property = state.block.stateDefinition.getProperty(name) ?: continue
            @Suppress("UNCHECKED_CAST")
            state = applyProperty(state, property as Property<Comparable<Any>>, value)
        }
        if (state != currentState) level.setBlock(pos, state, 3)
    }

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

    private fun <T : Comparable<T>> applyProperty(state: BlockState, property: Property<T>, valueStr: String): BlockState =
        property.getValue(valueStr).map { state.setValue(property, it) }.orElse(state)

    fun evaluateCondition(condition: StateCondition, worldPos: BlockPos): Boolean =
        evaluateCondition(condition, level, worldPos)

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
