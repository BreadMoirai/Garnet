package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.testing.data.LoadedSpec
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-wide state for the active managed-root: every leaf folder's loaded specs + their
 * cell snapshots. Created by [EditorDimLifecycle.placeAll] on server start; persists for the
 * server's lifetime.
 *
 * Per-folder: `Map<spec-id, LoadedSpec>`. [LoadedSpec] is unchanged — still carries the
 * region-relative cell origin, source file path, and loaded snapshot.
 */
class EditorWorld(
    val root: EditorRoot,
    val folderAbsoluteByPath: MutableMap<String, Path> = ConcurrentHashMap(),
    val perFolder: MutableMap<String, MutableMap<String, LoadedSpec>> = ConcurrentHashMap(),
) {
    fun loadedSpec(subpath: String, specId: String): LoadedSpec? =
        perFolder[subpath]?.get(specId)

    fun forEachSpec(block: (subpath: String, loaded: LoadedSpec) -> Unit) {
        for ((subpath, map) in perFolder) {
            for (loaded in map.values) block(subpath, loaded)
        }
    }

    /**
     * Absolute world origin for a given spec's cell. `cell.origin` Y is already absolute
     * (GridLayout uses `yBase` directly); X/Z are region-relative and need the region origin
     * added in.
     */
    fun absoluteCellOrigin(server: MinecraftServer, subpath: String, specId: String): BlockPos? {
        val loaded = perFolder[subpath]?.get(specId) ?: return null
        val region = EditorDimRegistry.of(server).regionOriginOf(subpath) ?: return null
        val rel = loaded.cell.origin
        return BlockPos(region.x + rel.x, rel.y, region.z + rel.z)
    }

    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, EditorWorld>()
        @Synchronized fun set(server: MinecraftServer, world: EditorWorld) { perServer[server] = world }
        @Synchronized fun get(server: MinecraftServer): EditorWorld? = perServer[server]
        @Synchronized fun clear(server: MinecraftServer) { perServer.remove(server) }
    }
}
