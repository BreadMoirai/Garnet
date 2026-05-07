package com.breadmoirai.redstonespecs.data

import net.minecraft.core.BlockPos

data class SpecEntry(
    val pos: BlockPos,
    val label: String,
    val color: Int,
    val kind: EntryKind,
    val time: SimTime,
    val condition: StateCondition,
)
