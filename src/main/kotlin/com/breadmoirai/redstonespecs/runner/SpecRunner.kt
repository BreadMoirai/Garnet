package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.SpecCaseResult
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

class SpecRunner(
    val spec: RedstoneSpec,
    val specCase: SpecCase,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1
    private val checks = mutableListOf<TickCheck>()

    fun start() {
        applyInputsAt(SimTime.INIT)
    }

    fun resetCircuit() {
        snapshot.restore(level)
    }

    /**
     * Called at each sub-tick phase boundary. Returns a completed [SpecCaseResult] once
     * [SpecCase.lifespan] ticks have elapsed, null while still running.
     */
    fun onPhase(phase: Phase): SpecCaseResult? {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return null
        if (ticksElapsed >= specCase.lifespan) {
            return SpecCaseResult(specCase.name, checks.toList())
        }
        val simTime = SimTime(ticksElapsed, phase)
        applyInputsAt(simTime)
        checkOutputsAt(simTime)
        return null
    }

    private fun applyInputsAt(simTime: SimTime) {
        for (input in specCase.inputs) {
            val (_, props) = input.stateSpec.entries.find { it.first == simTime } ?: continue
            if (props.isEmpty()) continue
            val worldPos = worldPos(input.pos)
            setBlockStateProperties(worldPos, props)
        }
    }

    private fun checkOutputsAt(simTime: SimTime) {
        for (output in specCase.outputs) {
            val (_, expected) = output.stateSpec.entries.find { it.first == simTime } ?: continue
            if (expected.isEmpty()) continue
            val worldPos = worldPos(output.pos)
            val actualState = level.getBlockState(worldPos)
            for ((propName, expectedValue) in expected) {
                val actualValue = getPropertyStr(actualState, propName) ?: "missing"
                val label = output.label.ifEmpty { propName }
                checks += TickCheck(simTime, label, expectedValue, actualValue, actualValue == expectedValue)
            }
        }
    }

    // --- Block-state helpers ---

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

    private fun getPropertyStr(state: BlockState, propName: String): String? {
        val property = state.block.stateDefinition.getProperty(propName) ?: return null
        if (!state.hasProperty(property)) return null
        @Suppress("UNCHECKED_CAST")
        return readProperty(state, property as Property<Comparable<Any>>)
    }

    private fun <T : Comparable<T>> readProperty(state: BlockState, property: Property<T>): String =
        property.getName(state.getValue(property))

    // --- Condition evaluation (used by BreakpointSpec / AutoSpec in later milestones) ---

    fun evaluateCondition(condition: StateCondition, worldPos: BlockPos): Boolean = when (condition) {
        is StateCondition.All -> condition.conditions.all { evaluateCondition(it, worldPos) }
        is StateCondition.Any -> condition.conditions.any { evaluateCondition(it, worldPos) }
        is StateCondition.Not -> !evaluateCondition(condition.condition, worldPos)
        is StateCondition.BlockState -> {
            val state = level.getBlockState(worldPos)
            condition.properties.all { (name, expected) ->
                getPropertyStr(state, name) == expected
            }
        }
        is StateCondition.ContainerContents -> false // TODO milestone 8
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
