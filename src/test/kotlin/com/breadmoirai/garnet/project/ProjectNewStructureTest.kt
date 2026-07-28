package com.breadmoirai.garnet.project

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.name

class ProjectNewStructureTest : FunSpec({

    test("create writes a readable <name>.nbt with a size tag") {
        val dir = Files.createTempDirectory("new-structure")
        val file = ProjectNewStructure.create(dir, "gadget")
        file.name shouldBe "gadget.nbt"
        file.exists().shouldBeTrue()
        // Re-read: a valid compressed structure NBT carries an int-list "size".
        val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
        tag.contains("size").shouldBeTrue()
    }

    test("create rejects a blank or illegal name and an existing file") {
        val dir = Files.createTempDirectory("new-structure-bad")
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "") }
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "has space") }
        ProjectNewStructure.create(dir, "dup")
        shouldThrow<IllegalArgumentException> { ProjectNewStructure.create(dir, "dup") }
    }
})
