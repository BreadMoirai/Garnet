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
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Turns a structure's dirty state into a committed `.nbt` plus a history revision.
 *
 * This replaces the old `.nbt.unsaved` sidecar flush: there is no dirty buffer any more, so a
 * commit writes the real file every time and the local-history store is what makes an edit
 * reversible.
 *
 * **Ordering invariant (fix round 1 / Finding 2):** the live `.nbt` is never overwritten unless the
 * new content is already durably recorded in local history, or history is deliberately disabled.
 * [commit] therefore writes the revision *before* the `.nbt` rewrite and aborts — touching nothing
 * on disk — if that revision write fails for a reason other than history being off. If the
 * subsequent `.nbt` write itself then fails, the just-written revision is rolled back
 * ([LocalHistoryStore.discardRevision]) so a structure that can never actually commit does not
 * accumulate one indistinguishable revision per retry.
 *
 * **Pruning invariant (fix round 2 / Finding 1b):** a failed attempt must cause NO net loss of
 * genuine history, at any revision count — including at/above
 * [com.breadmoirai.garnet.config.SharedSettings.localHistoryMaxRevisions]. The speculative revision
 * write above therefore passes `prune = false`: it appends without ever deleting an older blob.
 * Only once the `.nbt` rewrite has actually succeeded does [commit] call
 * [LocalHistoryStore.prune] to apply the age/count cap — so a real, kept write still prunes
 * normally, but a rolled-back failed attempt never triggers a prune at all and is a complete no-op
 * against the rest of the structure's history.
 */
object StructureCommit {

    /**
     * Minimum ticks between retrying a structure whose last commit attempt failed to write its
     * `.nbt` or its history revision. ~5s at 20 tps — far above the previous every-tick retry, low
     * enough that a transient lock (antivirus scan, a momentarily-open file handle) still recovers
     * quickly. Gates the automatic [tick] pass only; an explicit [commit] call always attempts.
     */
    private const val FAILURE_BACKOFF_TICKS = 100L

    private val retryAfter = java.util.WeakHashMap<MinecraftServer, ConcurrentHashMap<String, Long>>()

    @Synchronized
    private fun backoffMap(server: MinecraftServer): ConcurrentHashMap<String, Long> =
        retryAfter.getOrPut(server) { ConcurrentHashMap() }

    /**
     * Drop [subpath]'s failure-backoff entry. Called internally on a no-op or successful commit;
     * also public as a test/administrative seam for forgetting a failure without a real commit
     * (e.g. a test that deliberately leaves a structure dirty must also forget any backoff it
     * triggered, or a later, unrelated test sharing the same server would inherit it).
     */
    fun clearBackoff(server: MinecraftServer, subpath: String) {
        backoffMap(server).remove(subpath)
    }

    /**
     * Records a failed commit attempt so [tick] backs off retrying [subpath] for
     * [FAILURE_BACKOFF_TICKS], and logs once per backoff window rather than once per attempt (an
     * explicit [commit] call bypasses [tick]'s skip and can retry sooner; this keeps repeated
     * explicit retries from spamming the log too).
     */
    private fun onCommitFailure(server: MinecraftServer, subpath: String, now: Long, message: String) {
        val previous = backoffMap(server).put(subpath, now + FAILURE_BACKOFF_TICKS)
        if (previous == null || previous <= now) {
            LOGGER.error("[StructureCommit] {}", message)
        }
    }

    /**
     * The result of a single [commit] attempt. Deliberately distinguishes "there was nothing to do"
     * from "an attempt was made and it failed" (Task 7 fix round 1 / Finding 4) — a caller that
     * collapses both into a bare `null` cannot tell a genuinely clean structure apart from one whose
     * edits are still only in the world and never made it to disk, which is exactly the distinction
     * a user pressing "Save Structure" needs reported honestly.
     */
    sealed interface CommitOutcome {
        /** A real write landed; [payload] is what changed, for broadcasting. */
        data class Committed(val payload: StructureAutoSavedS2C) : CommitOutcome

        /** The capture already matches the committed `.nbt` (or there was nothing to scan at all). */
        data object NoChange : CommitOutcome

        /** [commit] was called for a subpath that isn't placed, or the root isn't resolvable. */
        data object NotApplicable : CommitOutcome

        /** The history write or the `.nbt` write genuinely failed; the dirty state was NOT cleared. */
        data class Failed(val reason: String) : CommitOutcome
    }

