package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecEditorBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.item.UndoStack
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.runner.SpecRunnerCoordinator
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

private fun saveDir(server: net.minecraft.server.MinecraftServer): java.nio.file.Path =
    server.getWorldPath(LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)

fun registerNetworking() {
    // S2C registrations
    PayloadTypeRegistry.clientboundPlay().register(OpenOverviewS2CPayload.TYPE, OpenOverviewS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenEditorS2CPayload.TYPE, OpenEditorS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(TestResultS2CPayload.TYPE, TestResultS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenFileBrowserS2CPayload.TYPE, OpenFileBrowserS2CPayload.STREAM_CODEC)

    // C2S registrations
    PayloadTypeRegistry.serverboundPlay().register(UndoC2SPayload.TYPE, UndoC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SaveSpecEntryC2SPayload.TYPE, SaveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RemoveSpecEntryC2SPayload.TYPE, RemoveSpecEntryC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResizeBoundsC2SPayload.TYPE, ResizeBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(NudgeSpecBoundsC2SPayload.TYPE, NudgeSpecBoundsC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetSpecIdC2SPayload.TYPE, SetSpecIdC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetLifespanC2SPayload.TYPE, SetLifespanC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetStructureC2SPayload.TYPE, SetStructureC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RequestFileBrowserC2SPayload.TYPE, RequestFileBrowserC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(LoadFromFileC2SPayload.TYPE, LoadFromFileC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(TransformToRunnerC2SPayload.TYPE, TransformToRunnerC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(TransformToRecorderC2SPayload.TYPE, TransformToRecorderC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(TransformToEditorC2SPayload.TYPE, TransformToEditorC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRunnerPickerS2CPayload.TYPE, OpenRunnerPickerS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunnerLoadSpecC2SPayload.TYPE, RunnerLoadSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRecorderS2CPayload.TYPE, OpenRecorderS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenTimelineS2CPayload.TYPE, OpenTimelineS2CPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(StartRecordingC2SPayload.TYPE, StartRecordingC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(StopRecordingC2SPayload.TYPE, StopRecordingC2SPayload.STREAM_CODEC)

    // C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(UndoC2SPayload.TYPE) { _, context ->
        val player = context.player()
        context.server().execute {
            val record = UndoStack.pop(player.uuid) ?: return@execute
            LOGGER.debug("[NetworkRegistry#undo] player={} restoring entry", player.name.string)
            val be = player.level().getBlockEntity(record.originPos) as? SpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(record.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            val level = be.level as? ServerLevel ?: return@execute
            val dir = saveDir(context.server())
            val structureId = spec.structure ?: spec.id
            StructurePersistence.save(dir, structureId, level, be.blockPos, spec.bounds)
            LOGGER.debug("[NetworkRegistry#runSpec] auto-saved structure '{}' before run", structureId)
            SpecRunnerCoordinator.startRun(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            SpecRunnerCoordinator.resetSpec(be)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SaveSpecEntryC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#saveSpecEntry] originPos={} pos={}", payload.originPos, payload.entry.pos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            be.addOrUpdateEntry(payload.entry)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RemoveSpecEntryC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#removeSpecEntry] originPos={} entryPos={}", payload.originPos, payload.entryRelPos)
            val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val removed = be.removeEntry(payload.entryRelPos) ?: return@execute
            UndoStack.push(player.uuid, UndoStack.UndoRecord(payload.originPos, removed))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetSpecIdC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setSpecId] originPos={} id='{}'", payload.originPos, payload.id)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (payload.id.isBlank()) return@execute
            val spec = be.spec ?: return@execute
            val oldId = spec.id
            be.setSpecId(payload.id)
            // Co-update structure if it was auto-named after the old spec id
            if (spec.structure == oldId || spec.structure == null) {
                be.setStructure(payload.id)
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetLifespanC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setLifespan] originPos={} lifespan={}", payload.originPos, payload.lifespan)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (payload.lifespan >= 1) be.setLifespan(payload.lifespan)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            be.setStructure(payload.structure)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResizeBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resizeBounds] originPos={} size=({},{},{})",
                payload.originPos, payload.sizeX, payload.sizeY, payload.sizeZ)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            val size = net.minecraft.core.Vec3i(
                payload.sizeX.coerceAtLeast(1),
                payload.sizeY.coerceAtLeast(1),
                payload.sizeZ.coerceAtLeast(1),
            )
            be.setSpec(spec.copy(bounds = size))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(NudgeSpecBoundsC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#nudgeSpecBounds] originPos={} axis={} isMax={} delta={}",
                payload.originPos, payload.axis, payload.isMax, payload.delta)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            // With size-only bounds, only `isMax = true` (positive face) maps cleanly.
            // Negative-face nudges would shift the origin; we don't support that here.
            if (!payload.isMax) return@execute
            val size = spec.bounds
            val newSize = when (payload.axis) {
                0 -> net.minecraft.core.Vec3i((size.x + payload.delta).coerceAtLeast(1), size.y, size.z)
                1 -> net.minecraft.core.Vec3i(size.x, (size.y + payload.delta).coerceAtLeast(1), size.z)
                2 -> net.minecraft.core.Vec3i(size.x, size.y, (size.z + payload.delta).coerceAtLeast(1))
                else -> size
            }
            be.setSpec(spec.copy(bounds = newSize))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val spec = be.spec ?: return@execute
            val structureId = spec.structure ?: spec.id
            val level = be.level as? ServerLevel ?: return@execute
            val dir = saveDir(context.server())
            if (payload.overwrite) {
                StructurePersistence.clearBounds(level, be.blockPos, spec.bounds)
                StructurePersistence.load(dir, structureId, level, be.blockPos, spec.bounds)
                LOGGER.debug("[NetworkRegistry#overwriteDecision] cleared and placed structure '{}'", structureId)
            } else {
                LOGGER.debug("[NetworkRegistry#overwriteDecision] user skipped structure load")
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RequestFileBrowserC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#requestFileBrowser] originPos={}", payload.originPos)
            val dir = saveDir(context.server())
            val files = SpecPersistence.listSpecsInfo(dir)
            ServerPlayNetworking.send(player, OpenFileBrowserS2CPayload(payload.originPos, files))
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(LoadFromFileC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#loadFromFile] originPos={} specId='{}'", payload.originPos, payload.specId)
            val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val dir = saveDir(context.server())
            val spec = SpecPersistence.load(dir, payload.specId)
            if (spec == null) {
                LOGGER.warn("[NetworkRegistry#loadFromFile] spec '{}' not found on disk", payload.specId)
                return@execute
            }
            be.setSpec(spec)
            LOGGER.debug("[NetworkRegistry#loadFromFile] loaded spec '{}' from disk", payload.specId)

            val structureId = spec.structure ?: spec.id
            val level = be.level as? ServerLevel ?: return@execute
            if (StructurePersistence.hasNonAirBlocks(level, be.blockPos, spec.bounds)) {
                ServerPlayNetworking.send(player, OverwritePromptS2CPayload(payload.originPos, structureId))
            } else {
                StructurePersistence.load(dir, structureId, level, be.blockPos, spec.bounds)
                LOGGER.debug("[NetworkRegistry#loadFromFile] placed structure '{}'", structureId)
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(TransformToRunnerC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#transformToRunner] originPos={}", payload.originPos)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecEditorBlock) return@execute
            be.transformTo(ModRegistries.REDSTONE_SPEC_RUNNER_BLOCK)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(TransformToRecorderC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#transformToRecorder] originPos={}", payload.originPos)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecEditorBlock) return@execute
            be.discardForRerecord()
            be.transformTo(ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(TransformToEditorC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#transformToEditor] originPos={}", payload.originPos)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute
            be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunnerLoadSpecC2SPayload.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runnerLoadSpec] originPos={} specId='{}'", payload.originPos, payload.specId)
            val level = player.level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute
            val dir = saveDir(context.server())
            val loaded = SpecPersistence.load(dir, payload.specId) ?: run {
                LOGGER.warn("[NetworkRegistry#runnerLoadSpec] spec '{}' not found on disk", payload.specId)
                return@execute
            }
            be.setSpec(loaded)
            LOGGER.debug("[NetworkRegistry#runnerLoadSpec] loaded spec '{}' into runner at {}", payload.specId, payload.originPos)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(StartRecordingC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#startRecording] originPos={}", payload.originPos)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
            be.startRecording()
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(StopRecordingC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#stopRecording] originPos={}", payload.originPos)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
            if (be.stopRecordingAndFinalize()) {
                be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
            }
        }
    }
}
