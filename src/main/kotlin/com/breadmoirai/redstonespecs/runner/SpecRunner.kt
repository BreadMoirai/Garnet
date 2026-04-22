package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.SpecCaseResult
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.TickCheck
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

data class BreakpointHit(
    val simTime: SimTime,
    val specName: String,
    val caseName: String,
    val breakpointLabel: String,
)

class SpecRunner(
    val spec: RedstoneSpec,
    val specCase: SpecCase,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1
    private val checks = mutableListOf<TickCheck>()

    var frozenAt: SimTime? = null
        private set
    var pendingBreakpointHit: BreakpointHit? = null
        private set

    fun start() {
        LOGGER.debug("[SpecRunner#start] starting case '{}' of spec '{}'", specCase.name, spec.name)
        applyInputsAt(SimTime.INIT)
    }

    fun resume() {
        LOGGER.debug("[SpecRunner#resume] resuming case '{}' frozen at {}", specCase.name, frozenAt)
        frozenAt = null
    }

    fun clearPendingBreakpointHit() { pendingBreakpointHit = null }

    fun resetCircuit() { snapshot.restore(level) }

    fun onPhase(phase: Phase): SpecCaseResult? {
        if (frozenAt != null) return null
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return null
        if (ticksElapsed >= specCase.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] case '{}' finished after {} ticks", specCase.name, ticksElapsed)
            return SpecCaseResult(specCase.name, checks.toList())
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkOutputsAt(simTime)
        checkBreakpointsAt(simTime)
        return null
    }

    private fun applyInputsAt(simTime: SimTime) {
        for (input in specCase.inputs) {
            val (_, condition) = input.entries.find { it.first == simTime } ?: continue
            val pos = worldPos(input.pos)
            LOGGER.debug("[SpecRunner#applyInputsAt] {} applying condition to {}", simTime, pos)
            applyCondition(condition, pos)
        }
    }

    private fun applyCondition(condition: StateCondition, pos: BlockPos) {
        var state = level.getBlockState(pos)
        val mods = mutableListOf<Pair<String, String>>()
        flattenToProperties(condition, mods)
        if (mods.isEmpty()) return
        for ((name, value) in mods) {
            val property = state.block.stateDefinition.getProperty(name) ?: continue
            @Suppress("UNCHECKED_CAST")
            state = applyProperty(state, property as Property<Comparable<Any>>, value)
        }
        level.setBlock(pos, state, 3)
    }

    private fun flattenToProperties(condition: StateCondition, out: MutableList<Pair<String, String>>) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { flattenToProperties(it, out) }
            is StateCondition.BoolProperty -> out += condition.name to condition.value.toString()
            is StateCondition.IntProperty -> out += condition.name to condition.value.toString()
            is StateCondition.EnumProperty -> out += condition.name to condition.value
            is StateCondition.BlockType -> LOGGER.warn(
                "[SpecRunner] BlockType condition '{}' cannot be applied as block property input, ignoring",
                condition.blockId
            )
            else -> LOGGER.warn(
                "[SpecRunner] Unsupported condition type '{}' in flattenToProperties, ignoring",
                condition::class.simpleName
            )
        }
    }

    private fun checkOutputsAt(simTime: SimTime) {
        for (output in specCase.outputs) {
            val (_, condition) = output.entries.find { it.first == simTime } ?: continue
            val pos = worldPos(output.pos)
            val state = level.getBlockState(pos)
            val label = output.label.ifEmpty { output.pos.toString() }
            collectChecks(condition, state, pos, simTime, label)
        }
    }

    private fun collectChecks(condition: StateCondition, state: BlockState, pos: BlockPos, simTime: SimTime, label: String) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { collectChecks(it, state, pos, simTime, label) }
            is StateCondition.BoolProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                val pass = actual == expected
                LOGGER.debug("[SpecRunner#collectChecks] {} '{}.{}' expected={} actual={} pass={}", simTime, label, condition.name, expected, actual, pass)
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, pass)
            }
            is StateCondition.IntProperty -> {
                val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty
                val actual = prop?.let { state.getValue(it).toString() } ?: "missing"
                val expected = condition.value.toString()
                val pass = actual == expected
                checks += TickCheck(simTime, "$label.${condition.name}", expected, actual, pass)
            }
            is StateCondition.EnumProperty -> {
                val actual = blockStatePropertyStr(state, condition.name) ?: "missing"
                val pass = actual == condition.value
                checks += TickCheck(simTime, "$label.${condition.name}", condition.value, actual, pass)
            }
            is StateCondition.BlockType -> {
                val actualId = BuiltInRegistries.BLOCK.getKey(state.block)?.toString() ?: "missing"
                val expected = condition.blockId.toString()
                val pass = actualId == expected
                checks += TickCheck(simTime, "$label.block", expected, actualId, pass)
            }
            is StateCondition.Any -> LOGGER.warn(
                "[SpecRunner] Any condition not supported in output checks at {}, skipping", label
            )
            is StateCondition.Not -> LOGGER.warn(
                "[SpecRunner] Not condition not supported in output checks at {}, skipping", label
            )
            is StateCondition.ContainerContents -> LOGGER.warn(
                "[SpecRunner] ContainerContents condition not supported in output checks at {}, skipping", label
            )
        }
    }

    private fun checkBreakpointsAt(simTime: SimTime) {
        for (bp in specCase.breakpoints) {
            if (!bp.enabled) continue
            if (evaluateCondition(bp.condition, level, worldPos(bp.pos))) {
                LOGGER.debug("[SpecRunner#checkBreakpointsAt] breakpoint '{}' hit at {}", bp.label, simTime)
                frozenAt = simTime
                pendingBreakpointHit = BreakpointHit(simTime, spec.name, specCase.name, bp.label.ifEmpty { bp.pos.toString() })
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
