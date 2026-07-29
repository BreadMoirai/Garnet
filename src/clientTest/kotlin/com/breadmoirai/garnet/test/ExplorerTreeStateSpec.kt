package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerEdit
import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FileTreeNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.foundation.lazy.tree.Tree

class ExplorerTreeStateSpec : ClientSpec({

    val tree = FolderNode("root", listOf(
        FolderNode("adders", listOf(
            FolderNode("full-adder", listOf(FileNode("full.spec.kts", "kts"))),
        )),
        FileNode("dirty.nbt", "nbt", hasUnsaved = true),
        FileNode("clean.nbt", "nbt", hasUnsaved = false),
    ))

    test("selection is stored in Jewel's TreeState, keyed by path") {
        runOnClient { ExplorerTreeState.reset(); ExplorerTreeState.select("adders/full-adder") }
        ExplorerTreeState.treeState.selectedKeys shouldContainExactly setOf("adders/full-adder")
        ExplorerTreeState.selectedPath shouldBe "adders/full-adder"
    }

    test("expansion toggles Jewel's openNodes, keyed by path") {
        runOnClient { ExplorerTreeState.reset(); ExplorerTreeState.toggleExpanded("adders") }
        ExplorerTreeState.treeState.openNodes shouldContain "adders"
        ExplorerTreeState.expandedPaths shouldContain "adders"
        runOnClient { ExplorerTreeState.toggleExpanded("adders") }
        ExplorerTreeState.treeState.openNodes shouldNotContain "adders"
    }

    test("selectedHasUnsaved is derived from the snapshot, not stored") {
        runOnClient {
            ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(tree, currentSubpath = null))
            ExplorerTreeState.select("dirty.nbt")
        }
        ExplorerTreeState.selectedHasUnsaved() shouldBe true
        runOnClient { ExplorerTreeState.select("clean.nbt") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
        runOnClient { ExplorerTreeState.select("adders") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
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
        val node = rootElement as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
        node.open()
        node.children!!.map { ExplorerTreeState.pathOf(it) } shouldBe listOf("adders", "clock.nbt")
    }

    test("buildTreeFrom mirrors the snapshot with path ids, folders keeping their children") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val rootElement = built.roots.single() as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
        rootElement.open(false)
        val ids = (rootElement.children ?: emptyList()).map { it.id }
        ids shouldContainExactly listOf("adders", "dirty.nbt", "clean.nbt")
    }

    test("buildTreeFrom nests folder children with /-joined ids, matching select/toggleExpanded's format") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val rootElement = built.roots.single() as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
        rootElement.open(false)
        val rootChildren = rootElement.children ?: emptyList()
        val adders = rootChildren.first { it.id == "adders" } as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
        adders.open(false) // children are lazily evaluated on open, per Jewel's Tree.Element.Node
        val addersChildren = adders.children ?: emptyList()
        addersChildren.map { it.id } shouldContainExactly listOf("adders/full-adder")
        val fullAdder = addersChildren.first() as Tree.Element.Node<com.breadmoirai.garnet.project.FileTreeNode>
        fullAdder.open(false)
        val fullAdderChildren = fullAdder.children ?: emptyList()
        fullAdderChildren.map { it.id } shouldContainExactly listOf("adders/full-adder/full.spec.kts")
    }

    test("selectedHasUnsaved is false with no snapshot, and with a selection absent from the snapshot") {
        runOnClient { ProjectTreeState.reset(); ExplorerTreeState.reset(); ExplorerTreeState.select("dirty.nbt") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
        runOnClient {
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(tree, currentSubpath = null))
            ExplorerTreeState.select("does/not/exist")
        }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
    }

    test("collapseAll clears every open node") {
        ExplorerTreeState.reset()
        ExplorerTreeState.treeState.openNodes = setOf("adders", "adders/full-adder", "clocks")
        ExplorerTreeState.expandedPaths shouldBe setOf("adders", "adders/full-adder", "clocks")

        ExplorerTreeState.collapseAll()

        ExplorerTreeState.expandedPaths.shouldBeEmpty()
    }

    test("reset clears selection and expansion") {
        runOnClient {
            ExplorerTreeState.reset()
            ExplorerTreeState.select("dirty.nbt")
            ExplorerTreeState.toggleExpanded("adders")
            ExplorerTreeState.reset()
        }
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
