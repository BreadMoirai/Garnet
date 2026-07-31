package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.structure.PlacedBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.server.MinecraftServer
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-server dirty bookkeeping for placed standalone structures: which ones have unsaved edits,
 * where those edits landed, and when they are due to be committed.
 *
 * Holds no world state and performs no IO — [StructureCommit] does the capturing and writing. The
 * split keeps this side cheap enough to touch from every successful `setBlock`.
 */
class StructureAutoSave {

    private data class Dirty(
        val min: BlockPos,
        val max: BlockPos,
        val firstEditTick: Long,
        val lastEditTick: Long,
    )

    private val dirty = ConcurrentHashMap<String, Dirty>()

    /** Record an edit at [pos] in [subpath]'s region, growing its dirty box. */
    fun onEdit(subpath: String, pos: BlockPos, tick: Long) {
        dirty.compute(subpath) { _, existing ->
            if (existing == null) {
                Dirty(pos, pos, firstEditTick = tick, lastEditTick = tick)
            } else {
                Dirty(
                    min = BlockPos(
                        minOf(existing.min.x, pos.x),
                        minOf(existing.min.y, pos.y),
                        minOf(existing.min.z, pos.z),
                    ),
                    max = BlockPos(
                        maxOf(existing.max.x, pos.x),
                        maxOf(existing.max.y, pos.y),
                        maxOf(existing.max.z, pos.z),
                    ),
                    firstEditTick = existing.firstEditTick,
                    lastEditTick = tick,
                )
            }
        }
    }

    fun isDirty(subpath: String): Boolean = dirty.containsKey(subpath)

    /** The inclusive box enclosing every edit since the last commit, as origin + size. */
    fun dirtyBox(subpath: String): PlacedBox? {
        val d = dirty[subpath] ?: return null
        return PlacedBox(
            d.min,
            Vec3i(d.max.x - d.min.x + 1, d.max.y - d.min.y + 1, d.max.z - d.min.z + 1),
        )
    }

    /**
     * True once the edits have gone quiet for [SharedSettings.autoSaveDebounceTicks], or the
     * structure has been continuously dirty for [SharedSettings.autoSaveMaxDirtyTicks] — the cap
     * that makes a long uninterrupted build session still checkpoint.
     */
    fun dueForCommit(subpath: String, tick: Long): Boolean {
        val d = dirty[subpath] ?: return false
        if (tick - d.lastEditTick >= SharedSettings.autoSaveDebounceTicks) return true
        return tick - d.firstEditTick >= SharedSettings.autoSaveMaxDirtyTicks
    }

    /** Snapshot of the currently dirty subpaths, safe to iterate while committing clears entries. */
    fun dirtySubpaths(): Set<String> = dirty.keys.toSet()

    fun clear(subpath: String) { dirty.remove(subpath) }

    companion object {
        private val perServer = java.util.WeakHashMap<MinecraftServer, StructureAutoSave>()

        @Synchronized fun of(server: MinecraftServer): StructureAutoSave =
            perServer.getOrPut(server) { StructureAutoSave() }

        @Synchronized fun dispose(server: MinecraftServer) { perServer.remove(server) }
    }
}
