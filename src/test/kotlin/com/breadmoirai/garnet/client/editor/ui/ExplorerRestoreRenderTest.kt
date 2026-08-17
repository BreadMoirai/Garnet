@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
)

package com.breadmoirai.garnet.client.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerSession
import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.explorer.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeSnapshot
import com.breadmoirai.garnet.editor.explorer.ui.explorerPanel
import com.breadmoirai.garnet.dock.compose.ComposeSceneHost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import org.jetbrains.jewel.foundation.lazy.tree.DefaultTreeViewKeyActions
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

/**
 * A restored expansion set must survive LazyTree's first composition.
 *
 * Mirrors the shape of a real `config/garnet-explorer.json` record: the root plus a three-deep
 * chain of nested folders.
 */
class ExplorerRestoreRenderTest : StringSpec({

    val root = FolderNode("Garnet", listOf(
        FolderNode("Logic Circuits", listOf(
            FolderNode("Logic Gates", listOf(
                FolderNode("NOT Gates", listOf(FileNode("Horizontal Torch Inverter.nbt", "nbt"))),
            )),
        )),
        FileNode("clock.nbt", "nbt"),
    ))

    val restored = setOf("", "Logic Circuits", "Logic Circuits/Logic Gates", "Logic Circuits/Logic Gates/NOT Gates")

    "a restored expansion set still renders its deep rows" {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", restored, null))
            ExplorerTreeState.applyPendingRestore(root)

            val composed = mutableListOf<String>()
            val tree = ExplorerTreeState.buildTreeFrom(root)
            val treeState = ExplorerTreeState.treeState
            val host = ComposeSceneHost(300, 400) {
                IntUiTheme(isDark = true) {
                    LazyTree(
                        tree = tree,
                        modifier = Modifier.fillMaxSize(),
                        treeState = treeState,
                        keyActions = DefaultTreeViewKeyActions(treeState),
                    ) { element ->
                        composed += ExplorerTreeState.pathOf(element)
                        Text(element.data.name)
                    }
                }
            }
            try {
                host.render(System.nanoTime())
            } finally {
                host.close()
            }

            composed shouldContainAll restored
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    /**
     * The real join sequence: the Explorer panel is already mounted (the dock auto-opens LEFT on
     * join) and has composed at least one frame with no snapshot, and only then does the tree
     * snapshot land and consume the armed restore.
     */
    "the mounted panel keeps the restored expansion when the snapshot lands" {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
            ExplorerTreeState.armRestore(ExplorerSession("/tmp/proj", restored, null))

            val panel = explorerPanel()
            val host = ComposeSceneHost(300, 400) { panel.content(panel) }
            try {
                // Frame 1: mounted, no snapshot yet.
                host.render(System.nanoTime())

                // The snapshot receiver, verbatim: state first, then the restore.
                ExplorerTreeSnapshot.onSnapshot(EditorTreeSnapshotS2C(root, null))
                ExplorerTreeState.applyPendingRestore(root)

                host.render(System.nanoTime() + 16_000_000)
                host.render(System.nanoTime() + 32_000_000)
            } finally {
                host.close()
            }

            ExplorerTreeState.expandedPaths shouldContainAll restored
        } finally {
            SharedSettings.projectRootPath = prior
            ExplorerTreeSnapshot.reset()
            ExplorerTreeState.reset()
        }
    }
})
