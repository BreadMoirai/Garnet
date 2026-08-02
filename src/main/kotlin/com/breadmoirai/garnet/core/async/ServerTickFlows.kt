package com.breadmoirai.garnet.core.async

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import net.minecraft.server.MinecraftServer

object ServerTickFlows {
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

    /*
    Same-tick contract — DO NOT change without understanding the chain:
    1. tryEmit synchronously calls Continuation.resumeWith on suspended consumers.
    2. That resume goes through the consumer's CoroutineDispatcher.dispatch.
    3. ServerThreadDispatcher's isDispatchNeeded returns false on the server thread
    (we are the server thread, calling these emitters from inside END/START_SERVER_TICK),
    so the resumed continuation runs INLINE on this thread until its next suspension point.
    4. Any `withContext(Server)` work the test does after awaiting the tick lands on
    server.execute via the same dispatcher. Those Runnables join the executor queue.
    5. managedBlock then drains the executor queue synchronously, on this thread, before
    returning to MC. The whole "wait → do server-side work" sequence runs in one tick.

    If a future change adds a non-server-thread dispatcher to the consumer chain, this
    invariant breaks: continuations would queue elsewhere, managedBlock would drain an
    empty queue, and the same-tick guarantee would silently degrade to a race.
    */
    internal fun emitServerTickStart(server: MinecraftServer) {
        _serverTickStart.tryEmit(server)
        server.managedBlock { server.pendingTasksCount == 0 }
    }

    internal fun emitServerTickEnd(server: MinecraftServer) {
        _serverTickEnd.tryEmit(server)
        server.managedBlock { server.pendingTasksCount == 0 }
    }
}