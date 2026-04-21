package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.BoundingBox

class SpecSnapshot private constructor(
    private val positions: Map<BlockPos, BlockState>,
) {
    fun restore(level: ServerLevel) {
        positions.forEach { (pos, state) ->
            level.setBlock(pos, state, 3)
        }
    }

    companion object {
        fun capture(level: ServerLevel, origin: BlockPos, bounds: BoundingBox): SpecSnapshot {
            val positions = HashMap<BlockPos, BlockState>()
            for (x in bounds.minX()..bounds.maxX()) {
                for (y in bounds.minY()..bounds.maxY()) {
                    for (z in bounds.minZ()..bounds.maxZ()) {
                        val pos = BlockPos(origin.x + x, origin.y + y, origin.z + z)
                        positions[pos] = level.getBlockState(pos)
                    }
                }
            }
            return SpecSnapshot(positions)
        }
    }
}
