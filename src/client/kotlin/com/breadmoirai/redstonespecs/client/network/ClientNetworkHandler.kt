package com.breadmoirai.redstonespecs.client.network

import com.breadmoirai.redstonespecs.client.screen.SpecOverviewScreen
import com.breadmoirai.redstonespecs.client.screen.SpecEditorScreen
import com.breadmoirai.redstonespecs.network.*
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.screens.ConfirmScreen
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
            val r = payload.result
            val color = if (r.pass) "§a" else "§c"
            LOGGER.debug("[ClientNetworkHandler#testResult] originPos={} {}/{} passed", payload.originPos, r.passCount, r.checks.size)
            mc.player?.sendSystemMessage(
                Component.literal("${color}Spec '${r.specId}': ${r.passCount}/${r.checks.size} checks passed")
            )
            val current = mc.screen
            if (current is SpecOverviewScreen && current.originPos == payload.originPos) {
                mc.setScreen(SpecOverviewScreen(payload.originPos, current.availableStructures))
            }
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(BreakpointHitS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#breakpointHit] '{}' in '{}' at {}t {}",
                payload.breakpointLabel, payload.specId, payload.simTime.tick, payload.simTime.phase.name)
            mc.player?.sendSystemMessage(
                Component.literal("§6Breakpoint: §f${payload.breakpointLabel} §7in §f${payload.specId} §7at ${payload.simTime.tick}t ${payload.simTime.phase.name}")
            )
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(StructurePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#structurePrompt] kind={} id={}", payload.promptKind, payload.currentStructureId)
            val title = when (payload.promptKind) {
                "SAVE_OR_FORK" -> "Structure '${payload.currentStructureId}' has changed"
                else -> "Structure file '${payload.currentStructureId}' already exists"
            }
            val message = if (payload.promptKind == "SAVE_OR_FORK")
                "Overwrite '${payload.currentStructureId}'?"
            else
                "Overwrite existing file?"
            mc.setScreen(ConfirmScreen(
                BooleanConsumer { save ->
                    mc.setScreen(null)
                    val decision = if (save) "SAVE" else "CANCEL"
                    ClientPlayNetworking.send(StructureDecisionC2SPayload(
                        payload.originPos, decision, payload.currentStructureId
                    ))
                },
                Component.literal(title),
                Component.literal(message),
                Component.literal("Overwrite"),
                Component.literal("Cancel"),
            ))
        }
    }

    ClientPlayNetworking.registerGlobalReceiver(OverwritePromptS2CPayload.TYPE) { payload, context ->
        val mc = context.client()
        mc.execute {
            LOGGER.debug("[ClientNetworkHandler#overwritePrompt] specId={}", payload.specId)
            mc.setScreen(ConfirmScreen(
                BooleanConsumer { overwrite ->
                    mc.setScreen(null)
                    ClientPlayNetworking.send(OverwriteDecisionC2SPayload(payload.originPos, overwrite))
                },
                Component.literal("Blocks found inside bounds"),
                Component.literal("Overwrite existing blocks with structure '${payload.specId}'?"),
                Component.literal("Overwrite"),
                Component.literal("Skip Structure"),
            ))
        }
    }
}
