package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.network.BreakpointHitS2CPayload
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

fun registerClientNetworking() {
    ClientPlayNetworking.registerGlobalReceiver(OpenOverviewS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openOverview] originPos={}", payload.originPos)
            mc.setScreen(SpecOverviewScreen(payload.originPos, payload.availableStructures))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenEditorS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#openEditor] originPos={} entryRelPos={}", payload.originPos, payload.entryRelPos)
            mc.setScreen(SpecEditorScreen(payload.originPos, payload.entryRelPos))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(TestResultS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            val passCount = payload.result.passCount
            val total = payload.result.checks.size
            LOGGER.debug("[ClientNetworkHandler#testResult] originPos={} {}/{} passed", payload.originPos, passCount, total)
            val color = if (payload.result.pass) "§a" else "§c"
            mc.player?.sendSystemMessage(
                Component.literal("${color}Spec run complete: $passCount/$total checks passed")
            )
            // If the overview screen is open for this origin, reopen to refresh
            val current = mc.screen
            if (current is SpecOverviewScreen && current.originPos == payload.originPos) {
                mc.setScreen(SpecOverviewScreen(payload.originPos, emptyList()))
            }
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(BreakpointHitS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#breakpointHit] '{}' in spec '{}' at {}t {}", payload.breakpointLabel, payload.specId, payload.simTime.tick, payload.simTime.phase.name)
            mc.player?.sendSystemMessage(
                Component.literal("§6Breakpoint hit: §f${payload.breakpointLabel} §7in spec §f${payload.specId} §7at ${payload.simTime.tick}t ${payload.simTime.phase.name}")
            )
        }
    }
}
