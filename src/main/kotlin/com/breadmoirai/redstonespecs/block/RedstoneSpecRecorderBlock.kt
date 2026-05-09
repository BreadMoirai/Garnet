package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.network.OpenRecorderScreenS2C
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

    override fun onPlace(state: BlockState, level: Level, pos: BlockPos, oldState: BlockState, isMoving: Boolean) {
        super.onPlace(state, level, pos, oldState, isMoving)
        if (level.isClientSide) return
        if (oldState.`is`(this)) return
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return
        if (be.spec != null) return
        be.setSpec(RedstoneSpec.new("spec"))
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide) return
        val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return
        val player = placer as? ServerPlayer ?: return
        val playerId = player.gameProfile.name.lowercase().replace(" ", "_") + "_spec"
        val s = be.spec
        if (s == null) be.setSpec(RedstoneSpec.new(playerId))
        else if (s.id == "spec") be.setSpecId(playerId)
    }

    override fun useWithoutItem(
        state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecBlockEntity ?: return InteractionResult.PASS
            val serverPlayer = player as ServerPlayer
            val spec = be.spec
            val specId = spec?.id ?: ""
            val outPath = spec?.id ?: ""
            val structureId = spec?.structure ?: spec?.id ?: ""
            val recState = if (be.isRecording) "recording" else "idle"
            ServerPlayNetworking.send(serverPlayer, OpenRecorderScreenS2C(be.blockPos, specId, outPath, structureId, recState))
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
            be.stopRecordingAndFinalize()
        }
    }

    override fun <T : BlockEntity> getTicker(
        level: Level, state: BlockState, type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecRecorderBlock> = simpleCodec(::RedstoneSpecRecorderBlock)
    }
}
