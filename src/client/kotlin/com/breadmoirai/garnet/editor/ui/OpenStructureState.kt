package com.breadmoirai.garnet.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.network.StructureResultS2C

/**
 * Which structure is currently placed in the editor world, client-side.
 *
 * [ProjectTreeState] records only the status *message* from a `StructureResultS2C`, which is not
 * something anything can key off. The Local History panel needs the subpath itself: it only ever
 * shows a structure that is actually in the world.
 */
object OpenStructureState {
    var subpath by mutableStateOf<String?>(null)
        private set

    fun onStructureResult(r: StructureResultS2C) { subpath = r.subpath }

    /** Test/reset hook; also called on disconnect — a placed structure does not survive a world. */
    fun reset() { subpath = null }
}
