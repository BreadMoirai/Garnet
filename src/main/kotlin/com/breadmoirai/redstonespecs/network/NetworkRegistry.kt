package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

fun registerNetworking() {
    // S2C registrations
    PayloadTypeRegistry.clientboundPlay().register(OpenOverviewS2CPayload.TYPE, OpenOverviewS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenEditorS2CPayload.TYPE, OpenEditorS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(TestResultS2CPayload.TYPE, TestResultS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(BreakpointHitS2CPayload.TYPE, BreakpointHitS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(StructurePromptS2CPayload.TYPE, StructurePromptS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)

    // C2S registrations
    PayloadTypeRegistry.serverboundPlay().register(UndoC2SPayload.TYPE, UndoC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResumeSpecC2SPayload.TYPE, ResumeSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecEntryC2SPayload.TYPE, SaveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RemoveSpecEntryC2SPayload.TYPE, RemoveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResizeBoundsC2SPayload.TYPE, ResizeBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(NudgeSpecBoundsC2SPayload.TYPE, NudgeSpecBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetSpecIdC2SPayload.TYPE, SetSpecIdC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetSpecModeC2SPayload.TYPE, SetSpecModeC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetLifespanC2SPayload.TYPE, SetLifespanC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetStructureC2SPayload.TYPE, SetStructureC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecC2SPayload.TYPE, SaveSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(LoadSpecC2SPayload.TYPE, LoadSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(StructureDecisionC2SPayload.TYPE, StructureDecisionC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)

    // C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            LOGGER.debug("[NetworkRegistry#undo] player={} restoring entry", player.name.string)
            val be = player.level().getBlockEntity(record.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.startRun(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResumeSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resumeSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resumeSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SaveSpecEntryC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#saveSpecEntry] originPos={} pos={}", payload.originPos, payload.entry.pos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(payload.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecEntryC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#removeSpecEntry] originPos={} entryPos={}", payload.originPos, payload.entryRelPos)
            val be = player.level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val removed = be.removeEntry(payload.entryRelPos) ?: return@execute
            UndoStack.push(player.uuid, UndoStack.UndoRecord(payload.originPos, removed))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecIdC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setSpecId] originPos={} id='{}'", payload.originPos, payload.id)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.id.isNotBlank()) be.setSpecId(payload.id)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecModeC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setSpecMode] originPos={} mode={}", payload.originPos, payload.mode)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setMode(payload.mode)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetLifespanC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setLifespan] originPos={} lifespan={}", payload.originPos, payload.lifespan)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            if (payload.lifespan >= 1) be.setLifespan(payload.lifespan)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            be.setStructure(payload.structure)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resizeBounds] originPos={} bounds={}", payload.originPos, payload.bounds)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = payload.bounds))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#nudgeSpecBounds] originPos={} axis={} isMax={} delta={}", payload.originPos, payload.axis, payload.isMax, payload.delta)
            val be = context.player().level().getBlockEntity(payload.originPos) as? RedstoneSpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = nudgeBounds(spec.bounds, payload.axis, payload.isMax, payload.delta)))
        }
    }

    // SaveSpec and LoadSpec handlers are added in Task 7 after persistence layer exists.
    // StructureDecision and OverwriteDecision handlers are also added in Task 7.
}
