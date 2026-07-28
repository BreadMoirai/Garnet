package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

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

    test("buildTreeFrom mirrors the snapshot with path ids, folders keeping their children") {
        val built = ExplorerTreeState.buildTreeFrom(tree)
        val ids = built.roots.map { it.id }
        ids shouldContainExactly listOf("adders", "dirty.nbt", "clean.nbt")
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
})
