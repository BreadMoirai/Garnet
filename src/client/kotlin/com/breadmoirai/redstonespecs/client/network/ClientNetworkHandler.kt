package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

fun registerClientNetworking() {
    ClientPlayNetworking.registerGlobalReceiver(OverwritePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#overwritePrompt] specId={}", payload.specId)
            mc.setScreen(net.minecraft.client.gui.screens.ConfirmScreen(
                it.unimi.dsi.fastutil.booleans.BooleanConsumer { overwrite ->
                    mc.setScreen(null)
                    ClientPlayNetworking.send(OverwriteDecisionC2SPayload(payload.originPos, overwrite))
                },
                net.minecraft.network.chat.Component.literal("Blocks found inside bounds"),
                net.minecraft.network.chat.Component.literal("Overwrite existing blocks with structure '${payload.specId}'?"),
                net.minecraft.network.chat.Component.literal("Overwrite"),
                net.minecraft.network.chat.Component.literal("Skip Structure"),
            ))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenRecorderScreenS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.info("[ClientNetworkHandler] recorder UI removed (returns as a panel in sub-project A/B); ignoring open for {}", payload.originPos)
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenRunnerScreenS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.info("[ClientNetworkHandler] runner UI removed (returns as a panel in sub-project A/B); ignoring open for {}", payload.originPos)
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(RunnerStatusS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.debug("[ClientNetworkHandler] runner status (no UI): state={} summary={}", payload.state, payload.summary)
        }
    }
}
