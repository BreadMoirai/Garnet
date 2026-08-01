package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.structure.PlacedBox
import net.minecraft.core.BlockPos
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * One per `MinecraftServer`. Owns folder → region-origin assignment within the overworld
 * canvas: counter-based, in-memory, ephemeral (resets on server restart).
 *
 * The managed canvas is the integrated server's overworld; no custom dim is registered.
 */
class EditorDimRegistry(private val server: MinecraftServer) {
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
        LOGGER.info("[EditorDimRegistry] assigned region #{} at {} to '{}'", idx, origin, subpath)
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
        LOGGER.info("[EditorDimRegistry] assigned structure region #{} at {} to '{}'", idx, origin, subpath)
        return origin
    }

    fun structureRegionOriginOf(subpath: String): BlockPos? = structureBySubpath[subpath]

    /**
     * The structure whose assigned region contains [pos], or null. Regions span the full world
     * height, so only X/Z are tested. Called on the hot `setBlock` path (via
     * [StructureEditWatcher]), so the overwhelmingly common "not near any structure" answer must
     * cost as little as possible: [isInStructureLaneZ] rejects on two integer comparisons before
     * this method touches [structureBySubpath] at all, and the remaining (rare) lookup uses
     * `ConcurrentHashMap.forEach(BiConsumer)` rather than a `for ((k, v) in map)` loop, which
     * would allocate an `entries` iterator plus a boxed `Map.Entry` per step every single call.
     */
    fun structureSubpathAt(pos: BlockPos): String? {
        if (!isInStructureLaneZ(pos)) return null
        val width = SharedSettings.structureRegionChunks * 16
        var found: String? = null
        structureBySubpath.forEach { subpath, origin ->
            if (found == null && pos.x >= origin.x && pos.x < origin.x + width) {
                found = subpath
            }
        }
        return found
    }

    fun placedBoxOf(subpath: String): PlacedBox? = placedBoxes[subpath]

    fun setPlacedBox(subpath: String, box: PlacedBox) { placedBoxes[subpath] = box }

    /** Subpaths with a recorded placed box this session. */
    fun placedStructureSubpaths(): Set<String> = placedBoxes.keys.toSet()

    /**
     * Forget everything about a placed structure: its footprint record and its region assignment.
     * Returns the removed [PlacedBox] so the caller can clear those blocks, or null if it was not
     * placed.
     *
     * The freed region index is **not** recycled — [nextStructureIndex] is monotonic, so a structure
     * re-placed after this lands in a fresh region. That matches how every other assignment in this
     * registry behaves and keeps region identity from being reused while blocks may still linger.
     */
    fun unplaceStructure(subpath: String): PlacedBox? {
        structureBySubpath.remove(subpath)
        return placedBoxes.remove(subpath)
    }

    /**
     * Rewrite every registry entry keyed by [oldSubpath], or nested under it, onto [newSubpath] —
     * called after a rename's file move succeeds. `EditorDimRegistry` keys every map by full
     * subpath string, so renaming a folder without this strands every structure beneath it: its
     * placed-box and region-assignment entries stay keyed by the OLD path forever, which orphans
     * its in-world blocks from `StructureCommit` (whose `commit` resolves the subpath via
     * `EditorNetworking.rootFor(server).resolveSubpath(subpath)` and returns null, silently
     * skipping it) and lets a click on the new path re-place a second copy in a fresh region.
     *
     * The boundary is a full path segment, matching [repointSession]'s logic: renaming "redstone"
     * must rekey "redstone" and "redstone/clock.nbt", but never "redstoneworks/clocks" — a plain
     * `startsWith(oldSubpath)` would wrongly catch that sibling.
     *
     * Deliberately does NOT touch the world: only in-memory bookkeeping moves. A rekeyed structure's
     * blocks stay exactly where they were placed — the structure never moved in the world, only its
     * file path changed — so registry state and world state still agree once this returns.
     */
    fun rekeyForRename(oldSubpath: String, newSubpath: String) {
        fun rekeyedKey(subpath: String): String? = when {
            subpath == oldSubpath -> newSubpath
            subpath.startsWith("$oldSubpath/") -> newSubpath + subpath.removePrefix(oldSubpath)
            else -> null
        }

        for (key in bySubpath.keys.toList()) {
            val newKey = rekeyedKey(key) ?: continue
            val entry = bySubpath.remove(key) ?: continue
            bySubpath[newKey] = entry.copy(subpath = newKey)
        }
        for (key in structureBySubpath.keys.toList()) {
            val newKey = rekeyedKey(key) ?: continue
            val origin = structureBySubpath.remove(key) ?: continue
            structureBySubpath[newKey] = origin
        }
        for (key in placedBoxes.keys.toList()) {
            val newKey = rekeyedKey(key) ?: continue
            val box = placedBoxes.remove(key) ?: continue
            placedBoxes[newKey] = box
        }
    }

    companion object {
        const val REGION_PAD = 64  // empty void between adjacent regions

        /**
         * Z coordinate of the standalone-structure region lane (far from the spec lane at z=0).
         * This separation is a practical bound, not a hard guarantee: the spec lane grows in +Z
         * as a folder accumulates specs, so a single folder with enough specs (~900+, per the
         * row/grid math in [computeRegionOrigin]) could in principle grow its grid past z≈4096
         * and collide with the structure lane.
         */
        const val STRUCTURE_LANE_Z = 4096

        /**
         * Cheap, allocation-free, lock-free test for whether [pos] could possibly land inside ANY
         * structure region — safe to call before touching a specific server's [EditorDimRegistry]
         * instance at all (no [of] lookup, no map access). Every structure region's origin has
         * `z == STRUCTURE_LANE_Z` — the only mutator, [getOrAssignStructureRegion], hardcodes it —
         * and all regions share the same width, since [SharedSettings.structureRegionChunks] is a
         * single global setting rather than per-structure. So every region occupies exactly the
         * same Z band regardless of X index, server, or how many structures are placed: a position
         * outside this band cannot be inside any region, full stop, and [structureSubpathAt] relies
         * on that invariant to skip its per-region X check entirely once this passes.
         */
        fun isInStructureLaneZ(pos: BlockPos): Boolean {
            val width = SharedSettings.structureRegionChunks * 16
            return pos.z >= STRUCTURE_LANE_Z && pos.z < STRUCTURE_LANE_Z + width
        }

        private val perServer = java.util.WeakHashMap<MinecraftServer, EditorDimRegistry>()

        @Synchronized fun of(server: MinecraftServer): EditorDimRegistry =
            perServer.getOrPut(server) { EditorDimRegistry(server) }

        @Synchronized fun dispose(server: MinecraftServer) {
            perServer.remove(server)
        }
    }
}
