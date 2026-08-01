package com.breadmoirai.garnet.editor.world

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.PlacedBox
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.structure.structuresDiffer
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Turns a structure's dirty state into a committed `.nbt` plus a history revision.
 *
 * This replaces the old `.nbt.unsaved` sidecar flush: there is no dirty buffer any more, so a
 * commit writes the real file every time and the local-history store is what makes an edit
 * reversible.
 */
object StructureCommit {

    /**
     * Capture, diff, and write [subpath] if its content actually changed. Returns the packet
     * describing what was written, or null when nothing needed writing (or the structure is not
     * placed / not resolvable). Always clears the dirty state — a structure that captured identical
     * to disk is clean by definition.
     */
    fun commit(server: MinecraftServer, subpath: String, reason: String): StructureAutoSavedS2C? {
        val autoSave = StructureAutoSave.of(server)
        val root = EditorNetworking.rootFor(server) ?: return null
        val file = root.resolveSubpath(subpath) ?: return null
        val registry = EditorDimRegistry.of(server)
        val placed = registry.placedBoxOf(subpath) ?: return null

        val scan = union(placed, autoSave.dirtyBox(subpath)) ?: run {
            autoSave.clear(subpath)
            return null
        }
        val captured = StructurePersistence.captureAutoFitIn(registry.projectLevel(), scan)

        val committed = readTag(file)
        if (committed != null && !structuresDiffer(committed, captured.tag)) {
            autoSave.clear(subpath)
            return null
        }

        val size = captured.box?.size ?: Vec3i(0, 0, 0)
        LocalHistoryStore.writeRevision(
            file, captured.tag, size.x, size.y, size.z, captured.blockCount, reason,
        )
        try {
            file.parent?.let { java.nio.file.Files.createDirectories(it) }
            NbtIo.writeCompressed(captured.tag, file)
        } catch (e: IOException) {
            LOGGER.error("[StructureCommit] write '{}': {}", file, e.message)
            return null
        }
        captured.box?.let { registry.setPlacedBox(subpath, it) }
        autoSave.clear(subpath)

        return StructureAutoSavedS2C(
            subpath, size.x, size.y, size.z, captured.blockCount, System.currentTimeMillis(),
        )
    }

    /** Commit every dirty structure that has come due, and tell the clients. */
    fun tick(server: MinecraftServer) {
        if (!SharedSettings.autoSaveEnabled) return
        val autoSave = StructureAutoSave.of(server)
        if (autoSave.dirtySubpaths().isEmpty()) return
        val now = server.overworld().gameTime
        for (subpath in autoSave.dirtySubpaths()) {
            if (!autoSave.dueForCommit(subpath, now)) continue
            commit(server, subpath, LocalHistoryStore.REASON_AUTOSAVE)?.let { broadcast(server, it) }
        }
    }

    /**
     * Backstop flush: commit every dirty structure regardless of timing. Used on world-save, server
     * stop, and before operations that would strand dirty state (rename, unplace).
     */
    fun commitAll(server: MinecraftServer, reason: String) {
        val autoSave = StructureAutoSave.of(server)
        for (subpath in autoSave.dirtySubpaths()) {
            commit(server, subpath, reason)?.let { broadcast(server, it) }
        }
    }

    fun broadcast(server: MinecraftServer, payload: StructureAutoSavedS2C) {
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, payload)
        }
    }

    /**
     * The volume to scan: the structure's own extent plus wherever the player touched. Zero-size
     * boxes contribute nothing — an emptied structure has a size-0 placed box, and unioning that
     * with a real edit box would otherwise drag the origin to a meaningless corner.
     */
    private fun union(placed: PlacedBox, dirty: PlacedBox?): PlacedBox? {
        val boxes = listOfNotNull(placed, dirty).filter { it.size.x > 0 && it.size.y > 0 && it.size.z > 0 }
        if (boxes.isEmpty()) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var minZ = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE; var maxY = Int.MIN_VALUE; var maxZ = Int.MIN_VALUE
        for (box in boxes) {
            minX = minOf(minX, box.origin.x); maxX = maxOf(maxX, box.origin.x + box.size.x - 1)
            minY = minOf(minY, box.origin.y); maxY = maxOf(maxY, box.origin.y + box.size.y - 1)
            minZ = minOf(minZ, box.origin.z); maxZ = maxOf(maxZ, box.origin.z + box.size.z - 1)
        }
        return PlacedBox(
            BlockPos(minX, minY, minZ),
            Vec3i(maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1),
        )
    }

    private fun readTag(file: Path) =
        if (!file.exists()) null
        else runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
}
