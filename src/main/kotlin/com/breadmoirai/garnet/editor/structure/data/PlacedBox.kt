package com.breadmoirai.garnet.editor.structure.data

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/** Absolute origin plus size of a structure placed into the world. */
data class PlacedBox(val origin: BlockPos, val size: Vec3i)
