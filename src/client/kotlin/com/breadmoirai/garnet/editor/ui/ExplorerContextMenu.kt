package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.breadmoirai.garnet.editor.data.NewNodeKind
import com.breadmoirai.garnet.editor.data.resolve
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator

/**
 * Which node was right-clicked and where the menu should appear.
 *
 * Panel-scoped by construction (`remember`-ed in the panel, never a top-level object): a popup layer
 * belongs to the composition that opened it, and the dock composes into a long-lived singleton
 * scene, so a menu held in global state would survive a panel re-mount and repaint over the next
 * one. See DockState.mountEpoch and docs/ui/jewel-widget-layer.md.
 */
class ExplorerMenuState {
    var target: String? by mutableStateOf(null)
        private set
    var anchor: IntOffset by mutableStateOf(IntOffset.Zero)
        private set

    fun open(path: String, offset: IntOffset) {
        target = path
        anchor = offset
    }

    fun close() {
        target = null
    }
}

/**
 * The `New Folder` / `New Structure` / `Rename` / `Duplicate` / `Move to…` / `Delete` menu, anchored
 * at the click point.
 *
 * The `New` actions target the clicked folder, or a clicked file's parent folder — the IDE
 * convention. Every other action targets the clicked node itself and is disabled on the project
 * root, which has no parent to be renamed, duplicated, moved, or deleted within.
 *
 * `Delete` and `Move to…` open a dialog rather than acting immediately; both do so only after this
 * menu has closed, keeping each dialog a single popup layer. See [ExplorerDialogs].
 *
 * **The two `New` actions are deliberately flat, not a `New ▸ (Folder | Structure)` submenu.** Jewel's
 * `submenu { }` opens its flyout as a second, `focusable = true` popup layer, and the dock composes
 * into an [androidx.compose.ui.ImageComposeScene], i.e. a `CanvasLayersComposeScene`. That scene
 * routes pointer input through an `isInteractive(owner)` check which returns **false for every layer
 * below the focused one** — so the instant the flyout opens, the parent menu stops receiving pointer
 * events entirely. Jewel deselects a submenu row from the *sibling* row's hover
 * (`LaunchedEffect(isHovered) { deselectSubmenu() }`), and that hover never arrives: `New` keeps its
 * selection highlight, `Rename` never highlights, and a click on `Rename` only dismisses the flyout.
 * Nothing at this call site can fix that — `focusable = true` is hardcoded inside Jewel's `internal
 * fun Submenu`, and the layer-blocking is internal to the scene. Any nested popup in this dock has
 * the same defect; keep menus one level deep. See docs/ui/jewel-widget-layer.md.
 */
@Composable
fun ExplorerContextMenu(
    state: ExplorerMenuState,
    onNew: (parentPath: String, kind: NewNodeKind) -> Unit,
    onRename: (path: String) -> Unit,
    onDuplicate: (path: String) -> Unit,
    onDelete: (path: String) -> Unit,
    onMove: (path: String) -> Unit,
) {
    val target = state.target ?: return
    val parent = newTargetFolderFor(target)
    PopupMenu(
        onDismissRequest = { state.close(); true },
        popupPositionProvider = FixedOffsetPositionProvider(state.anchor),
    ) {
        selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.FOLDER) }) {
            Text("New Folder")
        }
        selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.STRUCTURE) }) {
            Text("New Structure")
        }
        separator()
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onRename(target) },
        ) {
            Text("Rename")
        }
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onDuplicate(target) },
        ) {
            Text("Duplicate")
        }
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onMove(target) },
        ) {
            Text("Move to…")
        }
        separator()
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onDelete(target) },
        ) {
            Text("Delete")
        }
    }
}

/**
 * The folder a `New` action on [target] should create into: [target] itself when it is a folder,
 * else its parent. Reads the live snapshot rather than guessing from the path shape, since a folder
 * name may legitimately contain a dot.
 */
private fun newTargetFolderFor(target: String): String {
    val root = ProjectTreeState.snapshot?.root ?: return ExplorerTreeState.ROOT_PATH
    val node = root.resolve(target)
    return if (node is com.breadmoirai.garnet.editor.data.FolderNode) target
    else target.substringBeforeLast('/', ExplorerTreeState.ROOT_PATH)
}

/**
 * Places the popup's top-left at a fixed window offset — the recorded right-click point.
 *
 * `internal` rather than private because [ExplorerDialogs] positions its dialogs at the same anchor
 * the menu used, so a dialog opens exactly where the item that triggered it was.
 */
internal class FixedOffsetPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // Clamp so a click near the right/bottom edge does not push the menu off-canvas; the dock
        // scene has no desktop window to overflow into.
        val x = offset.x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = offset.y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
