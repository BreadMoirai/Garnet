package com.breadmoirai.garnet.editor.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import com.breadmoirai.garnet.config.SharedSettings
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name

class EditorNewStructureTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("create writes a readable <name>.nbt with a size tag") {
        val dir = Files.createTempDirectory("new-structure")
        val file = EditorNewStructure.create(dir, "gadget")
        file.name shouldBe "gadget.nbt"
        file.exists().shouldBeTrue()
        // Re-read: a valid compressed structure NBT carries an int-list "size".
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        tag.contains("size").shouldBeTrue()
    }

    test("create handles valid names (including spaces) and rejects blank, illegal, or duplicate names") {
        val dir = Files.createTempDirectory("new-structure-bad")
        shouldThrow<IllegalArgumentException> { EditorNewStructure.create(dir, "") }
        val spaceFile = EditorNewStructure.create(dir, "has space")
        spaceFile.name shouldBe "has space.nbt"
        spaceFile.exists().shouldBeTrue()
        shouldThrow<IllegalArgumentException> { EditorNewStructure.create(dir, "has/slash") }
        EditorNewStructure.create(dir, "dup")
        shouldThrow<IllegalArgumentException> { EditorNewStructure.create(dir, "dup") }
    }

    test("a created structure carries the configured default platform") {
        val dir = Files.createTempDirectory("new-structure-platform")
        val prevBlock = SharedSettings.newStructurePlatformBlock
        val prevWidth = SharedSettings.newStructurePlatformWidth
        val prevDepth = SharedSettings.newStructurePlatformDepth
        try {
            SharedSettings.newStructurePlatformBlock = "minecraft:smooth_stone"
            SharedSettings.newStructurePlatformWidth = 3
            SharedSettings.newStructurePlatformDepth = 3

            val file = EditorNewStructure.create(dir, "platformed")
            val template = StructureTemplate()
            template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))

            template.size shouldBe Vec3i(3, 1, 3)
            val blocks = template.filterBlocks(BlockPos.ZERO, StructurePlaceSettings(), Blocks.SMOOTH_STONE, false)
            blocks.size shouldBe 9
            blocks.all { it.state == Blocks.SMOOTH_STONE.defaultBlockState() } shouldBe true
        } finally {
            SharedSettings.newStructurePlatformBlock = prevBlock
            SharedSettings.newStructurePlatformWidth = prevWidth
            SharedSettings.newStructurePlatformDepth = prevDepth
            dir.toFile().deleteRecursively()
        }
    }

    test("a zero-width platform setting still creates an empty structure") {
        val dir = Files.createTempDirectory("new-structure-noplatform")
        val prevWidth = SharedSettings.newStructurePlatformWidth
        try {
            SharedSettings.newStructurePlatformWidth = 0

            val file = EditorNewStructure.create(dir, "bare")
            val template = StructureTemplate()
            template.load(net.minecraft.core.registries.BuiltInRegistries.BLOCK, NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()))

            template.size shouldBe Vec3i(0, 0, 0)
        } finally {
            SharedSettings.newStructurePlatformWidth = prevWidth
            dir.toFile().deleteRecursively()
        }
    }
})
