package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.resolve
import com.breadmoirai.garnet.editor.data.walk
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text

/**
 * Which confirm/pick dialog the Explorer has open, if any, and where it should appear.
 *
 * Panel-scoped by construction (`remember`-ed in the panel, never a top-level object), for the same
 * reason as [ExplorerMenuState]: the dock composes into a long-lived singleton scene, so dialog
 * state held globally would survive a panel re-mount and repaint over the next one. See
 * DockState.mountEpoch and docs/ui/jewel-widget-layer.md.
 */
class ExplorerDialogState {
    sealed interface Pending {
        val path: String
        data class Delete(override val path: String) : Pending
        data class Move(override val path: String) : Pending
    }

    var pending: Pending? by mutableStateOf(null)
        private set
    var anchor: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    fun openDelete(path: String, at: IntOffset) { anchor = at; pending = Pending.Delete(path) }
    fun openMove(path: String, at: IntOffset) { anchor = at; pending = Pending.Move(path) }
    fun close() { pending = null }
}

/**
 * The Explorer's delete-confirmation and move-target dialogs.
 *
 * A single popup layer at a time, opened only AFTER the context menu has closed — which is what
 * keeps this clear of the nested-popup defect documented on [ExplorerContextMenu]: Jewel opens a
 * flyout as a second focusable layer, and the dock's `CanvasLayersComposeScene` stops routing
 * pointer events to every layer below the focused one. One layer, one set of live pointer targets.
 */
@Composable
fun ExplorerDialogs(
    state: ExplorerDialogState,
    onConfirmDelete: (path: String) -> Unit,
    onConfirmMove: (path: String, destFolder: String) -> Unit,
) {
    when (val pending = state.pending) {
        null -> Unit
        is ExplorerDialogState.Pending.Delete -> DeleteConfirmDialog(
            path = pending.path,
            anchor = state.anchor,
            onCancel = { state.close() },
            onConfirm = { state.close(); onConfirmDelete(pending.path) },
        )
        is ExplorerDialogState.Pending.Move -> MoveTargetDialog(
            path = pending.path,
            anchor = state.anchor,
            onCancel = { state.close() },
            onPick = { dest -> state.close(); onConfirmMove(pending.path, dest) },
        )
    }
}

/**
 * "Delete 'clock.nbt'?" — with a structure count when the target is a folder, because that is the
 * number the player is actually weighing. Confirm is the first row so the common case is the
 * shortest travel from the menu item that opened it.
 */
@Composable
private fun DeleteConfirmDialog(
    path: String,
    anchor: IntOffset,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val name = path.substringAfterLast('/')
    val count = structureCountUnder(path)
    val prompt = when {
        count == null -> "Delete '$name'?"
        count == 0 -> "Delete the empty folder '$name'?"
        count == 1 -> "Delete '$name' and the 1 structure inside it?"
        else -> "Delete '$name' and the $count structures inside it?"
    }
    PopupMenu(
        onDismissRequest = { onCancel(); true },
        popupPositionProvider = FixedOffsetPositionProvider(anchor),
    ) {
        selectableItem(selected = false, onClick = { onConfirm() }) { Text(prompt) }
        selectableItem(selected = false, onClick = { onCancel() }) { Text("Cancel") }
    }
}

/**
 * "Move to…" — one row per legal destination folder, in tree order, then Cancel.
 *
 * Illegal destinations are never offered rather than offered-then-refused: [moveDestinationsFor]
 * applies the same two exclusions `ExplorerActions.commitMove` enforces.
 */
@Composable
private fun MoveTargetDialog(
    path: String,
    anchor: IntOffset,
    onCancel: () -> Unit,
    onPick: (destFolder: String) -> Unit,
) {
    val destinations = moveDestinationsFor(path)
    PopupMenu(
        onDismissRequest = { onCancel(); true },
        popupPositionProvider = FixedOffsetPositionProvider(anchor),
    ) {
        for ((subpath, label) in destinations) {
            selectableItem(selected = false, onClick = { onPick(subpath) }) { Text(label) }
        }
        selectableItem(selected = false, onClick = { onCancel() }) { Text("Cancel") }
    }
}

/**
 * How many `.nbt` structures sit anywhere beneath [path], for the delete prompt. Null when [path] is
 * a file or does not resolve — those are named without a count, since the number the player cares
 * about is "how many structures am I about to lose", and for a single file that number is obvious.
 *
 * Counts structures rather than all nodes on purpose: intermediate folders are not what anyone is
 * afraid of losing.
 */
internal fun structureCountUnder(path: String): Int? {
    val root = ProjectTreeState.snapshot?.root ?: return null
    val node = root.resolve(path) as? FolderNode ?: return null
    return node.walk().count { (_, child) -> child is FileNode && child.extension == "nbt" }
}

/**
 * Every folder in the project that [movedPath] could legally move into, as `subpath to label` in
 * tree order. The project root is always first, as `"" to "<root name>"`.
 *
 * Excludes the moved node's own subtree (moving a folder inside itself) and the folder it already
 * lives in — the same two rules `ExplorerActions.commitMove` enforces, applied here so an illegal
 * destination is never offered rather than being offered and then refused.
 */
internal fun moveDestinationsFor(movedPath: String): List<Pair<String, String>> {
    val root = ProjectTreeState.snapshot?.root ?: return emptyList()
    val currentParent = movedPath.substringBeforeLast('/', "")
    return root.walk()
        .filter { (_, node) -> node is FolderNode }
        .filterNot { (subpath, _) -> subpath == movedPath || subpath.startsWith("$movedPath/") }
        .filterNot { (subpath, _) -> subpath == currentParent }
        .map { (subpath, node) -> subpath to (if (subpath.isEmpty()) node.name else subpath) }
        .toList()
}
