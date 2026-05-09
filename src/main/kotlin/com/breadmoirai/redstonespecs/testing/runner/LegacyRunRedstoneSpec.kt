package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.data.RedstoneSpec as DataRedstoneSpec
import com.breadmoirai.redstonespecs.runner.SpecRunner
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import com.breadmoirai.redstonespecs.runner.SpecSnapshot
import com.breadmoirai.redstonespecs.runner.StateRecorder
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.awaitTickEnd
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Legacy bridge: runs a [DataRedstoneSpec] using the old [SpecRunnerCoordinator] engine.
 * Kept so that `.spec.kts` files emitted by [com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter]
 * (which use the old `data.dsl.redstoneSpec { … }` DSL) can still compile and run.
 *
 * TODO Phase 6 (Task 24): delete this file along with the rest of the `data/` layer.
 */
suspend fun runRedstoneSpec(
    spec: DataRedstoneSpec,
    originPos: BlockPos,
    level: ServerLevel,
): StateRecording = withContext(McDispatchers.Server) {
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
        while (!runner.isComplete) {
            awaitTickEnd()
        }
    } finally {
        SpecRunnerCoordinator.unregisterStandalone(runner)
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }

    val recording = recorder.toRecording()
    coroutineContext[RecordingHolder]?.recording = recording
    assertOutputsMatch(spec, recording)
    recording
}
