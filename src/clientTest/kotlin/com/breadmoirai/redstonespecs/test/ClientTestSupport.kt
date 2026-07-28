package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
import com.breadmoirai.redstonespecs.testing.core.WorldHolder
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext

/** Active `ClientGameTestContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun clientContext(): ClientGameTestContext = ClientContextHolder.context

/** Active `TestSingleplayerContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun currentWorld(): TestSingleplayerContext = WorldHolder.world

/**
 * Hops to the render thread (via the Fabric test thread + `ctx.runOnClient`) to
 * evaluate [action] with a safe `Minecraft` reference, and returns the result.
 *
 * Fabric instruments `Minecraft.getInstance()` to throw if called from anywhere
 * other than the render thread. From a Kotest worker, route every `Minecraft`
 * field access through this helper.
 *
 * Nullable returns are routed through a holder because Fabric's `computeOnClient`
 * is typed `<T : Any>`; we use `runOnClient` + a captured array instead.
 */
@Suppress("UNCHECKED_CAST")
fun <T> onClient(action: (net.minecraft.client.Minecraft) -> T): T {
    val holder = arrayOf<Any?>(null)
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> holder[0] = action(mc) }
    }
    return holder[0] as T
}

/** Runs [action] on the render thread, ignoring any return value. */
fun runOnClient(action: (net.minecraft.client.Minecraft) -> Unit) {
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> action(mc) }
    }
}

/**
 * Polls the client's current screen until it is an instance of [screenClass], or
 * until [timeoutMs] elapses. Throws on timeout. To inspect the screen afterward,
 * fetch it explicitly via `onClient { mc -> mc.gui.screen() }` — direct access from the
 * calling thread is unsafe.
 */
fun waitForClientScreen(
    screenClass: Class<out net.minecraft.client.gui.screens.Screen>,
    timeoutMs: Long = 5000,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val matched = onClient { mc -> screenClass.isInstance(mc.gui.screen()) }
        if (matched) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.gui.screen()?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for screen ${screenClass.simpleName}; current is ${current ?: "null"}")
}

/**
 * Closes the active screen by hopping to the render thread and calling
 * `mc.setScreen(null)`. Polls until the change is observed.
 */
fun closeClientScreen(timeoutMs: Long = 5000) {
    onClient { mc -> mc.gui.setScreen(null) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (onClient { mc -> mc.gui.screen() } == null) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.gui.screen()?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for client screen to clear; current is $current")
}

/**
 * Takes a screenshot of the current client state by hopping to the Fabric test
 * thread (where `ctx.takeScreenshot` is legal). Returns the file path written.
 * Useful for diagnosing test-time UI state — store the path or open the file
 * after the test run. See `docs/gametest/screenshots-for-debug-and-regression.md`.
 */
fun takeClientScreenshot(name: String): java.nio.file.Path =
    FabricTestThreadPump.runOnTestThread { ctx -> ctx.takeScreenshot(name) }

/**
 * Sleeps the calling thread for [ticks] MC ticks' worth of wall time (~50ms each)
 * plus a small margin. Used when the test needs to let the client process pending
 * render-thread work (e.g. an `mc.execute`-posted state change) before checking.
 *
 * The actual tick advancement is driven by `ClientTestSentinel`'s `context.waitTick()`
 * loop on the Fabric test thread; this helper just yields long enough for those
 * ticks to land before the caller proceeds.
 */
fun waitClientTicks(ticks: Int) {
    Thread.sleep(ticks * 50L + 50L)
}
