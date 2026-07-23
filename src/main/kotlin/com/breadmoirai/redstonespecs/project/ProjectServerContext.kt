package com.breadmoirai.redstonespecs.project

import net.minecraft.server.MinecraftServer

/**
 * Per-server pin for the active managed root. Set from `SharedSettings.projectRootPath` at
 * server start (dedicated), or from the chosen root before the integrated server begins ticking
 * (singleplayer via the world-list "Project Specs…" flow, T20/T21).
 */
class ProjectServerContext(val root: ProjectRoot) {
    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, ProjectServerContext>()
        @Synchronized fun set(server: MinecraftServer, ctx: ProjectServerContext) { perServer[server] = ctx }
        @Synchronized fun get(server: MinecraftServer): ProjectServerContext? = perServer[server]
        @Synchronized fun clear(server: MinecraftServer) { perServer.remove(server) }
    }
}
