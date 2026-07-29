package com.breadmoirai.garnet.client.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.project.resolve
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
 * The `New ▸ (Folder | Structure)` / `Rename` menu, anchored at the click point.
 *
 * `New` targets the clicked folder, or a clicked file's parent folder — the IDE convention. `Rename`
 * targets the clicked node itself and is disabled on the project root, which has no parent to be
 * renamed within.
 */
@Composable
fun ExplorerContextMenu(
    state: ExplorerMenuState,
    onNew: (parentPath: String, kind: NewNodeKind) -> Unit,
    onRename: (path: String) -> Unit,
) {
    val target = state.target ?: return
    val parent = newTargetFolderFor(target)
    PopupMenu(
        onDismissRequest = { state.close(); true },
        popupPositionProvider = FixedOffsetPositionProvider(state.anchor),
    ) {
        submenu(submenu = {
            selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.FOLDER) }) {
                Text("Folder")
            }
            selectableItem(selected = false, onClick = { state.close(); onNew(parent, NewNodeKind.STRUCTURE) }) {
                Text("Structure")
            }
        }) {
            Text("New")
        }
        separator()
        selectableItem(
            selected = false,
            enabled = target != ExplorerTreeState.ROOT_PATH,
            onClick = { state.close(); onRename(target) },
        ) {
            Text("Rename")
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
    return if (node is com.breadmoirai.garnet.project.FolderNode) target
    else target.substringBeforeLast('/', ExplorerTreeState.ROOT_PATH)
}

/** Places the popup's top-left at a fixed window offset — the recorded right-click point. */
private class FixedOffsetPositionProvider(private val offset: IntOffset) : PopupPositionProvider {
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
