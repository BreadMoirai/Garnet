package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C

/**
 * Client-side, Compose-observable state for the Project Explorer. The networking layer mutates it
 * from the client thread; [ProjectExplorerPanel] reads it during composition and recomposes on
 * change. Replaces the old ProjectScreen-as-state-holder model (hard-cut).
 */
object ProjectTreeState {
    var snapshot by mutableStateOf<ProjectTreeSnapshotS2C?>(null)
        private set
    var status by mutableStateOf("")
        private set
    /** Subpaths the user has expanded in the tree. */
    val expanded = androidx.compose.runtime.mutableStateListOf<String>()

    fun onSnapshot(s: ProjectTreeSnapshotS2C) { snapshot = s }

    fun onFolderLoaded(p: ProjectFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: ProjectSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }
    fun onError(e: ProjectErrorS2C) { status = "error: ${e.reason}" }

    fun toggleExpanded(subpath: String) {
        if (!expanded.remove(subpath)) expanded.add(subpath)
    }
}
