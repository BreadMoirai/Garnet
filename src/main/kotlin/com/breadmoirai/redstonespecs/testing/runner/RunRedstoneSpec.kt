package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.runRedstoneSpec as runEngine
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.coroutines.coroutineContext

suspend fun runRedstoneSpec(
    spec: RedstoneSpec,
    originPos: BlockPos,
    level: ServerLevel,
): StateRecording {
    val recording = runEngine(level, originPos, spec)
    coroutineContext[RecordingHolder]?.recording = recording
    return recording
}
