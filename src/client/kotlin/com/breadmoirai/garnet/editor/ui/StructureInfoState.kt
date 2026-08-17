package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C

/**
 * Client-side, Compose-observable facts about the open structure, plus the editor's transient status
 * line. Read by [structureInfoPanel]; written by the network receivers in `EditorClientNetworking`.
 *
 * Fields, not a pre-baked sentence. This replaced `ExplorerTreeSnapshot.status`, a single string that
 * five receivers wrote to — three with transient feedback and two with structure facts — so the
 * size and block count of the open structure were destroyed by the next unrelated error.
 *
 * The two sentinels exist because the two payloads carry different amounts. `StructureResultS2C`
 * has the sizes but no block count, and a freshly placed structure has not been auto-saved yet; the
 * panel omits those rows entirely rather than rendering `-1` or an epoch date.
 */
object StructureInfoState {
    /** Null when no structure is open. */
    var subpath by mutableStateOf<String?>(null)
        private set
    var sizeX by mutableIntStateOf(0)
        private set
    var sizeY by mutableIntStateOf(0)
        private set
    var sizeZ by mutableIntStateOf(0)
        private set

    /** -1 when not known yet — placed, but no auto-save report has arrived for it. */
    var blockCount by mutableIntStateOf(-1)
        private set

    /** 0 when never saved this session. The server's own write time, from the payload. */
    var lastSavedMillis by mutableLongStateOf(0L)
        private set

    /** The transient line: errors, load reports, save reports, and place messages. */
    var status by mutableStateOf("")
        private set

    fun onStructureResult(r: StructureResultS2C) {
        subpath = r.subpath
        sizeX = r.sizeX; sizeY = r.sizeY; sizeZ = r.sizeZ
        // A place carries no block count, and nothing has auto-saved this structure yet. Keeping the
        // PREVIOUS structure's numbers under the new one's name would be a lie, so both reset.
        blockCount = -1
        lastSavedMillis = 0L
        status = r.message
    }

    fun onAutoSaved(p: StructureAutoSavedS2C) {
        subpath = p.subpath
        sizeX = p.sizeX; sizeY = p.sizeY; sizeZ = p.sizeZ
        blockCount = p.blockCount
        lastSavedMillis = p.savedAtMillis
    }

    fun onFolderLoaded(p: EditorFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: EditorSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }

    fun onError(e: EditorErrorS2C) { status = "error: ${e.reason}" }

    /** Test/reset hook; also called on disconnect — a placed structure does not survive a world. */
    fun reset() {
        subpath = null
        sizeX = 0; sizeY = 0; sizeZ = 0
        blockCount = -1
        lastSavedMillis = 0L
        status = ""
    }
}
