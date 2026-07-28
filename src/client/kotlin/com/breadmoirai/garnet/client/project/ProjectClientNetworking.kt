package com.breadmoirai.garnet.client.project

import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.network.project.ProjectErrorS2C
import com.breadmoirai.garnet.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.garnet.network.project.ProjectSaveReportS2C
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.StructureResultS2C
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
        ClientPlayNetworking.registerGlobalReceiver(StructureResultS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onStructureResult(payload) }
        }
    }
}
