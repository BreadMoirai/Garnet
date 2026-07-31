package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C

/**
 * Client-side, Compose-observable state for the Project Explorer: the server's tree snapshot and the
 * status line. The networking layer mutates it from the client thread; [ProjectExplorerPanel] reads
 * it during composition and recomposes on change.
 *
 * Tree *interaction* state (expansion, selection) deliberately lives in [ExplorerTreeState], owned by
 * Jewel's TreeState, so there is exactly one copy of it.
 */
object ProjectTreeState {
    var snapshot by mutableStateOf<EditorTreeSnapshotS2C?>(null)
        private set
    var status by mutableStateOf("")
        private set

    fun onSnapshot(s: EditorTreeSnapshotS2C) { snapshot = s }

    fun onFolderLoaded(p: EditorFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: EditorSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }
    fun onError(e: EditorErrorS2C) { status = "error: ${e.reason}" }
    fun onStructureResult(r: StructureResultS2C) { status = r.message }

    /** Test/reset hook: clears the snapshot and status back to initial values. */
    fun reset() {
        snapshot = null
        status = ""
    }
}
