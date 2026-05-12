package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.network.OpenRecorderScreenS2C
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import java.util.concurrent.CountDownLatch

/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player.
 *
 * Hops to the server thread via `MinecraftServer.execute` and waits on a
 * `CountDownLatch`. The `isSameThread` fast path is a safety net for callers
 * that already happen to be on the server thread (e.g. a future
 * `RedstoneTestSpec`-based caller); `ClientSpec` runs test bodies on
 * `Dispatchers.Default`, so in normal use we always take the post-and-wait path.
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

