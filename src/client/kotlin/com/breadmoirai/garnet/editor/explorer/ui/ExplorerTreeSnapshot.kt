package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C

/**
 * Client-side, Compose-observable state for the Project Explorer: the server's tree snapshot. The
 * networking layer mutates it from the client thread; [ProjectExplorerPanel] reads it during
 * composition and recomposes on change.
 *
 * The status line used to live here too. It moved wholesale to [StructureInfoState], which holds the
 * open structure's facts as fields rather than as a formatted sentence.
 *
 * Tree *interaction* state (expansion, selection) deliberately lives in [ExplorerTreeState], owned by
 * Jewel's TreeState, so there is exactly one copy of it.
 */
object ExplorerTreeSnapshot {
    var snapshot by mutableStateOf<EditorTreeSnapshotS2C?>(null)
        private set

    fun onSnapshot(s: EditorTreeSnapshotS2C) { snapshot = s }

    /** Test/reset hook: clears the snapshot back to its initial value. */
    fun reset() { snapshot = null }
}
