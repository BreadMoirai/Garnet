package com.breadmoirai.garnet.testing

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Per-thread binding of originPos/level for an engine-driven GarnetTestSpec run.
 * EngineDrivenRun.bind(...)s before instantiating the Spec class; clear()s after.
 */
internal object GarnetTestSpecContext {
    private val ctx = ThreadLocal<Binding?>()

    data class Binding(val originPos: BlockPos, val level: ServerLevel)

    fun bind(originPos: BlockPos, level: ServerLevel) { ctx.set(Binding(originPos, level)) }
    fun clear() = ctx.remove()
    fun current(): Binding =
        ctx.get() ?: error("No GarnetTestSpecContext bound. GarnetTestSpec must be launched via EngineDrivenRun.")
}
