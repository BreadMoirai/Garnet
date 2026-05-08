package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.block.SpecBlockKind
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.breadmoirai.redstonespecs.network.OpenRunnerPickerS2CPayload
import com.breadmoirai.redstonespecs.network.OpenTimelineS2CPayload
import com.breadmoirai.redstonespecs.persistence.SpecPersistence
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

class RedstoneSpecRunnerBlock(properties: Properties) : BaseEntityBlock(properties) {
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

            // Shift-right-click: open the timeline scrubber for the most recent run.
            if (player.isShiftKeyDown) {
                ServerPlayNetworking.send(serverPlayer, OpenTimelineS2CPayload(pos))
                return InteractionResult.SUCCESS
            }

            if (be.spec == null) {
                val saveDir = (level as ServerLevel).server.getWorldPath(LevelResource.ROOT)
                    .resolve(SharedSettings.specSaveDir)
                val files = SpecPersistence.listSpecsInfo(saveDir)
                ServerPlayNetworking.send(serverPlayer, OpenRunnerPickerS2CPayload(be.blockPos, files))
            } else {
                ServerPlayNetworking.send(serverPlayer, OpenOverviewS2CPayload(be.blockPos, SpecBlockKind.RUNNER))
            }
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecRunnerBlock> = simpleCodec(::RedstoneSpecRunnerBlock)
    }
}
