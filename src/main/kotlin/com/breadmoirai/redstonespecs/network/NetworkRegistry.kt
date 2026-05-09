package com.breadmoirai.redstonespecs.network

import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.persistence.SpecDirectoryScan
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.runner.SpecSnapshot
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
    PayloadTypeRegistry.clientboundPlay().register(OverwritePromptS2CPayload.TYPE, OverwritePromptS2CPayload.STREAM_CODEC)

    // C2S registrations
    PayloadTypeRegistry.serverboundPlay().register(RunSpecC2SPayload.TYPE, RunSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(ResetSpecC2SPayload.TYPE, ResetSpecC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetStructureC2SPayload.TYPE, SetStructureC2SPayload.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(OverwriteDecisionC2SPayload.TYPE, OverwriteDecisionC2SPayload.STREAM_CODEC)

    // v2.0: Slim recorder / runner packets
    PayloadTypeRegistry.serverboundPlay().register(SetRecorderConfigC2S.TYPE, SetRecorderConfigC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RecorderCommandC2S.TYPE, RecorderCommandC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(SetRunnerConfigC2S.TYPE, SetRunnerConfigC2S.STREAM_CODEC)
    PayloadTypeRegistry.serverboundPlay().register(RunnerCommandC2S.TYPE, RunnerCommandC2S.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRecorderScreenS2C.TYPE, OpenRecorderScreenS2C.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(OpenRunnerScreenS2C.TYPE, OpenRunnerScreenS2C.STREAM_CODEC)
    PayloadTypeRegistry.clientboundPlay().register(RunnerStatusS2C.TYPE, RunnerStatusS2C.STREAM_CODEC)

    // C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val level = be.level as? ServerLevel ?: return@execute
            val dir = saveDir(context.server())
            val structureId = be.specStructure ?: be.specId
            StructurePersistence.save(dir, structureId, level, be.blockPos, be.specBounds)
            LOGGER.debug("[NetworkRegistry#runSpec] auto-saved structure '{}' before run", structureId)
            val dslSpec = SpecPersistence.load(dir, be.specId)
            if (dslSpec == null) {
                LOGGER.warn("[NetworkRegistry#runSpec] could not load dsl.RedstoneSpec for '{}' — aborting run", be.specId)
                return@execute
            }
            be.startRun(dslSpec, level)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
            // No-op: engine-driven path has no coordinator to reset.
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            be.setStructure(payload.structure)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, context ->
        context.server().execute {
            val be = context.player().level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            val structureId = be.specStructure ?: be.specId
            val level = be.level as? ServerLevel ?: return@execute
            val dir = saveDir(context.server())
            if (payload.overwrite) {
                StructurePersistence.clearBounds(level, be.blockPos, be.specBounds)
                StructurePersistence.load(dir, structureId, level, be.blockPos, be.specBounds)
                LOGGER.debug("[NetworkRegistry#overwriteDecision] cleared and placed structure '{}'", structureId)
            } else {
                LOGGER.debug("[NetworkRegistry#overwriteDecision] user skipped structure load")
            }
        }
    }

    // v2.0: Slim recorder / runner C2S handlers
    ServerPlayNetworking.registerGlobalReceiver(SetRecorderConfigC2S.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setRecorderConfig] originPos={} specId={}", payload.originPos, payload.specId)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
            if (payload.specId.isNotBlank()) be.setSpecId(payload.specId)
            if (payload.structureId.isNotBlank()) be.setStructure(payload.structureId)
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RecorderCommandC2S.TYPE) { payload, context ->
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#recorderCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
            val level = context.player().level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRecorderBlock) return@execute
            when (payload.cmd) {
                RecorderCmd.START -> be.startRecording()
                RecorderCmd.STOP -> be.stopRecordingAndFinalize()
                RecorderCmd.DISCARD -> be.discardForRerecord()
            }
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(SetRunnerConfigC2S.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#setRunnerConfig] originPos={} specPath={}", payload.originPos, payload.specPath)
            val level = player.level()
            val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (level.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute
            val dir = saveDir(context.server())
            val specId = payload.specPath.removeSuffix(".spec.kts")
            val dslSpec = SpecPersistence.load(dir, specId)
            val meta: RunnerMetaSnapshot? = if (dslSpec != null) {
                RunnerMetaSnapshot(
                    id = dslSpec.id,
                    boundsX = dslSpec.bounds.x,
                    boundsY = dslSpec.bounds.y,
                    boundsZ = dslSpec.bounds.z,
                    lifespan = dslSpec.lifespan,
                    structure = dslSpec.structure,
                )
            } else {
                LOGGER.warn("[NetworkRegistry#setRunnerConfig] spec '{}' not found on disk", specId)
                null
            }
            val specList = SpecDirectoryScan.list(dir)
            ServerPlayNetworking.send(
                player,
                OpenRunnerScreenS2C(payload.originPos, payload.specPath, specList, meta)
            )
        }
    }

    ServerPlayNetworking.registerGlobalReceiver(RunnerCommandC2S.TYPE) { payload, context ->
        val player = context.player()
        context.server().execute {
            LOGGER.debug("[NetworkRegistry#runnerCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
            val serverLevel = player.level() as ServerLevel
            val be = serverLevel.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return@execute
            if (serverLevel.getBlockState(payload.originPos).block !is RedstoneSpecRunnerBlock) return@execute
            val dir = saveDir(context.server())

            when (payload.cmd) {
                RunnerCmd.PLACE_STRUCTURE -> {
                    if (be.isConfigured) {
                        val structureId = be.specStructure ?: be.specId
                        StructurePersistence.load(dir, structureId, serverLevel, payload.originPos, be.specBounds)
                        LOGGER.debug("[NetworkRegistry#runnerCommand] PLACE_STRUCTURE placed '{}'", structureId)
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "Structure placed: $structureId")
                        )
                    } else {
                        LOGGER.warn("[NetworkRegistry#runnerCommand] PLACE_STRUCTURE: BE not configured at {}", payload.originPos)
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "No spec configured")
                        )
                    }
                }
                RunnerCmd.RUN -> {
                    val dslSpec = SpecPersistence.load(dir, be.specId)
                    if (dslSpec == null) {
                        LOGGER.warn("[NetworkRegistry#runnerCommand] RUN: dsl spec '{}' not found", be.specId)
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.FAIL, "Spec file not found: ${be.specId}")
                        )
                        return@execute
                    }
                    ServerPlayNetworking.send(
                        player,
                        RunnerStatusS2C(payload.originPos, RunnerState.RUNNING, "Running…")
                    )
                    val launched = be.startRun(dslSpec, serverLevel)
                    if (!launched) {
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.RUNNING, "Already running")
                        )
                    }
                }
                RunnerCmd.RESTORE -> {
                    if (be.isConfigured) {
                        val snapshot = SpecSnapshot.capture(serverLevel, payload.originPos, be.specBounds)
                        snapshot.restore(serverLevel)
                        LOGGER.debug("[NetworkRegistry#runnerCommand] RESTORE applied snapshot at {}", payload.originPos)
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "Snapshot restored")
                        )
                    } else {
                        LOGGER.warn("[NetworkRegistry#runnerCommand] RESTORE: BE not configured at {}", payload.originPos)
                        ServerPlayNetworking.send(
                            player,
                            RunnerStatusS2C(payload.originPos, RunnerState.IDLE, "No spec configured")
                        )
                    }
                }
            }
        }
    }

    com.breadmoirai.redstonespecs.network.managed.ManagedNetworkRegistry.register()
}
