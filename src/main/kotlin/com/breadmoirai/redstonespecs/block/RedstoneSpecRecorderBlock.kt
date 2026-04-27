package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.network.OpenRecorderS2CPayload
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.redstone.Orientation
import net.minecraft.world.phys.BlockHitResult

class RedstoneSpecRecorderBlock(properties: Properties) : BaseEntityBlock(properties) {
    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC
    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecBlockEntity(pos, state)
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return
        if (be.spec != null) return
        val player = placer as? ServerPlayer
        val defaultId = if (player != null) {
            player.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
        } else {
            "spec"
        }
        be.setSpec(RedstoneSpec.new(defaultId))
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            val serverPlayer = player as ServerPlayer
            if (be.spec == null) {
                val defaultId = serverPlayer.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
                be.setSpec(RedstoneSpec.new(defaultId))
            }
            ServerPlayNetworking.send(serverPlayer, OpenRecorderS2CPayload(be.blockPos, be.isRecording))
        }
        return InteractionResult.SUCCESS
    }

    override fun neighborChanged(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        block: Block,
        orientation: Orientation?,
        movedByPiston: Boolean,
    ) {
        super.neighborChanged(state, level, pos, block, orientation, movedByPiston)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return
        val powered = level.hasNeighborSignal(pos)
        if (powered && !be.isRecording) {
            be.startRecording()
        } else if (!powered && be.isRecording) {
            if (be.stopRecordingAndFinalize()) {
                be.transformTo(ModRegistries.REDSTONE_SPEC_EDITOR_BLOCK)
            }
        }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecRecorderBlock> = simpleCodec(::RedstoneSpecRecorderBlock)
    }
}
