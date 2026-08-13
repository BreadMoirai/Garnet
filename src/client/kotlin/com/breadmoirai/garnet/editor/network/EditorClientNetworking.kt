package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.UndoState
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorSaveReportS2C
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object EditorClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(EditorTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                ProjectTreeState.onSnapshot(payload)
                // Driven from here rather than from inside onSnapshot so ProjectTreeState (tree
                // data) and ExplorerTreeState (tree interaction state) stay passive siblings that
                // do not reach into each other — the separation both their docstrings assert.
                ExplorerTreeState.applyPendingRestore(payload.root)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onFolderLoaded(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onSaveReport(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(EditorErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onError(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(StructureResultS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onStructureResult(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(StructureAutoSavedS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onAutoSaved(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(UndoStateS2C.TYPE) { payload, ctx ->
            ctx.client().execute { UndoState.onUndoState(payload) }
        }
    }
}
