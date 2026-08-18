package com.breadmoirai.garnet.editor.structure.data

import net.minecraft.nbt.CompoundTag

/**
 * True when two `StructureTemplate.save()` tags describe different block content: different size,
 * or a different set of (relative pos, block-state tag, block-entity nbt) cells. Palette ordering
 * and `DataVersion` are ignored (normalized away); entities are not compared. Pure — no level.
 */
fun structuresDiffer(a: CompoundTag, b: CompoundTag): Boolean = normalize(a) != normalize(b)

private data class Cell(val pos: Triple<Int, Int, Int>, val state: CompoundTag, val nbt: CompoundTag?)

private fun normalize(tag: CompoundTag): Pair<Triple<Int, Int, Int>, Set<Cell>> {
    val sizeTag = tag.getListOrEmpty("size")
    val size = Triple(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0))
    val palette = tag.getListOrEmpty("palette")
    val blocks = tag.getListOrEmpty("blocks")
    val cells = HashSet<Cell>()
    for (i in 0 until blocks.size) {
        val bt = blocks.getCompoundOrEmpty(i)
        val posTag = bt.getListOrEmpty("pos")
        val pos = Triple(posTag.getIntOr(0, 0), posTag.getIntOr(1, 0), posTag.getIntOr(2, 0))
        val state = palette.getCompoundOrEmpty(bt.getIntOr("state", 0))
        val nbt = bt.getCompound("nbt").orElse(null)
        cells.add(Cell(pos, state, nbt))
    }
    return size to cells
}
