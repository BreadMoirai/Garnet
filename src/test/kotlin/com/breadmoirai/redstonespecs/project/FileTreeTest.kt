package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.writeText

class FileTreeTest : FunSpec({

    test("scans nested folders and files into a recursive tree, including empty folders") {
        val tmp = Files.createTempDirectory("ft-structure")
        (tmp / "sub").createDirectories()
        (tmp / "a.spec.kts").writeText("")
        (tmp / "sub" / "b.spec.kts").writeText("")
        (tmp / "sub" / "empty").createDirectories()

        val root = scanFolder(tmp)

        root.name shouldBe tmp.name
        root.children.map { it.name } shouldContainExactly listOf("sub", "a.spec.kts")

        val sub = root.children.first { it.name == "sub" } as FolderNode
        sub.children.map { it.name } shouldContainExactly listOf("empty", "b.spec.kts")
        (sub.children.first { it.name == "empty" } as FolderNode).children shouldBe emptyList()
    }

    test("children are folders-first, then files, alphabetical case-insensitive") {
        val tmp = Files.createTempDirectory("ft-order")
        (tmp / "Zeta").createDirectories()
        (tmp / "alpha").createDirectories()
        (tmp / "b.txt").writeText("")
        (tmp / "A.txt").writeText("")

        val root = scanFolder(tmp)

        root.children.map { it.name } shouldContainExactly listOf("alpha", "Zeta", "A.txt", "b.txt")
    }

    test("file nodes carry the lowercased last-dot extension; empty when none") {
        val tmp = Files.createTempDirectory("ft-ext")
        (tmp / "foo.spec.kts").writeText("")
        (tmp / "data.NBT").writeText("")
        (tmp / "README").writeText("")

        val root = scanFolder(tmp)
        val byName = root.children.filterIsInstance<FileNode>().associateBy { it.name }

        byName.getValue("foo.spec.kts").extension shouldBe "kts"
        byName.getValue("data.NBT").extension shouldBe "nbt"
        byName.getValue("README").extension shouldBe ""
    }

    test("non-existent or non-directory path yields an empty root folder named after the path") {
        val tmp = Files.createTempDirectory("ft-missing")
        scanFolder(tmp / "nope") shouldBe FolderNode("nope", emptyList())

        val file = (tmp / "file.txt").also { it.writeText("") }
        scanFolder(file) shouldBe FolderNode("file.txt", emptyList())
    }
})
