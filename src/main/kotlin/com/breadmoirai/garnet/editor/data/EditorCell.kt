package com.breadmoirai.garnet.editor.data

import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

data class EditorCell(
    val specId: String,
    val origin: BlockPos,
    val cellSize: Vec3i,
    val sourceFile: String,  // filename relative to leaf folder, e.g. "piston.spec.kts"
)
