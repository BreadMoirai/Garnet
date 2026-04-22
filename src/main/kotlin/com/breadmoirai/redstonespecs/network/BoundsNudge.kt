package com.breadmoirai.redstonespecs.network

import net.minecraft.world.level.levelgen.structure.BoundingBox

fun nudgeBounds(b: BoundingBox, axis: Int, isMax: Boolean, delta: Int): BoundingBox = when (axis) {
    0 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), (b.maxX() + delta).coerceAtLeast(b.minX()), b.maxY(), b.maxZ())
         else
            BoundingBox((b.minX() + delta).coerceAtMost(b.maxX()), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
    1 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), (b.maxY() + delta).coerceAtLeast(b.minY()), b.maxZ())
         else
            BoundingBox(b.minX(), (b.minY() + delta).coerceAtMost(b.maxY()), b.minZ(), b.maxX(), b.maxY(), b.maxZ())
    2 -> if (isMax)
            BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), (b.maxZ() + delta).coerceAtLeast(b.minZ()))
         else
            BoundingBox(b.minX(), b.minY(), (b.minZ() + delta).coerceAtMost(b.maxZ()), b.maxX(), b.maxY(), b.maxZ())
    else -> b
}
