package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.network.OpenRecorderScreenS2C
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.core.WorldHolder
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import java.util.concurrent.CountDownLatch

/** Active `ClientGameTestContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun clientContext(): ClientGameTestContext = ClientContextHolder.context

/** Active `TestSingleplayerContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun currentWorld(): TestSingleplayerContext = WorldHolder.world

/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player.
 *
 * `RedstoneTestSpec` dispatches test bodies onto the server thread via
 * `withContext(McDispatchers.Server)`, so by the time this helper runs we are
 * usually already on the server thread; in that case we call [action] inline.
 * If we somehow end up off-thread (e.g., a future spec that detaches), fall
 * back to `server.execute` + a latch.
 */
private fun sendToLocalPlayer(action: (net.minecraft.server.MinecraftServer) -> Unit) {
    val server = McDispatchers.currentServer
    if (server.isSameThread) {
        action(server)
        return
    }
    val latch = CountDownLatch(1)
    var thrown: Throwable? = null
    server.execute {
        try {
            action(server)
        } catch (t: Throwable) {
            thrown = t
        } finally {
            latch.countDown()
        }
    }
    latch.await()
    thrown?.let { throw it }
}

fun sendOpenRecorderScreen(payload: OpenRecorderScreenS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendOpenRunnerScreen(payload: OpenRunnerScreenS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendRunnerStatus(payload: RunnerStatusS2C) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

fun sendOverwritePrompt(payload: OverwritePromptS2CPayload) {
    sendToLocalPlayer { server ->
        val player = server.overworld().players().firstOrNull() ?: return@sendToLocalPlayer
        ServerPlayNetworking.send(player, payload)
    }
}

/**
 * Polls `Minecraft.getInstance().screen` from any thread (including a Kotest worker
 * thread) until it is an instance of [screenClass], or until [timeoutMs] elapses.
 *
 * `ClientGameTestContext.waitForScreen` asserts being called from Fabric's gametest
 * test thread (`ThreadingImpl.checkOnGametestThread`); Kotest specs run on a worker
 * thread, so we can't use it. The screen field is updated by `mc.execute` from
 * `ClientNetworkHandler` receivers; the test thread drives client ticks via
 * `context.waitTick()` inside `ClientTestSentinel.runKotestOnWorker`, so polling
 * eventually observes the change.
 */
fun waitForClientScreen(
    screenClass: Class<out net.minecraft.client.gui.screens.Screen>,
    timeoutMs: Long = 5000,
): net.minecraft.client.gui.screens.Screen {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val s = net.minecraft.client.Minecraft.getInstance().screen
        if (screenClass.isInstance(s)) return s!!
        Thread.sleep(50)
    }
    val current = net.minecraft.client.Minecraft.getInstance().screen
    error("Timed out after ${timeoutMs}ms waiting for screen ${screenClass.simpleName}; current is ${current?.javaClass?.simpleName ?: "null"}")
}

/**
 * Sets the client screen to `null` on the render thread, then polls until the
 * change is observed. Worker-thread safe replacement for
 * `ctx.getInput().pressKey(GLFW_KEY_ESCAPE) + ctx.waitFor { mc.screen == null }`.
 */
fun closeClientScreen(timeoutMs: Long = 2000) {
    val mc = net.minecraft.client.Minecraft.getInstance()
    mc.execute { mc.setScreen(null) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (mc.screen == null) return
        Thread.sleep(50)
    }
    error("Timed out after ${timeoutMs}ms waiting for client screen to clear; current is ${mc.screen?.javaClass?.simpleName}")
}

/**
 * Worker-thread sleep that advances wall-clock; useful when we need the client to
 * process pending render-thread tasks (e.g., a `mc.execute`-posted screen update)
 * before checking state. Acts as a memory barrier so subsequent field reads see
 * recent writes.
 */
fun waitClientTicks(ticks: Int) {
    // Each MC tick is ~50ms; sleep n*50ms plus a small margin. The actual tick
    // advancement is driven by the test thread's `context.waitTick()` loop in
    // ClientTestSentinel; we just yield long enough for those ticks to land.
    Thread.sleep(ticks * 50L + 50L)
}
