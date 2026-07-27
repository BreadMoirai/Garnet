package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.breadmoirai.redstonespecs.client.ui.compose.dock.Panel
import com.breadmoirai.redstonespecs.network.project.DiscardStructureC2S
import com.breadmoirai.redstonespecs.network.project.ListProjectTreeC2S
import com.breadmoirai.redstonespecs.network.project.LoadProjectFolderC2S
import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
import com.breadmoirai.redstonespecs.project.FileNode
import com.breadmoirai.redstonespecs.project.FileTreeNode
import com.breadmoirai.redstonespecs.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

private val TEXT = Color(0xFFDDE3EC)
private val TEXT_DIM = Color(0xFF8FA0B5)
private val TEXT_DISABLED = Color(0xFF5A6678)
private val SELECTED_BG = Color(0x334A90E2)
private val MENU_BG = Color(0xF01A2130)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("redstonespecs.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(4.dp)) {
            Header()
            StructureActions()
            val snap = ProjectTreeState.snapshot
            if (snap == null) {
                BasicText("(no project loaded — Refresh)", Modifier.padding(vertical = 2.dp), style = TextStyle(color = TEXT_DIM))
            } else {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    snap.root.children.forEach { child ->
                        TreeNode(child, path = child.name, depth = 0, currentSubpath = snap.currentSubpath)
                    }
                }
            }
            val status = ProjectTreeState.status
            if (status.isNotEmpty()) BasicText(status, Modifier.padding(top = 4.dp), style = TextStyle(color = TEXT_DIM))
        }
        if (RootPickerController.menuOpen) RootMenu()
    }
}

@Composable
private fun Header() {
    val rootName = ProjectTreeState.snapshot?.root?.name ?: "(no root)"
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(Modifier.clickable { RootPickerController.toggleMenu() }.padding(end = 8.dp)) {
            BasicText("$rootName  ▾", style = TextStyle(color = TEXT))
        }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }) {
            BasicText("↻", style = TextStyle(color = TEXT_DIM))
        }
    }
}

@Composable
private fun StructureActions() {
    var newName by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(Modifier.width(90.dp).background(Color(0x22000000)).padding(horizontal = 4.dp, vertical = 2.dp)) {
            BasicTextField(
                value = newName, onValueChange = { newName = it },
                textStyle = TextStyle(color = TEXT), singleLine = true,
            )
        }
        Box(Modifier.clickable {
            if (newName.isNotBlank()) { ClientPlayNetworking.send(NewStructureC2S(newName)); newName = "" }
        }.padding(horizontal = 6.dp)) { BasicText("+ Structure", style = TextStyle(color = TEXT_DIM)) }
        Spacer(Modifier.weight(1f))
        Box(Modifier.clickable {
            val sel = ProjectTreeState.selectedPath
            if (sel != null && sel.endsWith(".nbt")) ClientPlayNetworking.send(SaveStructureC2S(sel))
        }.padding(horizontal = 6.dp)) { BasicText("Save Structure", style = TextStyle(color = TEXT_DIM)) }
        val selDirty = ProjectTreeState.selectedHasUnsaved()
        Box(Modifier.clickable {
            val sel = ProjectTreeState.selectedPath
            if (sel != null && sel.endsWith(".nbt")) ClientPlayNetworking.send(DiscardStructureC2S(sel))
        }.padding(horizontal = 6.dp)) {
            BasicText("Discard", style = TextStyle(color = if (selDirty) TEXT_DIM else TEXT_DISABLED))
        }
    }
}

@Composable
private fun RootMenu() {
    // Scrim (lower z): click outside closes the menu.
    Box(Modifier.fillMaxSize().clickable { RootPickerController.closeMenu() })
    // Menu card (higher z): offset to sit just under the option button.
    Column(Modifier.offset(x = 4.dp, y = 22.dp).background(MENU_BG).padding(4.dp)) {
        Box(Modifier.fillMaxWidth().clickable { RootPickerController.openFolder() }
            .padding(vertical = 3.dp, horizontal = 6.dp)) {
            BasicText("Open Folder", style = TextStyle(color = TEXT))
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 3.dp, horizontal = 6.dp)) {
            BasicText("Attach Folder  (soon)", style = TextStyle(color = TEXT_DISABLED))
        }
    }
}

@Composable
private fun TreeNode(node: FileTreeNode, path: String, depth: Int, currentSubpath: String?) {
    val indent = (depth * 12).dp
    when (node) {
        is FolderNode -> {
            val isExpanded = path in ProjectTreeState.expanded
            val hasChildren = node.children.isNotEmpty()
            val isSpecFolder = node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }
            val triangle = if (!hasChildren) "  " else if (isExpanded) "▾" else "▸"
            val marker = if (path == currentSubpath) "● " else ""
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                Box(Modifier.clickable(enabled = hasChildren) { ProjectTreeState.toggleExpanded(path) }) {
                    BasicText("$triangle ", style = TextStyle(color = TEXT_DIM))
                }
                Box(Modifier.fillMaxWidth().clickable {
                    if (isSpecFolder) ClientPlayNetworking.send(LoadProjectFolderC2S(path))
                    else ProjectTreeState.toggleExpanded(path)
                }) {
                    BasicText("$marker${node.name}", style = TextStyle(color = TEXT))
                }
            }
            if (isExpanded) {
                node.children.forEach { child ->
                    TreeNode(child, path = "$path/${child.name}", depth = depth + 1, currentSubpath = currentSubpath)
                }
            }
        }
        is FileNode -> {
            val isSelected = path == ProjectTreeState.selectedPath
            val isStructure = node.extension == "nbt"
            val onClick: () -> Unit = {
                ProjectTreeState.select(path)
                if (isStructure) ClientPlayNetworking.send(PlaceStructureC2S(path))
            }
            val base = Modifier.fillMaxWidth().clickable(onClick = onClick)
            val rowMod = if (isSelected) base.background(SELECTED_BG) else base
            Row(rowMod.padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                val dirtyMark = if (node.hasUnsaved) "● " else ""
                val label = if (isStructure) "$dirtyMark▶ ${node.name}" else node.name
                BasicText(label, style = TextStyle(color = if (isSelected) TEXT else TEXT_DIM))
            }
        }
    }
}
