package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.network.project.LoadProjectFolderC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Panel background, matching the IntelliJ dark tool-window colour. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("garnet.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            ExplorerToolbar()
            val snap = ProjectTreeState.snapshot
            if (snap == null) {
                Text("(no project loaded — Refresh)", Modifier.padding(vertical = 2.dp))
            } else {
                // remember(snap.root): buildTreeFrom walks the WHOLE project tree recursively and
                // allocates a fresh Tree, which LazyTree then has to re-flatten. This scope also
                // reads ProjectTreeState.status, which changes on every S2C packet, so an
                // un-remembered call rebuilds the entire tree on each packet. Keyed on the root so a
                // genuinely new snapshot still rebuilds.
                val tree = remember(snap.root) { ExplorerTreeState.buildTreeFrom(snap.root) }
                LazyTree(
                    tree = tree,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    treeState = ExplorerTreeState.treeState,
                    onElementClick = { element -> onElementClick(element.data, ExplorerTreeState.pathOf(element)) },
                ) { element ->
                    TreeRow(element.data, ExplorerTreeState.pathOf(element), snap.currentSubpath)
                }
            }
            val status = ProjectTreeState.status
            if (status.isNotEmpty()) Text(status, Modifier.padding(top = 4.dp))
        }
    }
}

/**
 * Click behavior, preserved from the hand-rolled tree: a `.nbt` places its structure, a folder that
 * directly contains any `*.spec.kts` loads that folder as a project, and every other folder just
 * expands/collapses (which LazyTree already does on its own for nodes).
 *
 * Deliberately does **not** call `ExplorerTreeState.select(path)`: LazyTree has already written the
 * clicked element's id into `TreeState.selectedKeys` before invoking this callback, and Jewel's
 * TreeState is the declared single source of truth for selection. A second writer here would be a
 * silent no-op today and a divergence the moment the two disagree (multi-select, drag-select).
 */
private fun onElementClick(node: com.breadmoirai.garnet.project.FileTreeNode, path: String) {
    when (node) {
        is FileNode -> if (node.extension == "nbt") ClientPlayNetworking.send(PlaceStructureC2S(path))
        is FolderNode ->
            if (node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }) {
                ClientPlayNetworking.send(LoadProjectFolderC2S(path))
            }
    }
}

@Composable
private fun TreeRow(node: com.breadmoirai.garnet.project.FileTreeNode, path: String, currentSubpath: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (node) {
            is FolderNode -> Icon(AllIconsKeys.Nodes.Folder, contentDescription = null)
            is FileNode ->
                if (node.extension == "nbt") Icon(AllIconsKeys.FileTypes.Archive, contentDescription = null)
                else Icon(AllIconsKeys.FileTypes.Text, contentDescription = null)
        }
        val dirty = node is FileNode && node.hasUnsaved
        val current = path == currentSubpath
        val marker = if (dirty || current) "● " else ""
        Text("  $marker${node.name}")
    }
}

