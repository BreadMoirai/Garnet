package com.breadmoirai.garnet.editor.structure.ops

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.history.network.HistoryWatchers
import com.breadmoirai.garnet.editor.structure.data.CommittedStructure
import com.breadmoirai.garnet.editor.structure.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.structure.network.toAutoSavedPayload
import com.breadmoirai.garnet.editor.workspace.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.workspace.world.EditorRootResolver
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.editor.structure.data.CommitOutcome
import com.breadmoirai.garnet.editor.structure.data.PlacedBox
import com.breadmoirai.garnet.editor.structure.data.structuresDiffer
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Layer: `editor.structure.ops` — the live editing pipeline (dirty-track → debounce → commit →
 * history), distinct from [com.breadmoirai.garnet.editor.structure.data] (pure NBT and
 * region geometry, no server state).
 *
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
 * [com.breadmoirai.garnet.core.config.SharedSettings.localHistoryMaxRevisions]. The speculative revision
 * write above therefore passes `prune = false`: it appends without ever deleting an older blob.
 * Only once the `.nbt` rewrite has actually succeeded does [commit] call
 * [LocalHistoryStore.prune] to apply the age/count cap — so a real, kept write still prunes
 * normally, but a rolled-back failed attempt never triggers a prune at all and is a complete no-op
 * against the rest of the structure's history.
 */
object StructureCommit {

    /**
     * Drop [subpath]'s failure-backoff entry. Called internally on a no-op or successful commit;
     * also public as a test/administrative seam for forgetting a failure without a real commit
     * (e.g. a test that deliberately leaves a structure dirty must also forget any backoff it
     * triggered, or a later, unrelated test sharing the same server would inherit it).
     */
    fun clearBackoff(server: MinecraftServer, subpath: String) = CommitBackoff.clearBackoff(server, subpath)

