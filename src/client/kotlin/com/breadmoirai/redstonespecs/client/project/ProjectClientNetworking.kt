package com.breadmoirai.redstonespecs.client.project

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object ProjectClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ProjectTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onSnapshot(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onFolderLoaded(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onSaveReport(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onError(payload) }
        }
    }
}
