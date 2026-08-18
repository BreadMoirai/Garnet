package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.editor.explorer.network.ListEditorTreeC2S
import com.breadmoirai.garnet.editor.undo.network.RedoC2S
import com.breadmoirai.garnet.editor.undo.network.UndoC2S
import com.breadmoirai.garnet.editor.undo.ui.UndoState
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.IconButton
import org.jetbrains.jewel.ui.component.PopupMenu
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * The Explorer's tool-window toolbar: a kebab overflow menu on the left, tree actions on the right.
 *
 * Replaces the old root-name `Dropdown` + `+ New`/`Save`/`Discard` rows. The root name is no longer
 * shown here — the tree's own root node carries it (see [ExplorerTreeState.buildTreeFrom]).
 */
@Composable
fun ExplorerToolbar() {
    Row(
        // The start inset lives here rather than on the panel Column: the tree below is full-bleed,
        // so the Column can't carry horizontal padding without putting the dead left margin back.
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KebabMenu()
        Spacer(Modifier.weight(1f))
        IconButton(
            onClick = { ClientPlayNetworking.send(UndoC2S.INSTANCE) },
            enabled = UndoState.undoLabel != null,
        ) {
            Icon(
                AllIconsKeys.Actions.Undo,
                contentDescription = UndoState.undoLabel?.let { "Undo $it" } ?: "Undo",
            )
        }
        IconButton(
            onClick = { ClientPlayNetworking.send(RedoC2S.INSTANCE) },
            enabled = UndoState.redoLabel != null,
        ) {
            Icon(
                AllIconsKeys.Actions.Redo,
                contentDescription = UndoState.redoLabel?.let { "Redo $it" } ?: "Redo",
            )
        }
        IconButton(onClick = { ClientPlayNetworking.send(ListEditorTreeC2S.INSTANCE) }) {
            Icon(AllIconsKeys.Actions.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = { ExplorerTreeState.collapseAll() }) {
            // Actions.Collapseall — Jewel's generated name lowercases the second "a". The key
            // resolves to expui/general/collapseAll.svg in the IntelliJ icons artifact.
            Icon(AllIconsKeys.Actions.Collapseall, contentDescription = "Collapse All")
        }
    }
}

@Composable
private fun KebabMenu() {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = !open }) {
        // Actions.More is the VERTICAL three-dot kebab (expui/general/moreVertical.svg).
        // Actions.MoreHorizontal is the horizontal variant — not this.
        Icon(AllIconsKeys.Actions.More, contentDescription = "Menu")
    }
    if (open) {
        PopupMenu(
            onDismissRequest = { open = false; true },
            horizontalAlignment = Alignment.Start,
        ) {
            selectableItem(selected = false, onClick = { open = false; RootPickerController.openFolder() }) {
                Text("Open Folder…")
            }
        }
    }
}
