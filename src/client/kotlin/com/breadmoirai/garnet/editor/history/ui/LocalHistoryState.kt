package com.breadmoirai.garnet.editor.history.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C

/**
 * The Local History panel's list model.
 *
 * **Revisions are POST-commit snapshots**: the newest one is byte-identical to what is on disk right
 * now, so it is the "current" state and restoring it would be a no-op. It is kept in the list — a
 * timeline with a hole in it is worse — but is not selectable, and the panel renders it inert. The
 * server enforces the same rule; this is the half that stops the UI offering the action at all.
 *
 * Held newest-first, the reverse of the wire order, because that is display order.
 */
object LocalHistoryState {
    var subpath by mutableStateOf<String?>(null)
        private set
    var revisions by mutableStateOf<List<RevisionEntry>>(emptyList())
        private set
    var selected by mutableStateOf<Long?>(null)
        private set

    /** The newest revision's timestamp — what is on disk — or null when there is no history. */
    val currentTimestamp: Long? get() = revisions.firstOrNull()?.timestampMillis

    fun onHistory(p: StructureHistoryS2C) {
        subpath = p.subpath
        revisions = p.revisions.sortedByDescending { it.timestampMillis }
        // Drop a selection the new list no longer contains: a revision can be pruned between
        // pushes, and a Restore aimed at a gone timestamp would only earn a server refusal.
        if (selected != null && revisions.none { it.timestampMillis == selected }) selected = null
        if (selected == currentTimestamp) selected = null
    }

    fun isRestorable(timestampMillis: Long): Boolean =
        revisions.any { it.timestampMillis == timestampMillis } && timestampMillis != currentTimestamp

    fun select(timestampMillis: Long) {
        if (isRestorable(timestampMillis)) selected = timestampMillis
    }

    fun reset() {
        subpath = null
        revisions = emptyList()
        selected = null
    }
}
