package com.breadmoirai.garnet.block

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.network.OpenRunnerScreenS2C
import com.breadmoirai.garnet.network.RunnerMetaSnapshot
import com.breadmoirai.garnet.persistence.SpecDirectoryScan
import com.breadmoirai.garnet.persistence.SpecPersistence
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.BlockHitResult

class GarnetRunnerBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            val serverPlayer = player as ServerPlayer

            val saveDir = (level as ServerLevel).server.getWorldPath(LevelResource.ROOT)
                .resolve(SharedSettings.specSaveDir)
            val specList = SpecDirectoryScan.list(saveDir)

            // Build meta from the BE's configured spec id (loads from disk if available).
            val dslSpec = if (be.isConfigured) SpecPersistence.load(saveDir, be.specId) else null
            val meta: RunnerMetaSnapshot? = if (dslSpec != null) {
                RunnerMetaSnapshot(
                    id = dslSpec.id,
                    boundsX = dslSpec.bounds.x,
                    boundsY = dslSpec.bounds.y,
                    boundsZ = dslSpec.bounds.z,
                    lifespan = dslSpec.lifespan,
                    structure = dslSpec.structure,
                )
            } else null

            // Use the first available spec as the default selection, or empty string.
            val currentSpecPath = specList.firstOrNull() ?: ""
            ServerPlayNetworking.send(
                serverPlayer,
                OpenRunnerScreenS2C(be.blockPos, currentSpecPath, specList, meta)
            )
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<GarnetRunnerBlock> = simpleCodec(::GarnetRunnerBlock)
    }
}
