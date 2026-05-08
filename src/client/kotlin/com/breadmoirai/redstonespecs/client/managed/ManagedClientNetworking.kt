package com.breadmoirai.redstonespecs.client.managed

import com.breadmoirai.redstonespecs.network.managed.ManagedErrorS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedSaveReportS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft

object ManagedClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ManagedTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onTreeSnapshot(payload)
                    ?: run {
                        // No managed screen open — open one with this snapshot.
                        Minecraft.getInstance().setScreen(ManagedScreen(payload))
                    }
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onFolderLoaded(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onSaveReport(payload)
            }
        }
        ClientPlayNetworking.registerGlobalReceiver(ManagedErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute {
                (Minecraft.getInstance().screen as? ManagedScreen)?.onError(payload)
            }
        }
    }
}
