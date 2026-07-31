package com.breadmoirai.garnet.testing.runner

import com.breadmoirai.garnet.playback.data.StateRecording
import com.breadmoirai.garnet.playback.data.StateRecordingView
import com.breadmoirai.garnet.playback.recorder.StateRecorder
import com.breadmoirai.garnet.spec.Phase
import com.breadmoirai.garnet.spec.GarnetSpec
import com.breadmoirai.garnet.spec.SimTime
import com.breadmoirai.garnet.spec.SpecRun
import com.breadmoirai.garnet.spec.StateRecordingViewLike
import com.breadmoirai.garnet.mc.McDispatchers
import com.breadmoirai.garnet.mc.awaitTickEnd
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Run [spec] from inside a Kotest test body (or a server coroutine) and
 * assert its outputs.
 *
 *  1. Snapshot [origin] + bounds. Activate a recorder.
 *  2. Restore the snapshot so the run starts from a known state.
 *  3. Invoke [spec.block] once with a [SpecRun] backed by the live recorder
 *     buffer (`recorder.liveView()` — see Open Items in the design doc).
 *  4. For each tick `t in 0 until spec.lifespan`:
 *     - Fire `inputActions[(t, START_OF_TICK, *)]`.
 *     - `awaitTickEnd()`.
 *     - Fire `assertions[(t, END_OF_TICK, *)]`.
 *  5. If `spec.strict`, scan the recording for unexpected change-ticks at
 *     declared output positions and append failures.
 *  6. Restore the snapshot, deactivate the recorder.
 *  7. Throw [AssertionError] if any failures were collected.
 */
suspend fun runGarnetSpec(
    level: ServerLevel,
    origin: BlockPos,
    spec: GarnetSpec,
): StateRecording = withContext(McDispatchers.Server) {
    val snapshot = SpecSnapshot.capture(level, origin, spec.bounds)
    val recorderId = UUID.randomUUID()
    val recorder = StateRecorder.forSpec(recorderId, origin, spec.bounds)

    try {
        recorder.start(level, origin, spec.bounds)
        StateRecorder.activate(recorder)
        snapshot.restore(level)

        val run = SpecRun(
            level = level,
            origin = origin,
            recordingView = { recorderLiveView(recorder) },
        )
        spec.block(run)

        for (tick in 0 until spec.lifespan) {
            // Fire all START_OF_TICK input actions for this tick (any phase
            // counted as start-of-tick: we filter by tick + phase).
            val startKey = SimTime(tick, Phase.START_OF_TICK)
            run.inputActions
                .subMap(SimTime(tick, Phase.START_OF_TICK, Int.MIN_VALUE), true,
                        SimTime(tick, Phase.START_OF_TICK, Int.MAX_VALUE), true)
                .values.flatten().forEach { it() }

            awaitTickEnd()

            run.assertions
                .subMap(SimTime(tick, Phase.END_OF_TICK, Int.MIN_VALUE), true,
                        SimTime(tick, Phase.END_OF_TICK, Int.MAX_VALUE), true)
                .values.flatten().forEach { it() }
        }

        if (spec.strict) {
            scanForUnexpectedChanges(recorder, run, spec.lifespan)
        }

        if (run.failures.isNotEmpty()) {
            throw AssertionError(
                "assertOutputsMatch failed:\n" + run.failures.joinToString("\n") { it.render() }
            )
        }
    } finally {
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }

    return@withContext recorder.toRecording()
}

private fun recorderLiveView(recorder: StateRecorder): StateRecordingViewLike {
    // See Open Items in the design doc — Phase 2 must expose a live read API
    // on StateRecorder. Until that exists, fall back to building a recording
    // snapshot on each call (slower but correct for short tests).
    return object : StateRecordingViewLike {
        override fun stateAt(pos: net.minecraft.core.BlockPos, time: com.breadmoirai.garnet.spec.SimTime) =
            StateRecordingView.of(recorder.toRecording()).stateAt(pos, time)

        override fun initialAt(pos: net.minecraft.core.BlockPos) =
            recorder.toRecording().initialSnapshot[pos]
                ?: error("Position $pos not in recorder snapshot")
    }
}

private fun scanForUnexpectedChanges(
    recorder: StateRecorder,
    run: SpecRun,
    lifespan: Int,
) {
    val recording = recorder.toRecording()
    val view = StateRecordingView.of(recording)
    for ((pos, declaredTicks) in run.outputDeclaredTicks) {
        val initial = recording.initialSnapshot[pos] ?: continue
        var prev = initial
        for (t in 0 until lifespan) {
            val cur = view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            if (cur != prev && t !in declaredTicks) {
                run.reportFailure(
                    com.breadmoirai.garnet.spec.SpecFailure(
                        label = pos.toString(),
                        time = SimTime(t, Phase.END_OF_TICK),
                        message = "unexpected change (expected no change, got changed)",
                    )
                )
            }
            prev = cur
        }
    }
}
