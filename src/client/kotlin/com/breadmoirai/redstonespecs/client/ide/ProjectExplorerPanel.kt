package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

private val TEXT = Color(0xFFDDE3EC)
private val TEXT_DIM = Color(0xFF8FA0B5)

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
                snap.intermediates.sorted().forEach { dir -> Row2("▸ $dir", TEXT_DIM) { ProjectTreeState.toggleExpanded(dir) } }
                snap.leaves.sortedBy { it.subpath }.forEach { leaf ->
                    val marker = if (leaf.subpath == snap.currentSubpath) "● " else "  "
                    Row2("$marker${leaf.subpath}  (${leaf.specCount})", TEXT) {
                        ClientPlayNetworking.send(LoadProjectFolderC2S(leaf.subpath))
                    }
                }
            }
        }
        val status = ProjectTreeState.status
        if (status.isNotEmpty()) BasicText(status, Modifier.padding(top = 4.dp), style = TextStyle(color = TEXT_DIM))
    }
}

@Composable
private fun Row2(label: String, color: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp)) {
        BasicText(label, style = TextStyle(color = color))
    }
