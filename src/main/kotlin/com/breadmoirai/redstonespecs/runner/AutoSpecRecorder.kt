package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.Phase
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

// TODO milestone 8: implement rising/falling edge detection and diff-based StateSpec generation
class AutoSpecRecorder(
    val autoSpec: AutoSpec,
    val originPos: BlockPos,
    val level: ServerLevel,
) {
    fun onPhase(phase: Phase) {
        // stub
    }
}
