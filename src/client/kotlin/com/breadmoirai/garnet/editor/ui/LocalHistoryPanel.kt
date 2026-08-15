package com.breadmoirai.garnet.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.RestoreRevisionC2S
import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.WatchStructureHistoryC2S
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.Panel
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Matches the Explorer panel's background. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Local History tab for DockState.leftPanels. */
fun localHistoryPanel(): Panel = Panel(
    "garnet.localHistory", "Local History", DockRegion.LEFT, AllIconsKeys.Vcs.History,
) { LocalHistory() }

/**
 * The Local History panel body.
 *
 * No state of its own: everything it shows comes from [OpenStructureState] and [LocalHistoryState],
 * both fed by network receivers. Anything panel-local would have to be `remember`-ed *here* rather
 * than parked in a top-level object — the dock composes into a long-lived singleton scene, so global
 * panel state survives a re-mount and paints over the next one (see `DockState.mountEpoch`).
 *
 * No glyphs mark any state anywhere in this panel. Jewel's default family is Inter, which has no
 * emoji coverage, so a marker glyph falls through to whatever Skia finds on the host and renders as
 * tofu wherever no system emoji font is installed. Status is carried by colour and by whether a row
 * responds to a click at all.
 */
@Composable
private fun LocalHistory() {
    IntUiTheme(isDark = true) {
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(4.dp)) {
            val open = OpenStructureState.subpath
            // Tell the server what we are looking at whenever that changes — including the empty
            // "stop watching" case, so it is not still pushing lists for a structure we closed.
            // Guarded by canSend: a vanilla server (or, in the client harness, no connection at all)
            // has no receiver for this payload and a bare send throws — here that would throw out of
            // a LaunchedEffect and take the whole composition down with it.
            LaunchedEffect(open) {
                if (ClientPlayNetworking.canSend(WatchStructureHistoryC2S.TYPE)) {
                    ClientPlayNetworking.send(WatchStructureHistoryC2S(open.orEmpty()))
                }
            }
            when {
                open == null ->
                    Text("(no structure open - place one from the Explorer)")
                !SharedSettings.localHistoryEnabled ->
                    // Distinct from an empty list, which would claim this structure has no history.
                    Text("(local history is disabled in settings)")
                // The list is only shown when it is known to belong to the open structure. Between
                // placing a structure and its history push arriving, LocalHistoryState still holds
                // the PREVIOUS structure's revisions, and rendering those under this structure's name
                // would offer a Restore that silently targets the wrong build.
                LocalHistoryState.subpath != open || LocalHistoryState.revisions.isEmpty() ->
                    Text("(no revisions yet for $open)")
                else -> RevisionList()
            }
        }
    }
}

/** ColumnScope extension so the list can take [Modifier.weight] and leave room for Restore below. */
@Composable
private fun ColumnScope.RevisionList() {
    Text(LocalHistoryState.subpath.orEmpty(), Modifier.padding(bottom = 4.dp))
    LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
        items(LocalHistoryState.revisions) { revision ->
            RevisionRow(revision)
        }
    }
    val selected = LocalHistoryState.selected
    val subpath = LocalHistoryState.subpath
    DefaultButton(
        onClick = {
            // Both read again inside the handler rather than trusting the captured pair: a history
            // push can land between composition and click. Nulls are dropped instead of asserted —
            // a race must not crash the client.
            val s = LocalHistoryState.subpath
            val t = LocalHistoryState.selected
            if (s != null && t != null && ClientPlayNetworking.canSend(RestoreRevisionC2S.TYPE)) {
                ClientPlayNetworking.send(RestoreRevisionC2S(s, t))
            }
        },
        enabled = selected != null && subpath != null,
        modifier = Modifier.padding(top = 4.dp),
    ) {
        Text("Restore")
    }
}

/**
 * One revision row.
 *
 * The current (newest) revision is rendered in the disabled foreground and carries NO click
 * handler — it is what is already on disk, so restoring it would do nothing. That is not
 * colour-only signalling: the row genuinely does not respond, which is the honest rendering of an
 * inert row.
 */
@Composable
private fun RevisionRow(revision: RevisionEntry) {
    val restorable = LocalHistoryState.isRestorable(revision.timestampMillis)
    val isSelected = LocalHistoryState.selected == revision.timestampMillis
    val rowModifier = Modifier
        .fillMaxWidth()
        .background(if (isSelected) Color(0xFF2E436E) else Color.Transparent)
        .padding(horizontal = 4.dp, vertical = 2.dp)
        .let {
            if (restorable) it.pointerInput(revision.timestampMillis) {
                detectTapGestures { LocalHistoryState.select(revision.timestampMillis) }
            } else it
        }
    Row(rowModifier) {
        Text(formatTime(revision.timestampMillis), Modifier.weight(1f), color = fg(restorable))
        // Plain ASCII "x" between the dimensions, not U+00D7: see the panel doc on glyph coverage.
        Text("${revision.sizeX}x${revision.sizeY}x${revision.sizeZ}", Modifier.weight(1f), color = fg(restorable))
        Text(revision.reason, Modifier.weight(1f), color = muted(restorable))
    }
}

private fun fg(restorable: Boolean) = if (restorable) Color(0xFFDFE1E5) else Color(0xFF6F737A)
private fun muted(restorable: Boolean) = if (restorable) Color(0xFF8B8F96) else Color(0xFF6F737A)

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
private fun formatTime(millis: Long): String = TIME_FORMAT.format(Date(millis))
