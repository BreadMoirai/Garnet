package com.breadmoirai.garnet.mc

import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import kotlin.coroutines.CoroutineContext

private val logger = LoggerFactory.getLogger("Garnet")

/** Threshold: log a warning if a server-thread Runnable runs longer than this. */
private const val WATCHDOG_THRESHOLD_MS = 100L

/**
 * Dispatcher that posts continuations to `MinecraftServer.execute`, ensuring blocks
 * run on the server thread. Short-circuits when already on the server thread so
 * nested `withContext(Server)` calls don't bounce through the executor twice.
 *
 * Wraps each dispatched Runnable in a watchdog: stalls > 100ms are logged but not
 * enforced. Test bodies are expected to keep `onServer { }` blocks short.
 */
class ServerThreadDispatcher(private val server: MinecraftServer) : CoroutineDispatcher() {

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (server.isSameThread) {
            runWithWatchdog(block)
        } else {
            server.execute { runWithWatchdog(block) }
        }
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !server.isSameThread

    private fun runWithWatchdog(block: Runnable) {
        val start = System.nanoTime()
        try {
            block.run()
        } finally {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (elapsedMs > WATCHDOG_THRESHOLD_MS) {
                logger.warn(
                    "Server-thread block ran {}ms (>{}ms threshold). Long compute on the server thread stalls the tick loop.",
                    elapsedMs, WATCHDOG_THRESHOLD_MS,
                )
            }
        }
    }
}

/** Holder for lifecycle-scoped Minecraft dispatchers. Set on SERVER_STARTED, cleared on SERVER_STOPPED. */
object McDispatchers {
    @Volatile private var _server: ServerThreadDispatcher? = null
    @Volatile private var _currentServer: MinecraftServer? = null

    val Server: CoroutineDispatcher
        get() = _server ?: error("McDispatchers.Server accessed before SERVER_STARTED or after SERVER_STOPPED")

    val currentServer: MinecraftServer
        get() = _currentServer ?: error("McDispatchers.currentServer accessed before SERVER_STARTED or after SERVER_STOPPED")

    internal fun install(server: MinecraftServer) {
        _server = ServerThreadDispatcher(server)
        _currentServer = server
    }

    internal fun uninstall() {
        _server = null
        _currentServer = null
    }
}
