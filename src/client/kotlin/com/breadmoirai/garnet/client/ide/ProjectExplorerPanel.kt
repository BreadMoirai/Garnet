package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.network.project.LoadProjectFolderC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
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
            var edit by remember { mutableStateOf<ExplorerEdit?>(null) }
            val snap = ProjectTreeState.snapshot
            if (snap == null) {
                Text("(no project loaded — Refresh)", Modifier.padding(vertical = 2.dp))
            } else {
                // The root node carries the project name and is the "create at root" target, so it
                // is useless collapsed. Opening it here — synchronously inside this remember block —
                // rather than via LaunchedEffect is load-bearing: Jewel's LazyTree computes its own
                // remembered flatten list on first composition and, as part of that, intersects
                // TreeState.openNodes down to only the ids reachable from an already-OPEN root. A
                // LaunchedEffect's coroutine body runs strictly after that first composition commits,
                // so opening the root there is one frame too late — any pre-existing expand state
                // (e.g. a caller that expanded "adders" before this panel ever mounted) gets pruned
                // away in that same first pass because the root itself wasn't open yet when Jewel
                // computed reachability. Doing it here, before `buildTreeFrom` even runs, guarantees
                // the root is open by the time LazyTree's internal prune executes. Keyed on the root so
                // a genuinely new project re-opens it, while a user who collapses it during a session
                // keeps it collapsed (LazyTree's prune only runs once per (tree, treeState) identity).
                // remember(snap.root, edit): buildTreeFrom walks the WHOLE project tree recursively
                // and allocates a fresh Tree, which LazyTree then has to re-flatten. This scope also
                // reads ProjectTreeState.status, which changes on every S2C packet, so an
                // un-remembered call rebuilds the entire tree on each packet. Keyed on the root so a
                // genuinely new snapshot still rebuilds, and on the edit so a pending create's
                // placeholder row appears and disappears.
                val tree = remember(snap.root, edit) {
                    ExplorerTreeState.treeState.openNodes += ExplorerTreeState.ROOT_PATH
                    ExplorerTreeState.buildTreeFrom(snap.root, edit)
                }
                LazyTree(
                    tree = tree,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    treeState = ExplorerTreeState.treeState,
                    onElementClick = { element -> onElementClick(element.data, ExplorerTreeState.pathOf(element)) },
                ) { element ->
                    TreeRow(
                        element.data,
                        ExplorerTreeState.pathOf(element),
                        snap.currentSubpath,
                        edit,
                        onCommit = { edit = null },
                        onCancel = { edit = null },
                    )
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
private fun TreeRow(
    node: com.breadmoirai.garnet.project.FileTreeNode,
    path: String,
    currentSubpath: String?,
    edit: ExplorerEdit?,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val creatingHere = edit is ExplorerEdit.Creating && ExplorerEdit.isPendingId(path)
    val renamingHere = edit is ExplorerEdit.Renaming && edit.path == path
    if (creatingHere || renamingHere) {
        val kindIcon = when {
            edit is ExplorerEdit.Creating && edit.kind == NewNodeKind.FOLDER -> AllIconsKeys.Nodes.Folder
            edit is ExplorerEdit.Creating -> AllIconsKeys.FileTypes.Archive
            node is FolderNode -> AllIconsKeys.Nodes.Folder
            else -> AllIconsKeys.FileTypes.Text
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon, contentDescription = null)
            InlineNameField(
                initial = (edit as? ExplorerEdit.Renaming)?.original.orEmpty(),
                onCommit = onCommit,
                onCancel = onCancel,
            )
        }
        return
    }
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

/**
 * The in-tree name field. Enter commits, Escape cancels, and losing focus cancels — an abandoned
 * field must never linger as a phantom row after the user clicks elsewhere in the tree.
 *
 * EditBox-style note does not apply here: this is a Jewel TextField over a Compose TextFieldState,
 * and `setTextAndPlaceCursorAtEnd` does not fire a responder, so seeding [initial] is safe.
 */
@Composable
private fun RowScope.InlineNameField(initial: String, onCommit: (String) -> Unit, onCancel: () -> Unit) {
    val state = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        state.setTextAndPlaceCursorAtEnd(initial)
        focusRequester.requestFocus()
    }
    TextField(
        state = state,
        modifier = Modifier
            .weight(1f)
            .focusRequester(focusRequester)
            .onFocusChanged { if (!it.isFocused) onCancel() }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> { onCommit(state.text.toString()); true }
                    Key.Escape -> { onCancel(); true }
                    else -> false
                }
            },
    )
}

