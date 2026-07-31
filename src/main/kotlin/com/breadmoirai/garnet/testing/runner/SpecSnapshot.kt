package com.breadmoirai.garnet.testing.runner

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState

class SpecSnapshot private constructor(
    private val positions: Map<BlockPos, BlockState>,
) {
    fun restore(level: ServerLevel) {
        positions.forEach { (pos, state) ->
            level.setBlock(pos, state, 3)
        }
    }

    companion object {
        fun capture(level: ServerLevel, origin: BlockPos, bounds: Vec3i): SpecSnapshot {
            val positions = HashMap<BlockPos, BlockState>()
            for (x in 0 until bounds.x) {
                for (y in 0 until bounds.y) {
                    for (z in 0 until bounds.z) {
                        val pos = BlockPos(origin.x + x, origin.y + y, origin.z + z)
                        positions[pos] = level.getBlockState(pos)
                    }
                }
            }
            return SpecSnapshot(positions)
        }
    }
}
