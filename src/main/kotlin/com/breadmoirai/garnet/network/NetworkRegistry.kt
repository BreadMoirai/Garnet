package com.breadmoirai.garnet.network

import com.breadmoirai.garnet.block.GarnetRecorderBlock
import com.breadmoirai.garnet.block.GarnetRunnerBlock
import com.breadmoirai.garnet.block.SpecBlockEntity
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.persistence.SpecDirectoryScan
import com.breadmoirai.garnet.persistence.SpecPersistence
import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.runner.SpecSnapshot
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

fun saveDir(server: MinecraftServer): java.nio.file.Path =
    server.getWorldPath(LevelResource.ROOT)
        .resolve(SharedSettings.specSaveDir)

fun handleRunSpec(server: MinecraftServer, player: ServerPlayer, payload: RunSpecC2SPayload) {
    LOGGER.debug("[NetworkRegistry#runSpec] originPos={}", payload.originPos)
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    val level = be.level as? ServerLevel ?: return
    val dir = saveDir(server)
    val structureId = be.specStructure ?: be.specId
    StructurePersistence.save(dir, structureId, level, be.blockPos, be.specBounds)
    LOGGER.debug("[NetworkRegistry#runSpec] auto-saved structure '{}' before run", structureId)
    val dslSpec = SpecPersistence.load(dir, be.specId)
    if (dslSpec == null) {
        LOGGER.warn("[NetworkRegistry#runSpec] could not load dsl.GarnetSpec for '{}' — aborting run", be.specId)
        return
    }
    be.startRun(dslSpec, level)
}

fun handleResetSpec(server: MinecraftServer, player: ServerPlayer, payload: ResetSpecC2SPayload) {
    LOGGER.debug("[NetworkRegistry#resetSpec] originPos={}", payload.originPos)
    // No-op: engine-driven path has no coordinator to reset.
}

fun handleSetStructure(server: MinecraftServer, player: ServerPlayer, payload: SetStructureC2SPayload) {
    LOGGER.debug("[NetworkRegistry#setStructure] originPos={} structure={}", payload.originPos, payload.structure)
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    be.setStructure(payload.structure)
}

fun handleOverwriteDecision(server: MinecraftServer, player: ServerPlayer, payload: OverwriteDecisionC2SPayload) {
    val be = player.level().getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    val structureId = be.specStructure ?: be.specId
    val level = be.level as? ServerLevel ?: return
    val dir = saveDir(server)
    if (payload.overwrite) {
        StructurePersistence.clearBounds(level, be.blockPos, be.specBounds)
        StructurePersistence.load(dir, structureId, level, be.blockPos, be.specBounds)
        LOGGER.debug("[NetworkRegistry#overwriteDecision] cleared and placed structure '{}'", structureId)
    } else {
        LOGGER.debug("[NetworkRegistry#overwriteDecision] user skipped structure load")
    }
}

fun handleSetRecorderConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRecorderConfigC2S) {
    LOGGER.debug("[NetworkRegistry#setRecorderConfig] originPos={} specId={}", payload.originPos, payload.specId)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRecorderBlock) return
    if (payload.specId.isNotBlank()) be.setSpecId(payload.specId)
    if (payload.structureId.isNotBlank()) be.setStructure(payload.structureId)
}

fun handleRecorderCommand(server: MinecraftServer, player: ServerPlayer, payload: RecorderCommandC2S) {
    LOGGER.debug("[NetworkRegistry#recorderCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRecorderBlock) return
    when (payload.cmd) {
        RecorderCmd.START -> be.startRecording()
        RecorderCmd.STOP -> be.stopRecordingAndFinalize()
        RecorderCmd.DISCARD -> be.discardForRerecord()
    }
}

fun handleSetRunnerConfig(server: MinecraftServer, player: ServerPlayer, payload: SetRunnerConfigC2S) {
    LOGGER.debug("[NetworkRegistry#setRunnerConfig] originPos={} specPath={}", payload.originPos, payload.specPath)
    val level = player.level()
    val be = level.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (level.getBlockState(payload.originPos).block !is GarnetRunnerBlock) return
    val dir = saveDir(server)
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

fun handleRunnerCommand(server: MinecraftServer, player: ServerPlayer, payload: RunnerCommandC2S) {
    LOGGER.debug("[NetworkRegistry#runnerCommand] originPos={} cmd={}", payload.originPos, payload.cmd)
    val serverLevel = player.level() as ServerLevel
    val be = serverLevel.getBlockEntity(payload.originPos) as? SpecBlockEntity ?: return
    if (serverLevel.getBlockState(payload.originPos).block !is GarnetRunnerBlock) return
    val dir = saveDir(server)

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
                return
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

    // C2S handlers — bodies live in handleX top-level functions for testability
    ServerPlayNetworking.registerGlobalReceiver(RunSpecC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleRunSpec(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(ResetSpecC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleResetSpec(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetStructureC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetStructure(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(OverwriteDecisionC2SPayload.TYPE) { payload, ctx ->
        ctx.server().execute { handleOverwriteDecision(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetRecorderConfigC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetRecorderConfig(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(RecorderCommandC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleRecorderCommand(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(SetRunnerConfigC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleSetRunnerConfig(ctx.server(), ctx.player(), payload) }
    }
    ServerPlayNetworking.registerGlobalReceiver(RunnerCommandC2S.TYPE) { payload, ctx ->
        ctx.server().execute { handleRunnerCommand(ctx.server(), ctx.player(), payload) }
    }

    com.breadmoirai.garnet.network.project.ProjectNetworkRegistry.register()
}
