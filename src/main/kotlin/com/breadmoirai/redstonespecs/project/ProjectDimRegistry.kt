package com.breadmoirai.redstonespecs.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/** A structure's placed footprint in the world: absolute [origin] and [size]. */
data class PlacedBox(val origin: BlockPos, val size: Vec3i)

/**
 * One per `MinecraftServer`. Owns folder → region-origin assignment within the overworld
 * canvas: counter-based, in-memory, ephemeral (resets on server restart).
 *
 * The managed canvas is the integrated server's overworld; no custom dim is registered.
 */
class ProjectDimRegistry(private val server: MinecraftServer) {
    private data class Entry(
        val subpath: String,
        val regionIndex: Int,
        val regionOrigin: BlockPos,
    )

    private val bySubpath = ConcurrentHashMap<String, Entry>()
    private val nextRegionIndex = AtomicInteger(0)
    private val structureBySubpath = ConcurrentHashMap<String, BlockPos>()
    private val nextStructureIndex = AtomicInteger(0)
    private val placedBoxes = ConcurrentHashMap<String, PlacedBox>()

    /** The managed canvas is the server's overworld. */
    fun projectLevel(): ServerLevel = server.overworld()

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
        LOGGER.info("[ProjectDimRegistry] assigned region #{} at {} to '{}'", idx, origin, subpath)
        return origin
    }

    fun regionOriginOf(subpath: String): BlockPos? = bySubpath[subpath]?.regionOrigin

    private fun computeRegionOrigin(idx: Int): BlockPos {
        // Region span = cellSize.x * rowMax + cellGap * (rowMax + 1) + REGION_PAD
        // Match GridLayout's spacing (Task 3) so regions never collide with each other.
        val cs = SharedSettings.projectCellSize
        val gap = SharedSettings.projectCellGap
        val rowMax = SharedSettings.projectRowMax
        val regionWidth = cs.x * rowMax + gap * (rowMax + 1) + REGION_PAD
        // Lay regions along +X. Y_BASE is the grid's Y; Z is 0.
        return BlockPos(idx * regionWidth, SharedSettings.projectGridYBase, 0)
    }

    /**
     * Region origin (x,z corner; y == grid base) for a standalone structure [subpath], assigned
     * on first use. Structures occupy their own +X lane at z == [STRUCTURE_LANE_Z], disjoint from
     * spec-folder regions, so the two never collide.
     */
    fun getOrAssignStructureRegion(subpath: String): BlockPos {
        structureBySubpath[subpath]?.let { return it }
        val idx = nextStructureIndex.getAndIncrement()
        val width = SharedSettings.structureRegionChunks * 16
        val origin = BlockPos(idx * (width + REGION_PAD), SharedSettings.projectGridYBase, STRUCTURE_LANE_Z)
        structureBySubpath[subpath] = origin
        LOGGER.info("[ProjectDimRegistry] assigned structure region #{} at {} to '{}'", idx, origin, subpath)
        return origin
    }

    fun structureRegionOriginOf(subpath: String): BlockPos? = structureBySubpath[subpath]

    fun placedBoxOf(subpath: String): PlacedBox? = placedBoxes[subpath]

    fun setPlacedBox(subpath: String, box: PlacedBox) { placedBoxes[subpath] = box }

    companion object {
        const val REGION_PAD = 64  // empty void between adjacent regions

        /** Z coordinate of the standalone-structure region lane (far from the spec lane at z=0). */
        const val STRUCTURE_LANE_Z = 4096

        private val perServer = java.util.WeakHashMap<MinecraftServer, ProjectDimRegistry>()

        @Synchronized fun of(server: MinecraftServer): ProjectDimRegistry =
            perServer.getOrPut(server) { ProjectDimRegistry(server) }

        @Synchronized fun dispose(server: MinecraftServer) {
            perServer.remove(server)
        }
    }
}
