package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.network.OpenRecorderScreenS2C
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.WorldHolder
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.apache.commons.lang3.function.FailableConsumer

/** Active `ClientGameTestContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun clientContext(): ClientGameTestContext = ClientContextHolder.context

/** Active `TestSingleplayerContext` installed by `ClientTestSentinel`. */
@Suppress("UnstableApiUsage")
fun currentWorld(): TestSingleplayerContext = WorldHolder.world

/**
 * Synchronously sends an S2C payload to the integrated server's first overworld
 * player. Runs on the server thread via `TestSingleplayerContext.runOnServer`,
 * which blocks the caller until the block completes — convenient for tests that
 * need to know the send happened before they assert.
 */
@Suppress("UnstableApiUsage")
private fun sendToLocalPlayer(action: (net.minecraft.server.MinecraftServer) -> Unit) {
    currentWorld().getServer().runOnServer(
        object : FailableConsumer<net.minecraft.server.MinecraftServer, RuntimeException> {
            override fun accept(server: net.minecraft.server.MinecraftServer) {
                action(server)
            }
        }
    )
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
