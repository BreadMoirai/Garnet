package com.breadmoirai.garnet.core.async

import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.server.MinecraftServer

object AsyncDispatchers {
    @Volatile private var _server: ServerThreadDispatcher? = null
    @Volatile private var _currentServer: MinecraftServer? = null

    val Server: CoroutineDispatcher
        get() = _server ?: error("AsyncDispatchers.Server accessed before SERVER_STARTED or after SERVER_STOPPED")

    val currentServer: MinecraftServer
        get() = _currentServer ?: error("AsyncDispatchers.currentServer accessed before SERVER_STARTED or after SERVER_STOPPED")

    internal fun install(server: MinecraftServer) {
        _server = ServerThreadDispatcher(server)
        _currentServer = server
    }

    internal fun uninstall() {
        _server = null
        _currentServer = null
    }
}