    /**
     * Capture, diff, and write [subpath] if its content actually changed. Clears the dirty state on
     * every path that leaves the `.nbt` correct ([CommitOutcome.Committed] or
     * [CommitOutcome.NoChange]) — deliberately NOT on [CommitOutcome.Failed], so the structure stays
     * dirty and is retried rather than silently abandoned.
     *
     * [now] is the server tick used for failure-backoff bookkeeping; [writeNbt] is a test seam for
     * simulating a failing `.nbt` write (production callers get the real
     * [StructurePersistence.writeStructureAtomic]).
     *
     * **Judgement call (Task 7 fix round 2, narrowed in fix round 3):** [CommitOutcome.NotApplicable]
     * covers two distinct conditions, and they are NOT equivalent for whether it's safe to clear
     * the dirty flag:
     * 1. not placed (`registry.placedBoxOf(subpath) == null`) — genuinely nothing left to commit.
     *    There is no world content standing by; the dirty flag is cleared unconditionally. This is
     *    safe because both repo-wide callers of [EditorDimRegistry.unplaceStructure] —
     *    `EditorFileOpsHandlers.handleRename` (per-subpath, aborting the whole rename on
     *    [CommitOutcome.Failed]) and
     *    `EditorTreeHandlers.handleSetRoot` (a [commitAll] flush of the OLD root before the swap) —
     *    commit every dirty subpath first and only unplace afterward — so a dirty key never survives
     *    into an unplace.
     * 2. no root (`EditorRootResolver.rootFor(server) == null`) or no file
     *    (`root.resolveSubpath(subpath) == null`, which fails whenever the candidate file doesn't
     *    currently exist on disk) — while the structure IS still placed. Root/file resolution
     *    depends on state this object does not own: an external delete-then-restore, a git
     *    checkout, cloud sync, AV quarantine, or the player swapping project roots
     *    (`handleSetRoot`) mid-edit. A still-placed structure's world blocks are the only copy of
     *    its unsaved edits, and they remain live in the level the whole time root/file are
     *    unresolvable. Clearing the dirty flag here would discard the *only* signal that those
     *    blocks still need to be written, with no path back: neither `tick` nor `commitAll` scan a
     *    non-dirty subpath, so if the root/file becomes resolvable again later (root swapped back,
     *    file restored) nothing ever re-commits it. So for (2) the dirty flag is left untouched
     *    while placed, and cleared only if the structure also isn't placed (falls through to case
     *    1's reasoning instead).
     *
     * A still-placed, unresolved subpath is given a [CommitBackoff.FAILURE_BACKOFF_TICKS] backoff
     * entry directly (not via [CommitBackoff.onCommitFailure], which would log every window — an
     * unresolved path isn't a write failure and shouldn't spam the log), so [tick]'s automatic pass
     * retries it at most once per backoff window instead of every tick — a `resolveSubpath`
     * filesystem stat roughly every 5s instead of 20x/second — while an explicit [commit] call
     * always attempts regardless. That backoff entry is cleared the same way any other is, on the
     * first [Committed][CommitOutcome.Committed]/[NoChange][CommitOutcome.NoChange] outcome, so a
     * structure whose root/file resolves again commits promptly rather than waiting out a stale
     * window.
     */
    fun commit(
        server: MinecraftServer,
        subpath: String,
        reason: String,
        now: Long = server.overworld().gameTime,
        writeNbt: (CompoundTag, Path) -> Unit = StructurePersistence::writeStructureAtomic,
    ): CommitOutcome {
        val autoSave = StructureAutoSave.of(server)
        val registry = EditorDimRegistry.of(server)
        val placedBeforeResolve = registry.placedBoxOf(subpath)
        // Root/file unresolvable while still placed: leave the dirty flag alone (case 2 above) so
        // the edits still live in the world can be recommitted once root/file resolve again. Still
        // set a backoff entry directly (not via onCommitFailure, which would log once per window —
        // this isn't a write failure, just an unresolved path, and shouldn't spam the log) so
        // tick's per-tick dueForCommit check doesn't retry (and stat the filesystem) 20x/second for
        // as long as the root/file stays broken; once it resolves again, the paths below that
        // already clearBackoff on success clear this entry too, so a restored structure commits
        // promptly rather than waiting out a stale window.
        fun notApplicableUnresolved(): CommitOutcome {
            if (placedBeforeResolve == null) {
                autoSave.clear(subpath)
                clearBackoff(server, subpath)
            } else {
                CommitBackoff.backoffMap(server)[subpath] = now + CommitBackoff.FAILURE_BACKOFF_TICKS
            }
            return CommitOutcome.NotApplicable
        }
        val root = EditorRootResolver.rootFor(server) ?: return notApplicableUnresolved()
        val file = root.resolveSubpath(subpath) ?: return notApplicableUnresolved()
        // Not placed (case 1 above): genuinely nothing left to commit, clear unconditionally.
        val placed = registry.placedBoxOf(subpath) ?: run {
            autoSave.clear(subpath)
            clearBackoff(server, subpath)
            return CommitOutcome.NotApplicable
        }

        val scan = union(placed, autoSave.dirtyBox(subpath)) ?: run {
            autoSave.clear(subpath)
            clearBackoff(server, subpath)
            return CommitOutcome.NoChange
        }
        val captured = StructurePersistence.captureAutoFitIn(registry.projectLevel(), scan)

        val committed = readTag(file)

        // F4: bank on-disk content that was written OUTSIDE the editor (external NBT tool, git
        // checkout, restore-from-backup) between sessions — if it doesn't match the newest revision
        // already banked, no revision anywhere holds it, and it's about to be overwritten below with
        // no recovery point.
        //
        // This is NOT cheap in the general case, which an earlier comment here understated: proving
        // disk matches the newest revision means gzip-decompressing that revision's blob and
        // normalizing both tags, on top of the `committed` read the no-op diff below already needs.
        // On a ~1s debounce over a large structure that is real main-thread work every commit.
        //
        // So the steady state is short-circuited by a fingerprint: after a successful commit we know
        // exactly what we wrote and which revision it matches, so if the file's size and mtime are
        // still what we left them and the newest revision is unchanged, disk IS that revision and
        // there is nothing to bank. Any genuine out-of-band write changes size or mtime (or lands
        // before this process ever committed the file, in which case there's no fingerprint at all)
        // and falls through to the full comparison. The only way past it is an external write in the
        // same filesystem timestamp tick that also preserves the exact byte length — and the
        // consequence is one un-banked external edit, the same as for a file this process has not
        // committed yet.
        if (committed != null) {
            val existing = LocalHistoryStore.revisions(file)
            val newest = existing.lastOrNull()
            val current = CommitBackoff.fingerprint(file, newest?.file.orEmpty())
            val diskIsNewestRevision =
                current != null && CommitBackoff.fingerprintMap(server)[subpath] == current
            if (!diskIsNewestRevision) {
                val newestTag = newest?.let { LocalHistoryStore.readTag(file, it) }
                if (newestTag == null || structuresDiffer(committed, newestTag)) {
                    val (sx, sy, sz) = sizeOf(committed)
                    LocalHistoryStore.writeRevision(
                        file, committed, sx, sy, sz, blockCount = 0, reason = LocalHistoryStore.REASON_EXTERNAL,
                    )
                }
            }
        }

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
            CommitBackoff.onCommitFailure(server, subpath, now, message)
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
            CommitBackoff.onCommitFailure(server, subpath, now, message)
            return CommitOutcome.Failed(message)
        }

