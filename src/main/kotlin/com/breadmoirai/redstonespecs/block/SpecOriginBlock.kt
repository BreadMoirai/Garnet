package com.breadmoirai.redstonespecs.block

import com.mojang.serialization.MapCodec
import net.minecraft.core.BlockPos
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

class SpecOriginBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        SpecOriginBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (level.isClientSide) {
            val be = level.getBlockEntity(pos) as? SpecOriginBlockEntity ?: return InteractionResult.PASS
            openOverviewScreen(be)
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<SpecOriginBlock> = simpleCodec(::SpecOriginBlock)

        fun openOverviewScreen(be: SpecOriginBlockEntity) {
            // TODO Phase 5: open SpecOverviewScreen(be)
        }
    }
}
