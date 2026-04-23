package com.breadmoirai.redstonespecs.block

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.network.OpenOverviewS2CPayload
import com.mojang.serialization.MapCodec
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
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
import net.minecraft.world.level.levelgen.structure.BoundingBox
import net.minecraft.world.phys.BlockHitResult
import java.util.UUID

class RedstoneSpecBlock(properties: Properties) : BaseEntityBlock(properties) {

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity =
        RedstoneSpecBlockEntity(pos, state)

    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    override fun useWithoutItem(
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hit: BlockHitResult,
    ): InteractionResult {
        if (!level.isClientSide) {
            val be = level.getBlockEntity(pos) as? RedstoneSpecBlockEntity ?: return InteractionResult.PASS
            if (be.spec == null) {
                be.setSpec(
                    RedstoneSpec(
                        id = UUID.randomUUID(),
                        name = "New Spec",
                        bounds = BoundingBox(1, 0, 1, 5, 4, 5),
                        oneShot = false,
                        specCases = listOf(SpecCase("Case 1", 20, emptyList(), emptyList(), emptyList(), emptyList())),
                    )
                )
            }
            ServerPlayNetworking.send(player as ServerPlayer, OpenOverviewS2CPayload(pos))
        }
        return InteractionResult.SUCCESS
    }

    override fun <T : BlockEntity> getTicker(
        level: Level,
        state: BlockState,
        type: BlockEntityType<T>,
    ): BlockEntityTicker<T>? = null

    companion object {
        val CODEC: MapCodec<RedstoneSpecBlock> = simpleCodec(::RedstoneSpecBlock)
    }
}
