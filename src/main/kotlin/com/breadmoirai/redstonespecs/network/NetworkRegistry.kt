package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking

fun registerNetworking() {
    PayloadTypeRegistry.clientboundPlay().register(OpenEditorS2CPayload.TYPE, OpenEditorS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(UndoC2SPayload.TYPE, UndoC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)

    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            val level = player.level()
            val be = level.getBlockEntity(record.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.specCaseIndex, record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            SpecRunnerCoordinator.startRun(be, payload.runAll)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }
}
