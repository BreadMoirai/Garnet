package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.runner.SpecRunner
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import com.breadmoirai.redstonespecs.runner.SpecSnapshot
import com.breadmoirai.redstonespecs.runner.StateRecorder
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.awaitTickEnd
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Executes a [RedstoneSpec] from inside a Kotest test body and asserts its outputs.
 *
 * Drives the existing record→run→sample→verify pipeline as a single suspend call:
 *
 *  1. Captures a [SpecSnapshot] of the bounds at [originPos].
 *  2. Starts a [StateRecorder] over the same bounds.
 *  3. Registers a [SpecRunner] with [SpecRunnerCoordinator] as a standalone runner;
 *     the coordinator's tick-phase forwarding then drives the runner forward.
 *  4. Suspends one tick at a time via [awaitTickEnd] until the runner completes.
 *  5. Calls [assertOutputsMatch] with the recorded post-states.
 *  6. Restores the snapshot and unregisters / deactivates regardless of pass/fail.
 *
 * Returns the captured [com.breadmoirai.redstonespecs.runner.StateRecording] so callers
 * (and Plan E's DiagnosticRecorder TestListener) can attach it to test metadata.
 *
 * Must be called from inside a [com.breadmoirai.redstonespecs.testing.RedstoneTestSpec]
 * test body, which already dispatches on [McDispatchers.Server].
 */
suspend fun runRedstoneSpec(
    spec: RedstoneSpec,
    originPos: BlockPos,
    level: ServerLevel,
): com.breadmoirai.redstonespecs.runner.StateRecording = withContext(McDispatchers.Server) {
    val snapshot = SpecSnapshot.capture(level, originPos, spec.bounds)
    val recorderId = UUID.randomUUID()
    val recorder = StateRecorder.forSpec(recorderId, originPos, spec.bounds)
    val runner = SpecRunner(spec, originPos, level, snapshot)

    try {
        recorder.start(level, originPos, spec.bounds)
        StateRecorder.activate(recorder)
        snapshot.restore(level)
        SpecRunnerCoordinator.registerStandalone(runner)

        runner.start()
        // Each awaitTickEnd advances exactly one server tick.
        // SpecRunnerCoordinator.onPhase forwards START_OF_TICK and END_OF_TICK to standalone
        // runners; by the time awaitTickEnd returns, runner.onPhase has been called for
        // END_OF_TICK and isComplete reflects this tick's outcome.
        while (!runner.isComplete) {
            awaitTickEnd()
        }
    } finally {
        SpecRunnerCoordinator.unregisterStandalone(runner)
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }

    val recording = recorder.toRecording()
    assertOutputsMatch(spec, recording)
    recording
}
