package com.breadmoirai.redstonespecs.testing.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.minecraft.server.MinecraftServer

private val _serverTickStart = MutableSharedFlow<MinecraftServer>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
private val _serverTickEnd = MutableSharedFlow<MinecraftServer>(
    replay = 0,
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)

/** Emits each `ServerTickEvents.START_SERVER_TICK`. */
val serverTickStart: SharedFlow<MinecraftServer> = _serverTickStart.asSharedFlow()

/** Emits each `ServerTickEvents.END_SERVER_TICK`. */
val serverTickEnd: SharedFlow<MinecraftServer> = _serverTickEnd.asSharedFlow()

internal fun emitServerTickStart(server: MinecraftServer) {
    _serverTickStart.tryEmit(server)
}

internal fun emitServerTickEnd(server: MinecraftServer) {
    _serverTickEnd.tryEmit(server)
}
