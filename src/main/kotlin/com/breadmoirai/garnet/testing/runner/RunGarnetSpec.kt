package com.breadmoirai.garnet.testing.runner

import com.breadmoirai.garnet.dsl.GarnetSpec
import com.breadmoirai.garnet.runner.StateRecording
import com.breadmoirai.garnet.runner.runGarnetSpec as runEngine
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
