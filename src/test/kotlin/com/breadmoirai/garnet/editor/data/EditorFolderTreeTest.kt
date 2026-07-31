package com.breadmoirai.garnet.editor.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class EditorFolderTreeTest : FunSpec({

    test("leaf folder with one .spec.kts is reported as a leaf") {
        val tmp = Files.createTempDirectory("mft-leaf")
        val leafDir = tmp.resolve("alpha").also { it.createDirectories() }
        leafDir.resolve("foo.spec.kts").writeText("")
        val tree = EditorFolderTree.scan(EditorRoot(tmp))
        tree.leaves shouldContainExactly listOf(ProjectLeaf("alpha", listOf("foo.spec.kts")))
        tree.intermediates shouldBe emptySet()
    }

    test("intermediate folder containing only a subfolder is in intermediates; deeper leaf in leaves") {
        val tmp = Files.createTempDirectory("mft-intermediate")
        val deep = tmp.resolve("outer/inner").also { it.createDirectories() }
        deep.resolve("bar.spec.kts").writeText("")
        val tree = EditorFolderTree.scan(EditorRoot(tmp))
        tree.intermediates shouldContain "outer"
        tree.leaves shouldContainExactly listOf(ProjectLeaf("outer/inner", listOf("bar.spec.kts")))
    }

    test("folder with both spec files and subfolders appears in leaves AND intermediates") {
        val tmp = Files.createTempDirectory("mft-both")
        val mid = tmp.resolve("mid").also { it.createDirectories() }
        mid.resolve("a.spec.kts").writeText("")
        val sub = mid.resolve("sub").also { it.createDirectories() }
        sub.resolve("b.spec.kts").writeText("")
        val tree = EditorFolderTree.scan(EditorRoot(tmp))
        tree.leaves shouldContainExactly listOf(
            ProjectLeaf("mid", listOf("a.spec.kts")),
            ProjectLeaf("mid/sub", listOf("b.spec.kts")),
        )
        tree.intermediates shouldContain "mid"
    }

    test("non-.spec.kts files are ignored") {
        val tmp = Files.createTempDirectory("mft-ignore")
        val dir = tmp.resolve("docs").also { it.createDirectories() }
        dir.resolve("readme.md").writeText("hello")
        dir.resolve("notes.txt").writeText("hi")
        val tree = EditorFolderTree.scan(EditorRoot(tmp))
        tree.leaves shouldBe emptyList()
        tree.intermediates shouldBe emptySet()
    }

    test("empty root yields empty leaves and intermediates") {
        val tmp = Files.createTempDirectory("mft-empty")
        val tree = EditorFolderTree.scan(EditorRoot(tmp))
        tree.leaves shouldBe emptyList()
        tree.intermediates shouldBe emptySet()
    }
})
