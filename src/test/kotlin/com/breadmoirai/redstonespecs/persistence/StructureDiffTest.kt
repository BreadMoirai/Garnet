package com.breadmoirai.redstonespecs.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag

/** Builds a StructureTemplate-shaped tag from (x,y,z,paletteIndex) blocks + a palette of state names. */
private fun structureTag(
    size: Triple<Int, Int, Int>,
    palette: List<String>,
    blocks: List<IntArray>, // each = [x, y, z, stateIndex]
): CompoundTag {
    val tag = CompoundTag()
    val sizeTag = ListTag()
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.first))
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.second))
    sizeTag.add(net.minecraft.nbt.IntTag.valueOf(size.third))
    tag.put("size", sizeTag)
    val paletteTag = ListTag()
    palette.forEach { name -> paletteTag.add(CompoundTag().apply { putString("Name", name) }) }
    tag.put("palette", paletteTag)
    val blocksTag = ListTag()
    blocks.forEach { b ->
        val bt = CompoundTag()
        val pos = ListTag()
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[0]))
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[1]))
        pos.add(net.minecraft.nbt.IntTag.valueOf(b[2]))
        bt.put("pos", pos)
        bt.putInt("state", b[3])
        blocksTag.add(bt)
    }
    tag.put("blocks", blocksTag)
    return tag
}

class StructureDiffTest : FunSpec({
    test("identical structures are not different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe false
    }
    test("palette reordering with remapped indices is not different") {
        val a = structureTag(Triple(1, 1, 2), listOf("minecraft:stone", "minecraft:gold_block"),
            listOf(intArrayOf(0, 0, 0, 0), intArrayOf(0, 0, 1, 1)))
        // Same blocks, palette order swapped, state indices remapped accordingly.
        val b = structureTag(Triple(1, 1, 2), listOf("minecraft:gold_block", "minecraft:stone"),
            listOf(intArrayOf(0, 0, 0, 1), intArrayOf(0, 0, 1, 0)))
        structuresDiffer(a, b) shouldBe false
    }
    test("a changed block state is different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(1, 1, 1), listOf("minecraft:gold_block"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe true
    }
    test("a changed size is different") {
        val a = structureTag(Triple(1, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        val b = structureTag(Triple(2, 1, 1), listOf("minecraft:stone"), listOf(intArrayOf(0, 0, 0, 0)))
        structuresDiffer(a, b) shouldBe true
    }
    test("unsavedSidecarOf appends .unsaved adjacent to the file") {
        val f = java.nio.file.Path.of("/proj", "sub", "gadget.nbt")
        val sc = com.breadmoirai.redstonespecs.persistence.StructurePersistence.unsavedSidecarOf(f)
        sc.fileName.toString() shouldBe "gadget.nbt.unsaved"
        sc.parent shouldBe f.parent
        sc.fileName.toString().endsWith(".nbt") shouldBe false
    }
})
