package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.network.AutoSpecRecordedS2CPayload
import com.breadmoirai.redstonespecs.network.BreakpointHitS2CPayload
import com.breadmoirai.redstonespecs.network.OpenEditorS2CPayload
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.breadmoirai.redstonespecs.network.TestResultS2CPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

fun registerClientNetworking() {
    ClientPlayNetworking.registerGlobalReceiver(OpenOverviewS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            mc.setScreen(SpecOverviewScreen(payload.originPos))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OpenEditorS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            mc.setScreen(SpecEditorScreen(payload.originPos, payload.entryRelPos))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(TestResultS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            val passCount = payload.result.results.count { r -> r.checks.all { it.pass } }
            val total = payload.result.results.size
            val color = if (passCount == total) "§a" else "§c"
            mc.player?.sendSystemMessage(
                Component.literal("${color}Spec run complete: $passCount/$total cases passed")
            )
            // If the overview screen is open for this origin, reopen to refresh
            val current = mc.screen
            if (current is SpecOverviewScreen && current.originPos == payload.originPos) {
                mc.setScreen(SpecOverviewScreen(payload.originPos))
            }
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(BreakpointHitS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            mc.player?.sendSystemMessage(
                Component.literal("§6Breakpoint hit: §f${payload.breakpointLabel} §7in §f${payload.caseName} §7at ${payload.simTime.tick}t ${payload.simTime.phase.name}")
            )
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(AutoSpecRecordedS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            mc.player?.sendSystemMessage(
                Component.literal("§bAutoSpec recorded: §f${payload.specCaseName}")
            )
        }
    }
}
