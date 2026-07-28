package com.breadmoirai.garnet.testing.server

import com.breadmoirai.garnet.testing.core.McDispatchers
import com.breadmoirai.garnet.testing.core.serverTickEnd
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
import net.minecraft.server.MinecraftServer

/** Suspends until the next END_SERVER_TICK and returns the server. */
suspend fun awaitTickEnd(): MinecraftServer = serverTickEnd.first()

/** Suspends until [n] END_SERVER_TICK events have fired, returning the server at the last one. */
suspend fun awaitTicks(n: Int): MinecraftServer {
    require(n >= 1) { "awaitTicks requires n >= 1, got $n" }
    return serverTickEnd.take(n).last()
}

/** Suspends until an END_SERVER_TICK satisfies [predicate]. */
suspend fun awaitTickWhere(predicate: (MinecraftServer) -> Boolean): MinecraftServer =
    serverTickEnd.first(predicate)

/** Hops to the server thread, runs [block], returns the result. */
suspend fun <T> onServer(block: suspend MinecraftServer.() -> T): T =
    withContext(McDispatchers.Server) { block(McDispatchers.currentServer) }
