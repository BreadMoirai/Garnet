package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.network.nudgeBounds
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
    PayloadTypeRegistry.clientboundPlay().register(AutoSpecRecordedS2CPayload.TYPE, AutoSpecRecordedS2CPayload.STREAM_CODEC)

    // C2S registrations
    PayloadTypeRegistry.serverboundPlay().register(UndoC2SPayload.TYPE, UndoC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResumeSpecC2SPayload.TYPE, ResumeSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(CycleSpecCaseC2SPayload.TYPE, CycleSpecCaseC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecEntryC2SPayload.TYPE, SaveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RemoveSpecEntryC2SPayload.TYPE, RemoveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(AddSpecCaseC2SPayload.TYPE, AddSpecCaseC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RemoveSpecCaseC2SPayload.TYPE, RemoveSpecCaseC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SelectSpecCaseC2SPayload.TYPE, SelectSpecCaseC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RenameSpecC2SPayload.TYPE, RenameSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RenameSpecCaseC2SPayload.TYPE, RenameSpecCaseC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResizeBoundsC2SPayload.TYPE, ResizeBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(NudgeSpecBoundsC2SPayload.TYPE, NudgeSpecBoundsC2SPayload.STREAM_CODEC)

    // C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            LOGGER.debug("[NetworkRegistry#undo] player={} restoring entry at case={}", player.name.string, record.specCaseIndex)
            val be = player.level().getBlockEntity(record.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.specCaseIndex, record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runSpec] originPos={} runAll={}", payload.originPos, payload.runAll)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            SpecRunnerCoordinator.startRun(be, payload.runAll)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResumeSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resumeSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            SpecRunnerCoordinator.resumeSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(CycleSpecCaseC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            if (spec.specCases.isEmpty()) return@execute
            val size = spec.specCases.size
            val newIndex = if (payload.forward) {
                (be.activeSpecCaseIndex + 1) % size
            } else {
                (be.activeSpecCaseIndex - 1 + size) % size
            }
            LOGGER.debug("[NetworkRegistry#cycleSpecCase] originPos={} forward={} newIndex={}", payload.originPos, payload.forward, newIndex)
            be.setActiveSpecCase(newIndex)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SaveSpecEntryC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#saveSpecEntry] originPos={} case={} pos={}", payload.originPos, payload.specCaseIndex, payload.entry.pos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.addOrUpdateEntry(payload.specCaseIndex, payload.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecEntryC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#removeSpecEntry] originPos={} case={} entryPos={}", payload.originPos, payload.specCaseIndex, payload.entryRelPos)
            val be = player.level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val removed = be.removeEntry(payload.specCaseIndex, payload.entryRelPos) ?: return@execute
            UndoStack.push(player.uuid, UndoStack.UndoRecord(payload.originPos, payload.specCaseIndex, removed))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(AddSpecCaseC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#addSpecCase] originPos={} name='{}'", payload.originPos, payload.name)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.addSpecCase(payload.name)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecCaseC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#removeSpecCase] originPos={} index={}", payload.originPos, payload.index)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.removeSpecCase(payload.index)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SelectSpecCaseC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#selectSpecCase] originPos={} index={}", payload.originPos, payload.index)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            if (payload.index in (be.spec?.specCases?.indices ?: return@execute)) {
                be.setActiveSpecCase(payload.index)
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RenameSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#renameSpec] originPos={} newName='{}'", payload.originPos, payload.newName)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.setSpecName(payload.newName)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RenameSpecCaseC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#renameSpecCase] originPos={} index={} newName='{}'", payload.originPos, payload.index, payload.newName)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            be.renameSpecCase(payload.index, payload.newName)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resizeBounds] originPos={} bounds={}", payload.originPos, payload.bounds)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = payload.bounds))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#nudgeSpecBounds] originPos={} axis={} isMax={} delta={}", payload.originPos, payload.axis, payload.isMax, payload.delta)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecOriginBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            be.setSpec(spec.copy(bounds = nudgeBounds(spec.bounds, payload.axis, payload.isMax, payload.delta)))
        }
    }
}
