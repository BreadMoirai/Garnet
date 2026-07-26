package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.breadmoirai.redstonespecs.client.ui.compose.dock.Panel
import com.breadmoirai.redstonespecs.network.project.ListProjectTreeC2S
import com.breadmoirai.redstonespecs.network.project.LoadProjectFolderC2S
import com.breadmoirai.redstonespecs.project.FileNode
import com.breadmoirai.redstonespecs.project.FileTreeNode
import com.breadmoirai.redstonespecs.project.FolderNode
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

private val TEXT = Color(0xFFDDE3EC)
private val TEXT_DIM = Color(0xFF8FA0B5)
private val SELECTED_BG = Color(0x334A90E2)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("redstonespecs.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Row2("↻ Refresh", TEXT_DIM) { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }
        val snap = ProjectTreeState.snapshot
        if (snap == null) {
            Row2("(no project loaded — Refresh)", TEXT_DIM) {}
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
            val base = Modifier.fillMaxWidth().clickable { ProjectTreeState.select(path) }
            val rowMod = if (isSelected) base.background(SELECTED_BG) else base
            Row(rowMod.padding(vertical = 2.dp)) {
                Spacer(Modifier.width(indent))
                BasicText(node.name, style = TextStyle(color = if (isSelected) TEXT else TEXT_DIM))
            }
        }
    }
}

@Composable
private fun Row2(label: String, color: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp)) {
        BasicText(label, style = TextStyle(color = color))
    }
