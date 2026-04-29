package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.TickCheck
import net.minecraft.world.level.block.state.BlockState

data class VerificationResult(val checks: List<TickCheck>) {
    val pass: Boolean get() = checks.all { it.pass }
}

object OutputVerifier {
    fun verify(spec: RedstoneSpec, recording: StateRecording): VerificationResult {
        val view = StateRecordingView.of(recording)
        val checks = buildList {
            for (output in spec.outputs) {
                when (spec.mode) {
                    SpecMode.SIMPLE -> verifySimple(output, recording, view, spec.lifespan, this)
                    SpecMode.TICK_AWARE,
                    SpecMode.UPDATE_AWARE -> {
                        // Implemented in later tasks.
                    }
                }
            }
        }
        return VerificationResult(checks)
    }

    private fun verifySimple(
        output: OutputSpec,
        recording: StateRecording,
        view: StateRecordingView,
        lifespan: Int,
        out: MutableList<TickCheck>,
    ) {
        val label = output.label.ifEmpty { output.pos.toString() }
        val startEntry = output.entries.firstOrNull { it.first == SimTime.START }
        val endEntry = output.entries.firstOrNull { it.first == SimTime.END }

        if (startEntry != null) {
            val initial = recording.initialSnapshot[output.pos]
                ?: error("Output ${output.pos} not in recording snapshot")
            out += conditionCheck(SimTime.START, "$label@start", startEntry.second, initial)
        }
        if (endEntry != null) {
            val finalState = view.stateAt(output.pos, SimTime(lifespan, Phase.END_OF_TICK, Int.MAX_VALUE))
            out += conditionCheck(SimTime.END, "$label@end", endEntry.second, finalState)
        }
    }

    private fun conditionCheck(
        simTime: SimTime,
        label: String,
        condition: StateCondition,
        state: BlockState,
    ): TickCheck {
        val pass = evaluateConditionOnState(condition, state)
        val expected = describeCondition(condition)
        val actual = describeStateForCondition(condition, state)
        return TickCheck(simTime, label, expected, actual, pass)
    }

    private fun evaluateConditionOnState(condition: StateCondition, state: BlockState): Boolean = when (condition) {
        is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state) }
        is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state) }
        is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state)
        is StateCondition.BlockType -> {
            val actualId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block)
            actualId == condition.blockId
        }
        is StateCondition.BoolProperty -> {
            val prop = state.block.stateDefinition.getProperty(condition.name)
                as? net.minecraft.world.level.block.state.properties.BooleanProperty ?: return false
            state.getValue(prop) == condition.value
        }
        is StateCondition.IntProperty -> {
            val prop = state.block.stateDefinition.getProperty(condition.name)
                as? net.minecraft.world.level.block.state.properties.IntegerProperty ?: return false
            state.getValue(prop) == condition.value
        }
        is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) == condition.value
        is StateCondition.ContainerContents,
        is StateCondition.IntRange -> false
    }

    private fun describeCondition(condition: StateCondition): String = when (condition) {
        is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
        is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
        is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
        is StateCondition.BlockType -> "block=${condition.blockId}"
        is StateCondition.All -> condition.conditions.joinToString(",") { describeCondition(it) }
        is StateCondition.Any -> condition.conditions.joinToString("|") { describeCondition(it) }
        is StateCondition.Not -> "!${describeCondition(condition.condition)}"
        is StateCondition.ContainerContents -> "container"
        is StateCondition.IntRange -> "${condition.name}=${condition.min}..${condition.max}"
    }

    private fun describeStateForCondition(condition: StateCondition, state: BlockState): String = when (condition) {
        is StateCondition.BoolProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
        is StateCondition.IntProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
        is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
        is StateCondition.BlockType ->
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
        else -> "(complex)"
    }
}
