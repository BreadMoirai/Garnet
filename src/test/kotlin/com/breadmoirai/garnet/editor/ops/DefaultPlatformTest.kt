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

        // getPalettes()[0] is the single palette this builder writes; its block list must cover
        // every cell of the slab exactly once, all at y = 0, all smooth stone.
        val blocks = template.palettes[0].blocks()
        blocks.size shouldBe 9
        blocks.map { it.pos }.toSet() shouldBe
            (0..2).flatMap { x -> (0..2).map { z -> BlockPos(x, 0, z) } }.toSet()
        blocks.all { it.state == Blocks.SMOOTH_STONE.defaultBlockState() } shouldBe true
    }

    test("non-square dimensions produce the right cell count and size") {
        val tag = DefaultPlatform.platformTag(5, 2, "minecraft:gold_block").shouldNotBeNull()
        val template = loadTemplate(tag)
        template.size shouldBe Vec3i(5, 1, 2)
        template.palettes[0].blocks().size shouldBe 10
    }

    test("an unknown or malformed block id falls back to smooth stone instead of throwing") {
        val unknown = DefaultPlatform.platformTag(1, 1, "minecraft:not_a_real_block").shouldNotBeNull()
        loadTemplate(unknown).palettes[0].blocks()[0].state shouldBe
            Blocks.SMOOTH_STONE.defaultBlockState()

        val malformed = DefaultPlatform.platformTag(1, 1, "NOT AN ID").shouldNotBeNull()
        loadTemplate(malformed).palettes[0].blocks()[0].state shouldBe
            Blocks.SMOOTH_STONE.defaultBlockState()
    }

    test("a non-positive width or depth disables the platform") {
        DefaultPlatform.platformTag(0, 3, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(3, 0, "minecraft:smooth_stone").shouldBeNull()
        DefaultPlatform.platformTag(-1, -1, "minecraft:smooth_stone").shouldBeNull()
    }
})
