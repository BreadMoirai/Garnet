package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.config.ExplorerSession
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.ui.ExplorerEdit
import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FileTreeNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.NewNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.foundation.lazy.tree.Tree

class ExplorerTreeStateTest : FunSpec({

    val tree = FolderNode("root", listOf(
        FolderNode("adders", listOf(
            FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
        )),
        FileNode("dirty.nbt", "nbt"),
        FileNode("clean.nbt", "nbt"),
    ))

    test("selection is stored in Jewel's TreeState, keyed by path") {
        ExplorerTreeState.reset()
        ExplorerTreeState.select("adders/full-adder")
        ExplorerTreeState.treeState.selectedKeys shouldContainExactly setOf("adders/full-adder")
        ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
    }

    test("expansion toggles Jewel's openNodes, keyed by path") {
        ExplorerTreeState.reset()
        ExplorerTreeState.toggleExpanded("adders")
        ExplorerTreeState.treeState.openNodes shouldContain "adders"
        ExplorerTreeState.expandedPaths shouldContain "adders"
        ExplorerTreeState.toggleExpanded("adders")
        ExplorerTreeState.treeState.openNodes shouldNotContain "adders"
    }

    test("an armed restore reopens the persisted folders when the snapshot lands") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(
                ExplorerSession("/tmp/proj", setOf("", "adders"), "adders/full-adder"),
            )
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths shouldContainExactly setOf("", "adders")
            ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("a restore captured against a different root is discarded") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/other"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), "adders"))
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
            ExplorerTreeState.selectedPath.shouldBeNull()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("paths that no longer exist are dropped from the restore") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(
                ExplorerSession("/tmp/proj", setOf("adders", "deleted-folder"), "gone.nbt"),
            )
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths shouldContainExactly setOf("adders")
            ExplorerTreeState.selectedPath.shouldBeNull()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("a file path is never restored as an expanded node") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("dirty.nbt"), null))
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("the restore is one-shot: a second snapshot does not clobber live expansion") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), null))
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.collapseAll()
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("reset disarms a pending restore") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", setOf("adders"), null))
            ExplorerTreeState.reset()
            ExplorerTreeState.applyPendingRestore(tree)
            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("buildTreeFrom emits the project root as the single top-level node") {
        val root = FolderNode("myproject", listOf(
            FolderNode("adders", listOf(FileNode("full.spec.kts", "kts"))),
            FileNode("clock.nbt", "nbt"),
        ))

        val built = ExplorerTreeState.buildTreeFrom(root)

        built.roots.size shouldBe 1
        val rootElement = built.roots.single()
        ExplorerTreeState.pathOf(rootElement) shouldBe ExplorerTreeState.ROOT_PATH
        rootElement.data shouldBe root

        // Tree.Element.Node.children is lazy — open() materializes it.
        val node = rootElement as Tree.Element.Node<com.breadmoirai.garnet.editor.data.FileTreeNode>
        node.open()
        node.children!!.map { ExplorerTreeState.pathOf(it) } shouldBe listOf("adders", "clock.nbt")
    }

    test("buildTreeFrom mirrors the snapshot with path ids, folders keeping their children") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val rootElement = built.roots.single() as Tree.Element.Node<com.breadmoirai.garnet.editor.data.FileTreeNode>
        rootElement.open(false)
        val ids = (rootElement.children ?: emptyList()).map { it.id }
        ids shouldContainExactly listOf("adders", "dirty.nbt", "clean.nbt")
    }

    test("buildTreeFrom nests folder children with /-joined ids, matching select/toggleExpanded's format") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val rootElement = built.roots.single() as Tree.Element.Node<com.breadmoirai.garnet.editor.data.FileTreeNode>
        rootElement.open(false)
        val rootChildren = rootElement.children ?: emptyList()
        val adders = rootChildren.first { it.id == "adders" } as Tree.Element.Node<com.breadmoirai.garnet.editor.data.FileTreeNode>
        adders.open(false) // children are lazily evaluated on open, per Jewel's Tree.Element.Node
        val addersChildren = adders.children ?: emptyList()
        addersChildren.map { it.id } shouldContainExactly listOf("adders/full-adder")
        val fullAdder = addersChildren.first() as Tree.Element.Node<com.breadmoirai.garnet.editor.data.FileTreeNode>
        fullAdder.open(false)
        val fullAdderChildren = fullAdder.children ?: emptyList()
        fullAdderChildren.map { it.id } shouldContainExactly listOf("adders/full-adder/full.spec.kts")
    }

    test("collapseAll clears every open node") {
        ExplorerTreeState.reset()
        ExplorerTreeState.treeState.openNodes = setOf("adders", "adders/full-adder", "clocks")
        ExplorerTreeState.expandedPaths shouldBe setOf("adders", "adders/full-adder", "clocks")

        ExplorerTreeState.collapseAll()

        ExplorerTreeState.expandedPaths.shouldBeEmpty()
    }

    test("reset clears selection and expansion") {
        ExplorerTreeState.reset()
        ExplorerTreeState.select("dirty.nbt")
        ExplorerTreeState.toggleExpanded("adders")
        ExplorerTreeState.reset()
        ExplorerTreeState.selectedPath shouldBe null
        ExplorerTreeState.expandedPaths shouldBe emptySet()
    }

    test("a pending create injects a placeholder row into the target folder") {
        val root = FolderNode("myproject", listOf(
            FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))),
        ))
        val edit = ExplorerEdit.Creating("redstone", NewNodeKind.FOLDER)

        val tree = ExplorerTreeState.buildTreeFrom(root, edit)

        val rootNode = tree.roots.single() as Tree.Element.Node<FileTreeNode>
        rootNode.open()
        val redstone = rootNode.children!!.single() as Tree.Element.Node<FileTreeNode>
        redstone.open()
        ExplorerTreeState.pathOf(redstone.children!!.last()) shouldBe ExplorerEdit.pendingIdFor("redstone")
    }

    test("a pending create at the root injects the placeholder at top level") {
        val root = FolderNode("myproject", listOf(FileNode("clock.nbt", "nbt")))
        val edit = ExplorerEdit.Creating(ExplorerTreeState.ROOT_PATH, NewNodeKind.STRUCTURE)

        val tree = ExplorerTreeState.buildTreeFrom(root, edit)

        val rootNode = tree.roots.single() as Tree.Element.Node<FileTreeNode>
        rootNode.open()
        ExplorerTreeState.pathOf(rootNode.children!!.last()) shouldBe
            ExplorerEdit.pendingIdFor(ExplorerTreeState.ROOT_PATH)
    }

    test("the pending id can never collide with a real path") {
        ExplorerEdit.isPendingId(ExplorerEdit.pendingIdFor("redstone")).shouldBeTrue()
        ExplorerEdit.isPendingId("redstone/clock.nbt").shouldBeFalse()
        ExplorerEdit.isPendingId(ExplorerTreeState.ROOT_PATH).shouldBeFalse()
    }

    test("no pending create leaves the tree untouched") {
        val root = FolderNode("myproject", listOf(FileNode("clock.nbt", "nbt")))
        val rootNode = ExplorerTreeState.buildTreeFrom(root, null).roots.single()
            as Tree.Element.Node<FileTreeNode>
        rootNode.open()
        rootNode.children!!.map { ExplorerTreeState.pathOf(it) } shouldBe listOf("clock.nbt")
    }
})
