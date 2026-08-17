@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
)

package com.breadmoirai.garnet.client.editor.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerActions
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.explorer.ui.onElementClick
import com.breadmoirai.garnet.ui.compose.ComposeSceneHost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import org.jetbrains.jewel.foundation.lazy.tree.DefaultTreeViewKeyActions
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text

/**
 * Row-click expansion, driven through real pointer events rather than by calling the handler.
 *
 * The risk this covers is double-toggling. Jewel's `LazyTree` opens a node from its own chevron, so
 * if a chevron press ALSO reached `onElementClick` the node would toggle twice and the chevron
 * would look dead. Asserting the state after a real click is the only way to know which of the two
 * handlers actually runs.
 *
 * The tree is mounted the way [com.breadmoirai.garnet.editor.explorer.ui.explorerPanel] mounts it — same
 * click handler — but without the panel's toolbar above it, so the root row is at a known y and no
 * toolbar-height assumption is baked into the click coordinates.
 */
/**
 * Row geometry at `Density(1f)`, measured by sweeping click positions across the root row: x below
 * 18 is IntUi's default `elementPadding` gutter (dead, and removed in the real panel by
 * `flushTreeStyle`), 18..26 is the chevron, and 40 onwards is the icon-and-label body of the row.
 */
private const val CHEVRON_X = 20f
private const val LABEL_X = 80f

/** The first row's vertical middle; rows are ~24 px tall. */
private const val ROW_Y = 6f

class ExplorerRowClickSceneTest : StringSpec({

    val root = FolderNode("myproject", listOf(
        FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))),
    ))

    /** Mounts the tree with the root open, clicks once at [x],[y], and reports the open nodes. */
    fun openNodesAfterClickAt(x: Float, y: Float, handler: (Any, String) -> Unit = { node, path ->
        onElementClick(node as com.breadmoirai.garnet.editor.explorer.data.FileTreeNode, path)
    }): Set<String> {
        ExplorerTreeState.reset()
        ExplorerActions.sender = { }
        ExplorerTreeState.treeState.openNodes = setOf(ExplorerTreeState.ROOT_PATH)
        val tree = ExplorerTreeState.buildTreeFrom(root)
        val treeState = ExplorerTreeState.treeState
        val host = ComposeSceneHost(300, 200) {
            IntUiTheme(isDark = true) {
                LazyTree(
                    tree = tree,
                    modifier = Modifier.fillMaxSize(),
                    treeState = treeState,
                    keyActions = DefaultTreeViewKeyActions(treeState),
                    onElementClick = { element -> handler(element.data, ExplorerTreeState.pathOf(element)) },
                ) { element -> Text(element.data.name) }
            }
        }
        return try {
            host.render(System.nanoTime())
            host.pointerMove(Offset(x, y))
            host.pointerPress(Offset(x, y))
            host.pointerRelease(Offset(x, y))
            host.render(System.nanoTime() + 16_000_000)
            ExplorerTreeState.expandedPaths
        } finally {
            host.close()
            ExplorerActions.resetForTest()
        }
    }

    "clicking the root row's label collapses it" {
        openNodesAfterClickAt(x = LABEL_X, y = ROW_Y) shouldNotContain ExplorerTreeState.ROOT_PATH
    }

    "the label area does nothing without our click handler" {
        // The control the case above needs: out on the label, Jewel alone leaves expansion untouched.
        // That is precisely the gap this feature closes, and it is what makes the toggle above ours
        // rather than something Jewel was doing anyway.
        openNodesAfterClickAt(x = LABEL_X, y = ROW_Y, handler = { _, _ -> }) shouldContain
            ExplorerTreeState.ROOT_PATH
    }

    "the chevron still collapses exactly once, without our handler doubling it" {
        // Jewel's chevron consumes its own press, so onElementClick never fires for it. If both ran,
        // the node would toggle twice and the chevron would look dead.
        openNodesAfterClickAt(x = CHEVRON_X, y = ROW_Y) shouldNotContain ExplorerTreeState.ROOT_PATH
    }
})