    /**
     * Capture, diff, and write [subpath] if its content actually changed. Clears the dirty state on
     * every path that leaves the `.nbt` correct ([CommitOutcome.Committed] or
     * [CommitOutcome.NoChange]) — deliberately NOT on [CommitOutcome.Failed], so the structure stays
     * dirty and is retried rather than silently abandoned.
     *
     * [now] is the server tick used for failure-backoff bookkeeping; [writeNbt] is a test seam for
     * simulating a failing `.nbt` write (production callers get the real
     * [StructurePersistence.writeStructureAtomic]).
     */
    fun commit(
        server: MinecraftServer,
        subpath: String,
        reason: String,
        now: Long = server.overworld().gameTime,
        writeNbt: (CompoundTag, Path) -> Unit = StructurePersistence::writeStructureAtomic,
    ): CommitOutcome {
        val autoSave = StructureAutoSave.of(server)
        val root = EditorNetworking.rootFor(server) ?: return CommitOutcome.NotApplicable
        val file = root.resolveSubpath(subpath) ?: return CommitOutcome.NotApplicable
        val registry = EditorDimRegistry.of(server)
        val placed = registry.placedBoxOf(subpath) ?: return CommitOutcome.NotApplicable

        val scan = union(placed, autoSave.dirtyBox(subpath)) ?: run {
            autoSave.clear(subpath)
            clearBackoff(server, subpath)
            return CommitOutcome.NoChange
        }
        val captured = StructurePersistence.captureAutoFitIn(registry.projectLevel(), scan)

        val committed = readTag(file)
        if (committed != null && !structuresDiffer(committed, captured.tag)) {
            autoSave.clear(subpath)
            clearBackoff(server, subpath)
            return CommitOutcome.NoChange
        }

        val size = captured.box?.size ?: Vec3i(0, 0, 0)

        // Bank the new content in history BEFORE it becomes the live .nbt. A failure here (history
        // enabled but the write genuinely failed — NOT the same as history being disabled) means we
        // cannot recover this content if the rewrite below goes wrong, so we abort without touching
        // the .nbt at all.
        val historyEnabled = SharedSettings.localHistoryEnabled
        // prune = false: see the pruning invariant on this object's KDoc. A failed attempt must
        // not touch older revisions; only a write that actually lands (below) prunes.
        val revision = LocalHistoryStore.writeRevision(
            file, captured.tag, size.x, size.y, size.z, captured.blockCount, reason, prune = false,
        )
        if (historyEnabled && revision == null) {
            val message = "history write failed for '$subpath' ($file) — .nbt left untouched"
            onCommitFailure(server, subpath, now, message)
            return CommitOutcome.Failed(message)
        }

        try {
            writeNbt(captured.tag, file)
        } catch (e: IOException) {
            // The .nbt write itself failed (locked file, read-only checkout, AV scan mid-write,
            // ...). The revision above described content that never actually landed live — discard
            // it so a stuck structure doesn't bank one orphan revision per retry.
            if (revision != null) LocalHistoryStore.discardRevision(file, revision)
            val message = "write '$file' failed: ${e.message}"
            onCommitFailure(server, subpath, now, message)
            return CommitOutcome.Failed(message)
        }

        // The write landed: this revision is now confirmed worth keeping, so it's safe to apply
        // the age/count cap (which may delete OLDER blobs) without risking data a failed attempt
        // would have had no business touching. See the pruning invariant on this object's KDoc.
        LocalHistoryStore.prune(file)

        captured.box?.let { registry.setPlacedBox(subpath, it) }
        autoSave.clear(subpath)
        clearBackoff(server, subpath)

        return CommitOutcome.Committed(StructureAutoSavedS2C(
            subpath, size.x, size.y, size.z, captured.blockCount, System.currentTimeMillis(),
        ))
    }

    /** Commit every dirty structure that has come due and isn't in a failure backoff, and tell the clients. */
    fun tick(
        server: MinecraftServer,
        now: Long = server.overworld().gameTime,
        writeNbt: (CompoundTag, Path) -> Unit = StructurePersistence::writeStructureAtomic,
    ) {
        if (!SharedSettings.autoSaveEnabled) return
        val autoSave = StructureAutoSave.of(server)
        if (autoSave.dirtySubpaths().isEmpty()) return
        val backoff = backoffMap(server)
        for (subpath in autoSave.dirtySubpaths()) {
            if (!autoSave.dueForCommit(subpath, now)) continue
            val retryAt = backoff[subpath]
            if (retryAt != null && now < retryAt) continue
            val outcome = commit(server, subpath, LocalHistoryStore.REASON_AUTOSAVE, now, writeNbt)
            if (outcome is CommitOutcome.Committed) broadcast(server, outcome.payload)
        }
    }

    /**
     * Backstop flush: commit every dirty structure regardless of debounce/backoff timing. Used on
     * world-save and server stop. `EditorNetworking.handleRename` also calls [commit] directly
     * (not this batch form) for the renamed structure AND every dirty descendant of a renamed
     * folder, BEFORE moving any files — this is what keeps a rename from stranding a dirty entry
     * under a subpath nothing will ever commit again (Task 7 fix round 1 / Finding 1), and aborts
     * the whole rename without touching the filesystem if any of those commits genuinely fails
     * (Finding 2). There is no separate unplace path any more: `handleDiscardStructure` was removed
     * along with the sidecar model, and `handleRename` is the only caller of
     * [EditorDimRegistry.unplaceStructure], already covered above.
     */
    fun commitAll(server: MinecraftServer, reason: String) {
        val autoSave = StructureAutoSave.of(server)
        for (subpath in autoSave.dirtySubpaths()) {
            val outcome = commit(server, subpath, reason)
            if (outcome is CommitOutcome.Committed) broadcast(server, outcome.payload)
        }
    }

    fun broadcast(server: MinecraftServer, payload: StructureAutoSavedS2C) {
        for (player in server.playerList.players) {
            ServerPlayNetworking.send(player, payload)
        }
    }

    /** Drop this server's failure-backoff bookkeeping. Pair with [StructureAutoSave.dispose]. */
    @Synchronized
    fun dispose(server: MinecraftServer) {
        retryAfter.remove(server)
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
