package com.breadmoirai.garnet.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.editor.explorer.ui.formatClock
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.Panel
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Matches the Explorer and Local History panel backgrounds. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Structure Info panel for the LEFT stripe. */
fun structureInfoPanel(): Panel = Panel(
    "garnet.structureInfo", "Structure Info", DockRegion.LEFT, AllIconsKeys.General.Information,
) { StructureInfo() }

/**
 * The open structure's facts, plus the editor's transient status line.
 *
 * No state of its own: everything comes from [StructureInfoState], fed by network receivers.
 * Anything panel-local would have to be `remember`-ed *here* rather than parked in a top-level
 * object — the dock composes into a long-lived singleton scene, so global panel state survives a
 * re-mount and paints over the next one (see `DockState.mountEpoch`).
 *
 * No glyphs anywhere, and `x` between the dimensions rather than U+00D7: Jewel's default family is
 * Inter, which has no emoji coverage, so anything outside its coverage falls through to whatever
 * Skia finds on the host and renders as tofu. Same rule as the Local History panel.
 *
 * Unknown fields are omitted rather than rendered as their sentinel. A structure that has been
 * placed but not yet auto-saved genuinely has no block count and no save time; showing `-1` or a
 * 1970 date would be worse than showing nothing.
 */
@Composable
private fun StructureInfo() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            val subpath = StructureInfoState.subpath
            if (subpath == null) {
                Text("no structure open")
            } else {
                Text(subpath)
                Spacer(Modifier.height(6.dp))
                InfoRow("Size", "${StructureInfoState.sizeX} x ${StructureInfoState.sizeY} x ${StructureInfoState.sizeZ}")
                if (StructureInfoState.blockCount >= 0) {
                    InfoRow("Blocks", StructureInfoState.blockCount.toString())
                }
                if (StructureInfoState.lastSavedMillis > 0L) {
                    InfoRow("Saved", formatClock(StructureInfoState.lastSavedMillis))
                }
            }
            val status = StructureInfoState.status
            if (status.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(status)
            }
        }
    }
}

/** One `label   value` line. The fixed label column keeps the values aligned down the panel. */
@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, Modifier.width(56.dp))
        Text(value)
    }
}
