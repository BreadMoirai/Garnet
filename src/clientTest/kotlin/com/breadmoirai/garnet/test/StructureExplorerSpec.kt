package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.StructureResultS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.shouldBe

class StructureExplorerSpec : ClientSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        runOnClient {
            ProjectTreeState.reset()
            ExplorerTreeState.reset()
            ProjectTreeState.onStructureResult(
                StructureResultS2C("a/box.nbt", 2, 1, 3, hasUnsaved = false, message = "placed a/box.nbt"),
            )
        }
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }

    test("selectedHasUnsaved reflects the dirty flag on the selected .nbt node") {
        runOnClient {
            ProjectTreeState.reset()
            ExplorerTreeState.reset()
            val root = FolderNode("root", listOf(
                FileNode("dirty.nbt", "nbt", hasUnsaved = true),
                FileNode("clean.nbt", "nbt", hasUnsaved = false),
            ))
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root, currentSubpath = null))
            ExplorerTreeState.select("dirty.nbt")
        }
        ExplorerTreeState.selectedHasUnsaved() shouldBe true
        runOnClient { ExplorerTreeState.select("clean.nbt") }
        ExplorerTreeState.selectedHasUnsaved() shouldBe false
    }
})
