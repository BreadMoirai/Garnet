package com.breadmoirai.redstonespecs.managed

import net.minecraft.server.MinecraftServer

/**
 * Per-server pin for the active managed root. Set from `SharedSettings.managedRootPath` at
 * server start (dedicated), or from the chosen root before the integrated server begins ticking
 * (singleplayer via the world-list "Managed Specs…" flow, T20/T21).
 */
class ManagedServerContext(val root: ManagedRoot) {
    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, ManagedServerContext>()
        @Synchronized fun set(server: MinecraftServer, ctx: ManagedServerContext) { perServer[server] = ctx }
        @Synchronized fun get(server: MinecraftServer): ManagedServerContext? = perServer[server]
        @Synchronized fun clear(server: MinecraftServer) { perServer.remove(server) }
    }
}
