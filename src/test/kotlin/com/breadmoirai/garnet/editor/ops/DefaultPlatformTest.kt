package com.breadmoirai.garnet.editor.ops

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

/**
 * `StructureTemplate.load` needs a `HolderGetter<Block>`; the vanilla built-in block registry
 * doubles as one once `Bootstrap.bootStrap()` has run, so these tests need no world.
 */
class DefaultPlatformTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun loadTemplate(tag: net.minecraft.nbt.CompoundTag): StructureTemplate {
        val template = StructureTemplate()
        template.load(BuiltInRegistries.BLOCK, tag)
        return template
    }

    test("a 3x3 tag round-trips through StructureTemplate.load as a one-block-thick slab") {
        val tag = DefaultPlatform.platformTag(3, 3, "minecraft:smooth_stone").shouldNotBeNull()
        val template = loadTemplate(tag)
        template.size shouldBe Vec3i(3, 1, 3)

        // filterBlocks on SMOOTH_STONE with absolute=false returns the local (untransformed)
        // blocks at their built positions. Must cover every cell of the slab exactly once.
        val blocks = template.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.SMOOTH_STONE, false)
        blocks.size shouldBe 9
        blocks.map { it.pos }.toSet() shouldBe
            (0..2).flatMap { x -> (0..2).map { z -> BlockPos(x, 0, z) } }.toSet()
    }

    test("non-square dimensions produce the right cell count and size") {
        val tag = DefaultPlatform.platformTag(5, 2, "minecraft:gold_block").shouldNotBeNull()
        val template = loadTemplate(tag)
        template.size shouldBe Vec3i(5, 1, 2)
        val blocks = template.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.GOLD_BLOCK, false)
        blocks.size shouldBe 10
    }

    test("an unknown or malformed block id falls back to smooth stone instead of throwing") {
        val unknown = DefaultPlatform.platformTag(1, 1, "minecraft:not_a_real_block").shouldNotBeNull()
        val unknownTemplate = loadTemplate(unknown)
        val unknownBlocks = unknownTemplate.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.SMOOTH_STONE, false)
        unknownBlocks.size shouldBe 1

        val malformed = DefaultPlatform.platformTag(1, 1, "NOT AN ID").shouldNotBeNull()
        val malformedTemplate = loadTemplate(malformed)
        val malformedBlocks = malformedTemplate.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.SMOOTH_STONE, false)
        malformedBlocks.size shouldBe 1
    }

    test("a non-positive width or depth disables the platform") {
        DefaultPlatform.platformTag(0, 3, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(3, 0, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(-1, -1, "minecraft:smooth_stone").shouldBeNull()
    }
})
