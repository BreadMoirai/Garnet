package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.outputs
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

data class VerificationResult(val checks: List<TickCheck>) {
    val pass: Boolean get() = checks.all { it.pass }
}

object OutputVerifier {
    /**
     * Verifies recorded outputs against the spec's declared output entries.
     *
     * For each output [SpecEntry], evaluates its condition against the post-tick block
     * state at the entry's [SimTime]. Any unexpected change (a tick where the post-state
     * differs from the previous post-state, but no entry declared it) emits a failing
     * TickCheck for that tick.
     */
    fun verify(spec: RedstoneSpec, recording: StateRecording): VerificationResult {
        val view = StateRecordingView.of(recording)
        val outputs = spec.outputs
        val byPos: Map<BlockPos, List<SpecEntry>> = outputs.groupBy { it.pos }

        val checks = buildList {
            for ((pos, entries) in byPos) {
                val initial = recording.initialSnapshot[pos]
                    ?: error("Output $pos not in recording snapshot")
                val labelOf = { e: SpecEntry -> e.label.ifEmpty { e.pos.toString() } }

                for (entry in entries) {
                    val state = view.stateAt(entry.pos, anchorTime(entry.time))
                    add(conditionCheck(entry.time, "${labelOf(entry)}@${entry.time.tick}", entry.condition, state))
                }

                val declaredTicks = entries.map { it.time.tick }.toSet()
                val postState = (0 until spec.lifespan).associateWith { t ->
                    view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
                }
                var prev: BlockState = initial
                val anyLabel = entries.firstOrNull()?.let(labelOf) ?: pos.toString()
                for (t in 0 until spec.lifespan) {
                    val cur = postState.getValue(t)
                    if (cur != prev && t !in declaredTicks) {
                        add(TickCheck(
                            SimTime(t, Phase.END_OF_TICK, 0),
                            "$anyLabel@t$t (unexpected change)",
                            "no change",
                            "changed",
                            pass = false,
                        ))
                    }
                    prev = cur
                }
            }
        }
        return VerificationResult(checks)
    }

    /** Resolves a SpecEntry time to the SimTime used for stateAt lookups (tail of the tick by default). */
    private fun anchorTime(time: SimTime): SimTime =
        if (time.order == 0 && time.phase == Phase.END_OF_TICK)
            SimTime(time.tick, Phase.END_OF_TICK, Int.MAX_VALUE)
        else time

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
