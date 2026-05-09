package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.client.screen.RunnerScreen
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
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openRecorderScreen] originPos={} state={}", payload.originPos, payload.state)
            mc.setScreen(RecorderScreen(
                originPos = payload.originPos,
                initialSpecId = payload.specId,
                initialOutPath = payload.outPath,
                initialStructureId = payload.structureId,
                initialState = payload.state,
            ))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenRunnerScreenS2C.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openRunnerScreen] originPos={} specPath={} specCount={}",
                payload.originPos, payload.specPath, payload.specList.size)
            val screen = RunnerScreen(
                originPos = payload.originPos,
                initialSpecPath = payload.specPath,
                specList = payload.specList,
                initialMeta = payload.meta,
            )
            RunnerScreen.active = screen
            mc.setScreen(screen)
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(RunnerStatusS2C.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#runnerStatus] originPos={} state={} summary={}",
                payload.originPos, payload.state, payload.summary)
            val active = RunnerScreen.active
            if (active != null && active.originPos == payload.originPos) {
                active.pushStatus(payload.state, payload.summary)
            }
        }
    }
}
