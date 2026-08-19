package com.breadmoirai.garnet.editor.explorer.ui

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.explorer.network.EditorTreeSnapshotS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Proves the singleplayer gate is genuinely two-sided: both [saveExplorerSession] and
 * [armRestoreIfSingleplayer] must route through [ExplorerSessionGate.isSingleplayer], not a direct
 * `Minecraft.getInstance().hasSingleplayerServer()` call. Modeled on `ExplorerStateStoreTest` for the
 * config-file seam and `ExplorerTreeStateTest` for the `SharedSettings`/`runOnClient` discipline.
 */
class ExplorerLifecycleTest : FunSpec({

    val tree = FolderNode("root", listOf(
        FolderNode("adders", listOf()),
    ))

    test("singleplayer with a snapshot present writes the record") {
        val dir = createTempDirectory("garnet-explorer-lifecycle-save")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        val priorRoot = SharedSettings.projectRootPath
        try {
            ExplorerSessionGate.isSingleplayer = { true }
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeSnapshot.onSnapshot(EditorTreeSnapshotS2C(tree, null))
            ExplorerTreeState.reset()
            ExplorerTreeState.toggleExpanded("adders")

            saveExplorerSession()

            val loaded = ExplorerStateStore.load()!!
            loaded.root shouldBe "/tmp/proj"
            loaded.expanded shouldContainExactly setOf("adders")
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            ExplorerSessionGate.resetForTest()
            SharedSettings.projectRootPath = priorRoot
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
            dir.toFile().deleteRecursively()
        }
    }

    test("not singleplayer writes nothing") {
        val dir = createTempDirectory("garnet-explorer-lifecycle-noSave")
        val file = dir.resolve("garnet-explorer.json").toFile()
        ExplorerStateStore.configFileForTest(file)
        val priorRoot = SharedSettings.projectRootPath
        try {
            ExplorerSessionGate.isSingleplayer = { false }
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeSnapshot.onSnapshot(EditorTreeSnapshotS2C(tree, null))
            ExplorerTreeState.reset()
            ExplorerTreeState.toggleExpanded("adders")

            saveExplorerSession()

            file.exists() shouldBe false
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            ExplorerSessionGate.resetForTest()
            SharedSettings.projectRootPath = priorRoot
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
            dir.toFile().deleteRecursively()
        }
    }

    test("not singleplayer arms no restore") {
        val dir = createTempDirectory("garnet-explorer-lifecycle-noArm")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        val priorRoot = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerStateStore.save("/tmp/proj", setOf("adders"), null)
            ExplorerSessionGate.isSingleplayer = { false }

            ExplorerTreeState.reset()
            armRestoreIfSingleplayer()
            ExplorerTreeState.applyPendingRestore(tree)

            ExplorerTreeState.expandedPaths.shouldBeEmpty()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            ExplorerSessionGate.resetForTest()
            SharedSettings.projectRootPath = priorRoot
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
            dir.toFile().deleteRecursively()
        }
    }

    test("singleplayer arms a restore") {
        val dir = createTempDirectory("garnet-explorer-lifecycle-arm")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        val priorRoot = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerStateStore.save("/tmp/proj", setOf("adders"), null)
            ExplorerSessionGate.isSingleplayer = { true }

            ExplorerTreeState.reset()
            armRestoreIfSingleplayer()
            ExplorerTreeState.applyPendingRestore(tree)

            ExplorerTreeState.expandedPaths shouldContainExactly setOf("adders")
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            ExplorerSessionGate.resetForTest()
            SharedSettings.projectRootPath = priorRoot
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
            dir.toFile().deleteRecursively()
        }
    }
})
