package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import net.minecraft.server.MinecraftServer

/**
 * Per-server pin for the active managed root. Set from `SharedSettings.projectRootPath` at
 * server start (dedicated), or from the chosen root before the integrated server begins ticking
 * (singleplayer via the world-list "Project Specs…" flow, T20/T21).
 */
class EditorServerContext(val root: EditorRoot) {
    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, EditorServerContext>()
        @Synchronized fun set(server: MinecraftServer, ctx: EditorServerContext) { perServer[server] = ctx }
        @Synchronized fun get(server: MinecraftServer): EditorServerContext? = perServer[server]
        @Synchronized fun clear(server: MinecraftServer) { perServer.remove(server) }
    }
}
