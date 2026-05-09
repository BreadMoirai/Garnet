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
 * One per `MinecraftServer`. Owns folder → region-origin assignment within the overworld
 * canvas: counter-based, in-memory, ephemeral (resets on server restart).
 *
 * The managed canvas is the integrated server's overworld; no custom dim is registered.
 */
class ManagedDimRegistry(private val server: MinecraftServer) {
    private data class Entry(
        val subpath: String,
        val regionIndex: Int,
        val regionOrigin: BlockPos,
    )

    private val bySubpath = ConcurrentHashMap<String, Entry>()
    private val nextRegionIndex = AtomicInteger(0)

    /** The managed canvas is the server's overworld. */
    fun managedLevel(): ServerLevel = server.overworld()

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