        // The write landed: this revision is now confirmed worth keeping, so it's safe to apply
        // the age/count cap (which may delete OLDER blobs) without risking data a failed attempt
        // would have had no business touching. See the pruning invariant on this object's KDoc.
        // But ONLY when history is deliberately enabled (B3): a user who disables history means to
        // FREEZE the existing archive, not have it silently age-pruned on the next commit.
        if (historyEnabled) LocalHistoryStore.prune(file)

        // Remember what we just wrote, so the next commit can skip re-proving that disk still
        // matches the newest revision. Stat AFTER the write and AFTER prune, so the recorded
        // size/mtime and newest-revision filename describe the state actually left behind. If
        // history is off, `revision` is null and the empty revision name is recorded — which still
        // matches on the next commit, since revisions() stays empty too.
        CommitBackoff.fingerprint(file, revision?.file.orEmpty())?.let {
            CommitBackoff.fingerprintMap(server)[subpath] = it
        }

        captured.box?.let { registry.setPlacedBox(subpath, it) }
        autoSave.clear(subpath)
        clearBackoff(server, subpath)

        return CommitOutcome.Committed(CommittedStructure(
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
        val backoff = CommitBackoff.backoffMap(server)
        for (subpath in autoSave.dirtySubpaths()) {
            if (!autoSave.dueForCommit(subpath, now)) continue
            val retryAt = backoff[subpath]
            if (retryAt != null && now < retryAt) continue
            val outcome = commit(server, subpath, LocalHistoryStore.REASON_AUTOSAVE, now, writeNbt)
            if (outcome is CommitOutcome.Committed) broadcast(server, outcome.structure)
        }
    }

    /**
     * A structure [commitAll] could not get onto disk: its edits still exist only as world blocks.
     * [reason] is human-readable, for reporting to the player.
     *
     * [writeFailed] separates the two very different ways this happens, because callers must treat
     * them differently:
     * - `true` — a genuine [CommitOutcome.Failed]: the file resolved and the write itself failed
     *   (locked file, read-only checkout, AV scan). Retrying later can succeed, and the user can
     *   fix the cause, so a caller about to destroy the world blocks should refuse and say so.
     * - `false` — a [CommitOutcome.NotApplicable] whose subpath is still dirty: the root or file
     *   isn't resolvable at all right now. Nothing could be written no matter how long we wait, and
     *   refusing to proceed on this would trap the user — "Open Folder" is precisely the action
     *   that fixes an unresolvable root, so blocking it leaves no way out.
     */
    data class UncommittedStructure(val subpath: String, val reason: String, val writeFailed: Boolean)

    /**
     * Backstop flush: commit every dirty structure regardless of debounce/backoff timing. Used on
     * world-save and server stop. `EditorFileOpsHandlers.handleRename` also calls [commit] directly
     * (not this batch form) for the renamed structure AND every dirty descendant of a renamed
     * folder, BEFORE moving any files — this is what keeps a rename from stranding a dirty entry
     * under a subpath nothing will ever commit again (Task 7 fix round 1 / Finding 1), and aborts
     * the whole rename without touching the filesystem if any of those commits genuinely fails
     * (Finding 2). There is no separate unplace path any more: `handleDiscardStructure` was removed
     * along with the sidecar model. [EditorDimRegistry.unplaceStructure] has two callers repo-wide —
     * `handleRename`, above, and `handleSetRoot`, which calls this very method ([commitAll]) to flush
     * the OLD root before unplacing every previously-placed subpath during a root swap — and both
     * commit dirty state before unplacing, already covered above.
     *
     * Returns the structures whose edits did NOT reach disk — empty on the happy path. The
     * world-save and server-stop backstops ignore this (there is nobody to tell and nothing to
     * abort), but `handleSetRoot` must not: it unplaces every structure right after calling this,
     * so a structure left uncommitted here has its only copy — world blocks — destroyed moments
     * later. It refuses the root swap on any entry with [UncommittedStructure.writeFailed] set, and
     * merely logs the rest — see that property's KDoc for why the two cases cannot be treated
     * alike.
     */
    fun commitAll(server: MinecraftServer, reason: String): List<UncommittedStructure> {
        val autoSave = StructureAutoSave.of(server)
        val uncommitted = mutableListOf<UncommittedStructure>()
        for (subpath in autoSave.dirtySubpaths()) {
            when (val outcome = commit(server, subpath, reason)) {
                is CommitOutcome.Committed -> broadcast(server, outcome.structure)
                is CommitOutcome.Failed ->
                    uncommitted += UncommittedStructure(subpath, outcome.reason, writeFailed = true)
                is CommitOutcome.NotApplicable ->
                    if (autoSave.dirtySubpaths().contains(subpath)) {
                        uncommitted += UncommittedStructure(
                            subpath, "root or file is not resolvable right now", writeFailed = false,
                        )
                    }
                is CommitOutcome.NoChange -> Unit
            }
        }
        return uncommitted
    }

    /**
     * Unsolicited fan-out: tells every OTHER connected player (`exclude`, if given, is typically
     * the player who just triggered the commit and was already replied to directly — see
     * `EditorStructureHandlers.handleSaveStructure`) that a structure changed, so their Explorer status
     * lines can update. Nothing here is a reply to anything these players sent, so — unlike every
     * other S2C in this mod — the receiver isn't provably running the mod at all: a vanilla/unmodded
     * client on a dedicated server can be disconnected for an unknown play-phase payload (F6). Guard
     * every send with `canSend`. [tick] and [commitAll] are the two genuinely unsolicited callers
     * (a debounced auto-save and the periodic/shutdown backstop, neither triggered by a specific
     * player's packet) and both go through this function unfiltered (`exclude = null`).
     */
    fun broadcast(server: MinecraftServer, committed: CommittedStructure, exclude: ServerPlayer? = null) {
        val payload = committed.toAutoSavedPayload()
        for (player in server.playerList.players) {
            if (player === exclude) continue
            // Unlike every other S2C here, this one is unsolicited — it isn't a reply to a C2S, so
            // the receiver isn't provably running the mod. On a dedicated server, sending an unknown
            // play-phase payload to a vanilla/unmodded client can get it disconnected (F6).
            if (ServerPlayNetworking.canSend(player, StructureAutoSavedS2C.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
        // Anyone with this structure's Local History panel open just gained a revision. Deliberately
        // outside the `exclude` loop: `exclude` means "already replied to about the SAVE", and that
        // reply carries no revision list — the player who triggered the commit needs this push as
        // much as everyone else. `pushAll` applies the same `canSend` guard.
        HistoryWatchers.pushAll(server, payload.subpath)
    }

    /**
     * Drop this server's failure-backoff and last-committed-fingerprint bookkeeping. Pair with
     * [StructureAutoSave.dispose].
     */
    fun dispose(server: MinecraftServer) = CommitBackoff.dispose(server)

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

    /** The `(x, y, z)` size stored in a structure tag's "size" list — registry-free, unlike
     *  loading through [net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate]. */
    private fun sizeOf(tag: CompoundTag): Triple<Int, Int, Int> {
        val sizeTag = tag.getListOrEmpty("size")
        return Triple(sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0))
    }
}
