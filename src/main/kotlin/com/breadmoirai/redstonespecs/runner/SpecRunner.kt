package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.SpecCaseResult
import com.breadmoirai.redstonespecs.data.TickCheck
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

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
        applyInputsAt(SimTime.INIT)
    }

    fun resume() {
        frozenAt = null
    }

    fun clearPendingBreakpointHit() {
        pendingBreakpointHit = null
    }

    fun resetCircuit() {
        snapshot.restore(level)
    }

    /**
     * Called at each sub-tick phase boundary. Returns a [SpecCaseResult] once
     * [SpecCase.lifespan] ticks have elapsed (or immediately if still frozen).
     */
    fun onPhase(phase: Phase): SpecCaseResult? {
        if (frozenAt != null) return null
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return null
        if (ticksElapsed >= specCase.lifespan) {
            return SpecCaseResult(specCase.name, checks.toList())
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkOutputsAt(simTime)
        checkBreakpointsAt(simTime)
        return null
    }

    // --- inputs / outputs ---

    private fun applyInputsAt(simTime: SimTime) {
        for (input in specCase.inputs) {
            val (_, props) = input.stateSpec.entries.find { it.first == simTime } ?: continue
            if (props.isEmpty()) continue
            setBlockStateProperties(worldPos(input.pos), props)
        }
    }

    private fun checkOutputsAt(simTime: SimTime) {
        for (output in specCase.outputs) {
            val (_, expected) = output.stateSpec.entries.find { it.first == simTime } ?: continue
            if (expected.isEmpty()) continue
            val actualState = level.getBlockState(worldPos(output.pos))
            for ((propName, expectedValue) in expected) {
                val actualValue = blockStatePropertyStr(actualState, propName) ?: "missing"
                val label = output.label.ifEmpty { propName }
                checks += TickCheck(simTime, label, expectedValue, actualValue, actualValue == expectedValue)
            }
        }
    }

    // --- breakpoints ---

    private fun checkBreakpointsAt(simTime: SimTime) {
        for (bp in specCase.breakpoints) {
            if (!bp.enabled) continue
            if (evaluateCondition(bp.condition, level, worldPos(bp.pos))) {
                frozenAt = simTime
                pendingBreakpointHit = BreakpointHit(simTime, spec.name, specCase.name, bp.label.ifEmpty { bp.pos.toString() })
                return
            }
        }
    }

    // --- block-state helpers ---

    private fun setBlockStateProperties(pos: BlockPos, properties: Map<String, String>) {
        var state = level.getBlockState(pos)
        for ((name, value) in properties) {
            val property = state.block.stateDefinition.getProperty(name) ?: continue
            @Suppress("UNCHECKED_CAST")
            state = applyProperty(state, property as Property<Comparable<Any>>, value)
        }
        level.setBlock(pos, state, 3)
    }

    private fun <T : Comparable<T>> applyProperty(
        state: BlockState,
        property: Property<T>,
        valueStr: String,
    ): BlockState = property.getValue(valueStr).map { state.setValue(property, it) }.orElse(state)

    // --- condition evaluation (also used by breakpoints) ---

    fun evaluateCondition(condition: com.breadmoirai.redstonespecs.data.StateCondition, worldPos: BlockPos): Boolean =
        com.breadmoirai.redstonespecs.runner.evaluateCondition(condition, level, worldPos)

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
