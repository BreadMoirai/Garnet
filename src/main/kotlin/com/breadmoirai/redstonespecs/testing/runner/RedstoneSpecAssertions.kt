package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.outputs
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.StateRecordingView
import com.breadmoirai.redstonespecs.runner.anchorTime
import com.breadmoirai.redstonespecs.runner.describeCondition
import com.breadmoirai.redstonespecs.runner.describeStateForCondition
import com.breadmoirai.redstonespecs.runner.evaluateConditionOnState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Asserts that all declared OUTPUT entries in [spec] are satisfied by [recording].
 *
 * Throws
 * [AssertionError] on mismatch instead of returning a result object, making it suitable
 * for direct use inside Kotest test bodies.
 *
 * Also detects "unexpected change" ticks — ticks where the post-state changed but no entry
 * declared the change.
 *
 * @throws AssertionError if any output condition fails or an unexpected change is detected.
 */
fun assertOutputsMatch(spec: RedstoneSpec, recording: StateRecording) {
    val view = StateRecordingView.of(recording)
    val outputs = spec.outputs
    val byPos: Map<BlockPos, List<SpecEntry>> = outputs.groupBy { it.pos }

    val failures = buildList {
        for ((pos, entries) in byPos) {
            val initial: BlockState = recording.initialSnapshot[pos]
                ?: error("Output $pos not in recording snapshot")
            val labelOf = { e: SpecEntry -> e.label.ifEmpty { e.pos.toString() } }

            for (entry in entries) {
                val anchoredTime = anchorTime(entry.time)
                val state = view.stateAt(entry.pos, anchoredTime)
                if (!evaluateConditionOnState(entry.condition, state)) {
                    val expected = describeCondition(entry.condition)
                    val actual = describeStateForCondition(entry.condition, state)
                    add("FAIL ${labelOf(entry)} at tick ${entry.time.tick}: expected $expected but got $actual")
                }
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
                    add("FAIL $anyLabel at tick $t: unexpected change (expected no change, got changed)")
                }
                prev = cur
            }
        }
    }

    if (failures.isNotEmpty()) {
        throw AssertionError("assertOutputsMatch failed:\n" + failures.joinToString("\n"))
    }
}

