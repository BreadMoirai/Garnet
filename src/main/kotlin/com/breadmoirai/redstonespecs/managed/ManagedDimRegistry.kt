package com.breadmoirai.redstonespecs.managed

import com.breadmoirai.redstonespecs.config.SharedSettings
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/**
 * One per `MinecraftServer`. Owns:
 *   1. Folder → region-origin assignment within the single managed dim. Counter-based,
 *      in-memory, ephemeral (resets on server restart).
 *   2. Per-folder cell map: `Map<BlockPos, spec-id>` for cell-membership lookup.
 *
 * No dynamic level registration — the managed dim is statically registered via data-pack JSON.
 */
class ManagedDimRegistry(private val server: MinecraftServer) {
    private data class Entry(
        val subpath: String,
        val regionIndex: Int,
        val regionOrigin: BlockPos,
        val cellsByOrigin: MutableMap<BlockPos, String> = mutableMapOf(),
    )

    private val bySubpath = ConcurrentHashMap<String, Entry>()
    private val nextRegionIndex = AtomicInteger(0)

    /** Returns the static managed `ServerLevel`, or null if the server hasn't bootstrapped it. */
    fun managedLevel(): ServerLevel? = server.getLevel(ManagedDimensions.MANAGED_LEVEL_KEY)

    /**
     * Returns the per-folder ServerLevel if the runtime datapack registered it; otherwise null.
     * Caller falls back to `managedLevel()` + `getOrAssignRegion(subpath)` for the single-dim path.
     */
    fun perFolderLevel(subpath: String): ServerLevel? {
        val sanitized = DimIdSanitizer.toPath(subpath)
        val key = ManagedDimensions.levelKey(sanitized)
        return server.getLevel(key)
    }

    /**
     * Returns the region origin for `subpath`, assigning a fresh region if this is the first time.
     * Region width is sized to fit the largest possible folder grid plus padding, so regions never overlap.
     */
    fun getOrAssignRegion(subpath: String): BlockPos {
        val existing = bySubpath[subpath]
        if (existing != null) return existing.regionOrigin

        val idx = nextRegionIndex.getAndIncrement()
        val origin = computeRegionOrigin(idx)
        bySubpath[subpath] = Entry(subpath, idx, origin)
        LOGGER.info("[ManagedDimRegistry] assigned region #{} at {} to '{}'", idx, origin, subpath)
        return origin
    }

    fun setCellsForFolder(subpath: String, byOrigin: Map<BlockPos, String>) {
        val e = bySubpath[subpath] ?: return
        e.cellsByOrigin.clear()
        e.cellsByOrigin.putAll(byOrigin)
    }

    /** Reverse lookup: cell origin → spec id within `subpath`'s region. */
    fun specIdAt(subpath: String, cellOrigin: BlockPos): String? =
        bySubpath[subpath]?.cellsByOrigin?.get(cellOrigin)

    fun regionOriginOf(subpath: String): BlockPos? = bySubpath[subpath]?.regionOrigin

    private fun computeRegionOrigin(idx: Int): BlockPos {
        // Region span = cellSize.x * rowMax + cellGap * (rowMax + 1) + REGION_PAD
        // Match GridLayout's spacing (Task 3) so regions never collide with each other.
        val cs = SharedSettings.managedCellSize
        val gap = SharedSettings.managedCellGap
        val rowMax = SharedSettings.managedRowMax
        val regionWidth = cs.x * rowMax + gap * (rowMax + 1) + REGION_PAD
        // Lay regions along +X. Y_BASE is the grid's Y; Z is 0.
        return BlockPos(idx * regionWidth, SharedSettings.managedGridYBase, 0)
    }

    companion object {
        const val REGION_PAD = 64  // empty void between adjacent regions

        private val perServer = java.util.WeakHashMap<MinecraftServer, ManagedDimRegistry>()

        @Synchronized fun of(server: MinecraftServer): ManagedDimRegistry =
            perServer.getOrPut(server) { ManagedDimRegistry(server) }

        @Synchronized fun dispose(server: MinecraftServer) {
            perServer.remove(server)
        }
    }
}
