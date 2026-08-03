package com.breadmoirai.garnet.editor.data

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.Bootstrap
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
})
