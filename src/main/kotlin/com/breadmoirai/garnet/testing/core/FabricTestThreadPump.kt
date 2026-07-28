@file:Suppress("UnstableApiUsage")

package com.breadmoirai.garnet.testing.core

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue

/**
 * Lets code running on a Kotest worker thread hop briefly onto the Fabric
 * client-gametest test thread to call Fabric ctx.* APIs that assert
 * `ThreadingImpl.checkOnGametestThread` (`takeScreenshot`, `waitForScreen`,
 * `waitFor`, etc.).
 *
 * Wiring:
 * - `ClientTestSentinel.runKotestOnWorker` calls [drain] between every
 *   `context.waitTick()` so queued work runs at known tick boundaries.
 * - Workers post via [runOnTestThread]; the call blocks until the action
 *   finishes (or throws) on the test thread.
 *
 * Don't post long-running work — it stalls the tick loop. For multi-tick
 * Fabric calls (`waitFor`/`waitForScreen`), the action itself will call
 * `context.waitTick()` internally, which is allowed inside this drain.
 */
object FabricTestThreadPump {

    /**
     * One unit of work to run on the Fabric test thread.
     * `action` receives the active context and produces a result.
     */
    private class WorkItem<T>(
        val action: (ClientGameTestContext) -> T,
        val future: CompletableFuture<T>,
    )

    private val queue = LinkedBlockingQueue<WorkItem<*>>()

    /**
     * Posts [action] to run on the Fabric test thread, then blocks the caller
     * until it completes. Must NOT be called from the test thread itself
     * (would deadlock the drain loop).
     */
    fun <T> runOnTestThread(action: (ClientGameTestContext) -> T): T {
        val future = CompletableFuture<T>()
        queue.add(WorkItem(action, future))
        return future.get()
    }

    /**
     * Drains any pending work onto the calling thread. Called by the sentinel
     * from the Fabric test thread between `context.waitTick()` calls.
     *
     * Public-not-internal because Kotlin `internal` is per-sourceset; the
     * clientTest sourceset can't see `main`'s `internal` members.
     */
    fun drain(context: ClientGameTestContext) {
        while (true) {
            val item = queue.poll() ?: return
            @Suppress("UNCHECKED_CAST")
            val typed = item as WorkItem<Any?>
            try {
                typed.future.complete(typed.action(context))
            } catch (t: Throwable) {
                typed.future.completeExceptionally(t)
            }
        }
    }
}
