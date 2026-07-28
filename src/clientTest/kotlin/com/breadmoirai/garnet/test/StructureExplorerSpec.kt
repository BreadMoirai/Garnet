package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.StructureResultS2C
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.shouldBe

class StructureExplorerSpec : ClientSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        runOnClient {
            ProjectTreeState.reset()
            ProjectTreeState.onStructureResult(
                StructureResultS2C("a/box.nbt", 2, 1, 3, hasUnsaved = false, message = "placed a/box.nbt"),
            )
        }
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }

    test("selectedHasUnsaved reflects the dirty flag on the selected .nbt node") {
        runOnClient {
            ProjectTreeState.reset()
            val root = FolderNode("root", listOf(
                FileNode("dirty.nbt", "nbt", hasUnsaved = true),
                FileNode("clean.nbt", "nbt", hasUnsaved = false),
            ))
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(root, currentSubpath = null))
            ProjectTreeState.select("dirty.nbt")
        }
        ProjectTreeState.selectedHasUnsaved() shouldBe true
        runOnClient { ProjectTreeState.select("clean.nbt") }
        ProjectTreeState.selectedHasUnsaved() shouldBe false
    }
})
