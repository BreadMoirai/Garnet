package com.breadmoirai.garnet.harness

import com.breadmoirai.garnet.core.spec.GarnetSpec
import com.breadmoirai.garnet.playback.data.StateRecording
import com.breadmoirai.garnet.testing.runner.runGarnetSpec as runEngine
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.coroutines.coroutineContext

suspend fun runGarnetSpec(
    spec: GarnetSpec,
    originPos: BlockPos,
    level: ServerLevel,
): StateRecording {
    val recording = runEngine(level, originPos, spec)
    coroutineContext[RecordingHolder]?.recording = recording
    return recording
}
