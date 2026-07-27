package com.breadmoirai.redstonespecs.persistence

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

// Extension methods for NBT access with defaults
private fun ListTag.size(): Int = this.size
private fun CompoundTag.getListOrEmpty(key: String): ListTag = getList(key).orElse(ListTag())
private fun CompoundTag.getIntOr(key: String, default: Int): Int = if (contains(key)) getInt(key) as Int else default
private fun CompoundTag.getStringOr(key: String, default: String): String = if (contains(key)) getString(key) as String else default
private fun ListTag.getCompoundOrEmpty(index: Int): CompoundTag = if (index < this.size) getCompound(index) as CompoundTag else CompoundTag()
private fun ListTag.getIntOr(index: Int, default: Int): Int = if (index < this.size) getInt(index) as Int else default

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
    for (i in 0 until blocks.size()) {
        val bt = blocks.getCompoundOrEmpty(i)
        val posTag = bt.getListOrEmpty("pos")
        val pos = Triple(posTag.getIntOr(0, 0), posTag.getIntOr(1, 0), posTag.getIntOr(2, 0))
        val state = palette.getCompoundOrEmpty(bt.getIntOr("state", 0))
        val nbt = bt.getCompound("nbt").orElse(null)
        cells.add(Cell(pos, state, nbt))
    }
    return size to cells
}
