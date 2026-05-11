package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.network.OpenRecorderScreenS2C
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
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
 * Handler name used when installing the outbound interceptor into a [LocalChannel] pipeline.
 * Stable so we can check for presence before re-installing.
 */
private const val CLIENT_PAYLOAD_INTERCEPTOR = "redstonespecs_c2s_capture"

/**
 * Thread-safe queue that accumulates C2S [CustomPacketPayload]s captured by the
 * pipeline interceptor installed in [drainClientPayloads]. Written on the render/Netty
 * IO thread; drained on the Kotest worker thread (all accesses are synchronized on the
 * list itself).
 */
private val capturedClientPayloads =
    java.util.Collections.synchronizedList(mutableListOf<net.minecraft.network.protocol.common.custom.CustomPacketPayload>())

/**
 * Reads all [CustomPacketPayload]s sent FROM the client TO the server since the
 * last call. Mirrors [drainPayloads] for the inverse direction — useful for
 * asserting C2S round-trips triggered by UI interactions (e.g. a ConfirmScreen
 * button click that sends `OverwriteDecisionC2SPayload`).
 *
 * Single-player integrated mode uses a [LocalChannel] rather than an [EmbeddedChannel].
 * On the first call for a given connection this function installs a
 * [ChannelOutboundHandlerAdapter] into the pipeline that intercepts every write and
 * records [ServerboundCustomPayloadPacket] payloads into [capturedClientPayloads].
 * Subsequent calls simply drain that list.
 */
fun drainClientPayloads(): List<net.minecraft.network.protocol.common.custom.CustomPacketPayload> {
    // Install the pipeline interceptor (idempotent — checks for existing handler).
    onClient { mc ->
        val listener = mc.connection ?: return@onClient
        val conn = (listener as com.breadmoirai.redstonespecs.mixin.client.ClientCommonPacketListenerImplAccessor)
            .`redstonespecs$getConnection`()
        val ch = (conn as com.breadmoirai.redstonespecs.mixin.ConnectionAccessor).`redstonespecs$getChannel`()
        if (ch.pipeline().get(CLIENT_PAYLOAD_INTERCEPTOR) == null) {
            // Insert after "packet_handler" so we see the Packet object before the encoder
            // converts it to a HiddenByteBuf. Outbound writes traverse the pipeline in
            // reverse order (tail→head), so "after packet_handler" means our handler is
            // invoked immediately after packet_handler fires ctx.write().
            ch.pipeline().addAfter(
                "packet_handler",
                CLIENT_PAYLOAD_INTERCEPTOR,
                object : io.netty.channel.ChannelOutboundHandlerAdapter() {
                    override fun write(
                        ctx: io.netty.channel.ChannelHandlerContext,
                        msg: Any,
                        promise: io.netty.channel.ChannelPromise,
                    ) {
                        if (msg is net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) {
                            capturedClientPayloads.add(msg.payload())
                        }
                        ctx.write(msg, promise)
                    }
                },
            )
        }
    }
    // Drain and return whatever was accumulated since the last call.
    val snapshot = synchronized(capturedClientPayloads) {
        val copy = capturedClientPayloads.toList()
        capturedClientPayloads.clear()
        copy
    }
    return snapshot
}

/**
 * Hops to the render thread (via the Fabric test thread + `computeOnClient`) to
 * evaluate [action] with a safe `Minecraft` reference, and returns the result.
 *
 * Fabric instruments `Minecraft.getInstance()` to throw if called from anywhere
 * other than the render thread. From a Kotest worker thread, route every
 * `Minecraft` field access through this helper.
 */
/** Runs [action] on the render thread, ignoring any return value. */
fun runOnClient(action: (net.minecraft.client.Minecraft) -> Unit) {
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> action(mc) }
    }
}

/**
 * Runs [action] on the render thread and returns its result. Routes nullable
 * returns through a holder because Fabric's `computeOnClient` is typed `<T : Any>`.
 */
@Suppress("UNCHECKED_CAST")
fun <T> onClient(action: (net.minecraft.client.Minecraft) -> T): T {
    val holder = arrayOf<Any?>(null)
    FabricTestThreadPump.runOnTestThread { ctx ->
        ctx.runOnClient<RuntimeException> { mc -> holder[0] = action(mc) }
    }
    return holder[0] as T
}

/**
 * Polls the client's current screen until it is an instance of [screenClass], or
 * until [timeoutMs] elapses. Returns the live screen (which is only safe to inspect
 * via [onClient]).
 */
fun waitForClientScreen(
    screenClass: Class<out net.minecraft.client.gui.screens.Screen>,
    timeoutMs: Long = 5000,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        val matched = onClient { mc -> screenClass.isInstance(mc.screen) }
        if (matched) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.screen?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for screen ${screenClass.simpleName}; current is ${current ?: "null"}")
}

/**
 * Closes the active screen by hopping to the render thread and calling
 * `mc.setScreen(null)`. Polls until the change is observed.
 */
fun closeClientScreen(timeoutMs: Long = 5000) {
    onClient { mc -> mc.setScreen(null) }
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (onClient { mc -> mc.screen } == null) return
        Thread.sleep(50)
    }
    val current = onClient { mc -> mc.screen?.javaClass?.simpleName }
    error("Timed out after ${timeoutMs}ms waiting for client screen to clear; current is $current")
}

/**
 * Takes a screenshot of the current client state by hopping to the Fabric test
 * thread (where `ctx.takeScreenshot` is legal). Returns the file path written.
 * Useful for diagnosing test-time UI state — store the path or open the file
 * after the test run.
 */
fun takeClientScreenshot(name: String): java.nio.file.Path =
    FabricTestThreadPump.runOnTestThread { ctx -> ctx.takeScreenshot(name) }

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
