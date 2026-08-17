package com.breadmoirai.garnet.editor.undo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.undo.network.UndoStateS2C

/**
 * Client mirror of the server's per-player undo/redo availability.
 *
 * The client never derives this itself — it has no stack. A null label means the corresponding
 * toolbar button is disabled.
 */
object UndoState {
    var undoLabel by mutableStateOf<String?>(null)
        private set
    var redoLabel by mutableStateOf<String?>(null)
        private set

    fun onUndoState(payload: UndoStateS2C) {
        undoLabel = payload.undoLabel
        redoLabel = payload.redoLabel
    }

    fun reset() {
        undoLabel = null
        redoLabel = null
    }
}
