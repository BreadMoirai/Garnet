package com.breadmoirai.redstonespecs.client.project

import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

object ProjectClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ProjectTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ProjectScreen)?.onTreeSnapshot(payload)
                    ?: run {
                        // No managed screen open — open one with this snapshot.
                        Minecraft.getInstance().setScreen(ProjectScreen(payload))
                    }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ProjectScreen)?.onFolderLoaded(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ProjectScreen)?.onSaveReport(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ProjectScreen)?.onError(payload)
            }
        }
    }
}
