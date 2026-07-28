package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.network.project.DiscardStructureC2S
import com.breadmoirai.garnet.network.project.ListProjectTreeC2S
import com.breadmoirai.garnet.network.project.LoadProjectFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.network.project.SaveStructureC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.DefaultSlimButton
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.OutlinedSlimButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import org.jetbrains.jewel.ui.component.separator
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Panel background, matching the IntelliJ dark tool-window colour. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("garnet.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            Header()
            StructureActions()
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

@Composable
private fun Header() {
    val rootName = ProjectTreeState.snapshot?.root?.name ?: "(no root)"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        // Real Jewel Dropdown. This replaces the hand-rolled RootMenu overlay, which only existed
        // because Compose Popups were believed unable to render in the embedded scene.
        Dropdown(
            menuContent = {
                selectableItem(selected = false, onClick = { RootPickerController.openFolder() }) {
                    Text("Open Folder")
                }
                separator()
                selectableItem(selected = false, enabled = false, onClick = {}) {
                    Text("Attach Folder  (soon)")
                }
            },
        ) {
            Text(rootName)
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }) {
            Icon(AllIconsKeys.Actions.Refresh, contentDescription = "Refresh")
        }
    }
}

@Composable
private fun StructureActions() {
    val newName = rememberTextFieldState()
    val selected = ExplorerTreeState.selectedPath
    val isStructure = selected != null && selected.endsWith(".nbt")
    // All four controls must stay reachable at the dock's typical 300px LEFT-panel width without
    // shrinking the tree area or scrolling this row — slim button variants (Jewel's
    // DefaultSlimButton/OutlinedSlimButton, a narrower min-height/padding than the default
    // buttons used elsewhere in this panel), a narrower name field, a shorter "+ New" label, and a
    // fixed (not flex) inter-button gap buy back enough width for Save AND Discard to render fully
    // on-canvas, label included. A flex Spacer(weight = 1f) here would still overflow the row when
    // the fixed-width children alone exceed 300px, since it can only shrink to zero, never negative
    // — packing everything left-aligned with a small fixed gap is what actually guarantees no
    // clipping. Verified visually via the client-test screenshots (see task-5-report.md), not just
    // by compiling.
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            state = newName,
            modifier = Modifier.width(48.dp),
        )
        DefaultSlimButton(
            onClick = {
                val name = newName.text.toString()
                if (name.isNotBlank()) {
                    ClientPlayNetworking.send(NewStructureC2S(name))
                    newName.clearText()
                }
            },
            modifier = Modifier.padding(horizontal = 2.dp),
        ) { Text("+ New") }
        Spacer(Modifier.width(4.dp))
        OutlinedSlimButton(
            onClick = { if (isStructure) ClientPlayNetworking.send(SaveStructureC2S(selected!!)) },
            enabled = isStructure,
            modifier = Modifier.padding(horizontal = 1.dp),
        ) { Text("Save") }
        OutlinedSlimButton(
            onClick = { if (isStructure) ClientPlayNetworking.send(DiscardStructureC2S(selected!!)) },
            enabled = isStructure && ExplorerTreeState.selectedHasUnsaved(),
            modifier = Modifier.padding(horizontal = 1.dp),
        ) { Text("Discard") }
    }
}
