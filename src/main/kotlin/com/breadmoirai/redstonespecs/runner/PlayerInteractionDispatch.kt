package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.ButtonBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/**
 * Apply [target] at [pos] in [level] as if a player interacted — buttons go
 * through `ButtonBlock.press` so scheduled-tick paths fire correctly; other
 * blocks fall through to a plain `setBlock`. Lifted from the old
 * `SpecRunner` so the DSL can call it without depending on that class.
 */
fun tryApplyAsPlayerInteraction(
    level: ServerLevel,
    pos: BlockPos,
    current: BlockState,
    target: BlockState,
) {
    val block = current.block
    if (block is ButtonBlock) {
        val targetPowered = if (target.hasProperty(BlockStateProperties.POWERED))
            target.getValue(BlockStateProperties.POWERED) else return
        val currentPowered = current.getValue(BlockStateProperties.POWERED)
        if (targetPowered && !currentPowered) {
            LOGGER.debug("[tryApplyAsPlayerInteraction] press button at {}", pos)
            block.press(current, level, pos, null)
            return
        }
    }
    if (target != current) level.setBlock(pos, target, 3)
}
