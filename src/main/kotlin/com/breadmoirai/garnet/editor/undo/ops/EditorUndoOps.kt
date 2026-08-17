package com.breadmoirai.garnet.editor.undo.ops

import com.breadmoirai.garnet.editor.undo.data.CreatedFileKind
import com.breadmoirai.garnet.editor.undo.data.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.data.EditorUndoStack
import com.breadmoirai.garnet.editor.undo.data.RelocateKind
import com.breadmoirai.garnet.editor.history.ops.RestoreOutcome
import com.breadmoirai.garnet.editor.history.ops.StructureRestoreOps
import com.breadmoirai.garnet.editor.explorer.network.DeleteOutcome
import com.breadmoirai.garnet.editor.explorer.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.network.EditorFolderLoadedS2C
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState
import com.breadmoirai.garnet.editor.workspace.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.workspace.world.EditorRootResolver
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import kotlin.io.path.exists

private val LOGGER = LoggerFactory.getLogger("Garnet")

/**
 * Replays a command's inverse (undo) or the command itself (redo).
 *
 * Every inverse goes through `EditorFileOpsHandlers`' primitives — `relocate`, `deleteSubtree`,
 * `restoreSubtree` — never through hand-rolled file IO. Those functions carry the ordering rules
 * this feature must not relearn: quiesce before relocating, unplace before `clearBounds`, rekey the
 * registry, repoint the session, carry history across.
 *
 * A stale entry is REFUSED and left on the stack. Per-player stacks sit over shared server state,
 * so an entry can be invalidated by anyone; refusing lets the player retry after resolving the
 * conflict, where discarding would silently skip to an older action.
 *
 * The invariant that follows from that, and which every branch here upholds: **an entry is popped
 * only when the inverse actually happened.** Every refusal — whether it came from a precondition
 * here or from a primitive that failed mid-flight — leaves both deques untouched. A stack that
 * moved an entry for an operation the filesystem refused would claim an undo that never occurred
 * and destroy the player's only handle on retrying it.
 *
 * **Layering exception.** This file imports `explorer/network` ([EditorFileOpsHandlers]) and the
 * `editor/network` spine ([EditorHandlerSupport]), against the usual `ops` → `data` direction,
 * because undoing a file operation replays it through the very handlers the client would have
 * invoked. This is the codebase's only `ops` → `network` edge and is recorded in
 * `docs/superpowers/specs/2026-08-16-feature-sub-package-layout-design.md`. A second one means
 * the rule is wrong and should be revisited, not extended.
 */
object EditorUndoOps {

    /**
     * What a replay produced — in EITHER direction: a failure reason, or the command to seat on the
     * opposite deque. `reapply` returns this too, not a nullable refusal, because a re-delete has to
     * hand back a REPLACEMENT command rather than letting `redo()` re-seat the stale original.
     */
    private sealed interface Inverted {
        /**
         * [alreadyReported] means the primitive that refused has ALREADY sent the player an
         * `EditorErrorS2C` of its own (`relocate` reports its own failures), so [undo]/[redo] must
         * not send a second packet for the same failure. Do not "simplify" this flag away: without
         * it a failed relocate produces two error toasts for one event.
         */
        data class Refused(val reason: String, val alreadyReported: Boolean = false) : Inverted

        /**
         * [redoable] is the command to seat on the OPPOSITE deque — the redo deque for [undo], the
         * undo deque for [redo]. Usually the original command; creates return a copy carrying their
         * bank, and a re-delete on the redo path returns the FRESH `Delete` its own
         * `deleteSubtree` produced. Seating the original there instead would re-seat a bank of the
         * bytes as they stood before the FIRST delete, so any edit made between the undo and the
         * redo would be silently reverted by a later undo.
         *
         * Null means "the operation happened, but it cannot be replayed in the other direction" —
         * an undone create whose removal could not be banked, or a re-delete that could not be
         * banked (local history off, or a banking failure). Seating such an entry would light the
         * opposite button on something that refuses on every press, and since refusals never pop,
         * that entry would permanently mask every entry beneath it.
         */
        data class Applied(val redoable: EditorUndoCommand?) : Inverted
    }

    fun undo(server: MinecraftServer, player: ServerPlayer) {
        val command = EditorUndoStack.peekUndo(player.uuid) ?: run {
            fail(player, "nothing to undo"); return
        }
        when (val result = applyInverse(server, player, command)) {
            is Inverted.Refused -> {
                // The entry stays put — see this object's KDoc.
                if (!result.alreadyReported) fail(player, "can't undo ${command.label} — ${result.reason}")
                return
            }

            is Inverted.Applied -> {
                EditorUndoStack.popUndo(player.uuid)
                result.redoable?.let { EditorUndoStack.pushRedo(player.uuid, it) }
            }
        }
        sendTree(server, player)
        sendUndoState(player)
    }

    fun redo(server: MinecraftServer, player: ServerPlayer) {
        val command = EditorUndoStack.peekRedo(player.uuid) ?: run {
            fail(player, "nothing to redo"); return
        }
        when (val result = reapply(server, player, command)) {
            is Inverted.Refused -> {
                // Same rule as undo(): the redo entry stays put, and a primitive that already
                // reported its own failure is not reported twice.
                if (!result.alreadyReported) fail(player, "can't redo ${command.label} — ${result.reason}")
                return
            }

            is Inverted.Applied -> {
                EditorUndoStack.popRedo(player.uuid)
                // push() would clear the redo deque, discarding every entry above this one. This is
                // a replay, not a new action, so the redo branch must survive.
                //
                // What gets seated is `result.redoable`, NOT `command`: a re-delete banks its own
                // fresh copy of what was on disk, and null means the replay cannot be undone again
                // (see Inverted.Applied's KDoc).
                result.redoable?.let { EditorUndoStack.pushUndoWithoutClearingRedo(player.uuid, it) }
            }
        }
        sendTree(server, player)
        sendUndoState(player)
    }

    private fun applyInverse(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand,
    ): Inverted = when (command) {
        is EditorUndoCommand.CreateFolder ->
            when (val removed = removeCreated(server, player, command.subpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                // No bank, no redo — see Inverted.Applied's KDoc.
                is Removed.Gone -> Inverted.Applied(removed.banked?.let { command.copy(banked = it) })
            }

        is EditorUndoCommand.CreateFile ->
            when (val removed = removeCreated(server, player, command.subpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                is Removed.Gone -> {
                    // A spec's creation re-placed its parent folder in the world, so removing the
                    // spec must re-place it again — otherwise the folder keeps showing a spec whose
                    // file no longer exists. Structures are not placed by their create handler and
                    // need nothing here.
                    if (command.kind == CreatedFileKind.SPEC) replaceFolderOf(server, player, command.subpath)
                    Inverted.Applied(removed.banked?.let { command.copy(banked = it) })
                }
            }

        is EditorUndoCommand.Duplicate ->
            when (val removed = removeCreated(server, player, command.createdSubpath)) {
                is Removed.Refused -> Inverted.Refused(removed.reason)
                is Removed.Gone -> Inverted.Applied(removed.banked?.let { command.copy(banked = it) })
            }

        is EditorUndoCommand.Relocate ->
            moveBack(server, player, from = command.newSubpath, to = command.oldSubpath, command = command)

        is EditorUndoCommand.Delete -> restore(server, player, command)

        // Undo = the SAME operation aimed at the other revision. The pre-restore content is a real
        // banked revision (the restore's own quiesce guaranteed it), so no content rides on the
        // command and there is nothing to reconstruct here.
        is EditorUndoCommand.RestoreRevision ->
            replayRestore(server, command, aimedAt = command.fromTimestampMillis)
    }

    /**
     * Replays [command] itself. The refusal's `reason` reads as a clause following "can't redo X — ",
     * and its `alreadyReported` says whether the player has already heard it.
     *
     * On success, [Inverted.Applied.redoable] is what `redo()` seats on the UNDO deque — which is
     * not always [command]: see that property's KDoc.
     */
    private fun reapply(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand,
    ): Inverted = when (command) {
        // A create cannot be replayed as a create — the content came from a create handler, not
        // from anything this command recorded. It is replayed as a RESTORE of the bank the undo
        // took on its way out, which is exactly what `banked` is for.
        is EditorUndoCommand.CreateFolder ->
            restoreBanked(server, player, command.banked) ?: Inverted.Applied(command)

        is EditorUndoCommand.CreateFile -> {
            val problem = restoreBanked(server, player, command.banked)
            if (problem != null) problem
            else {
                // On SUCCESS only: the spec file is back, so the folder must be re-placed to show
                // it again.
                if (command.kind == CreatedFileKind.SPEC) replaceFolderOf(server, player, command.subpath)
                Inverted.Applied(command)
            }
        }

        is EditorUndoCommand.Duplicate ->
            restoreBanked(server, player, command.banked) ?: Inverted.Applied(command)

        is EditorUndoCommand.Relocate ->
            moveBack(server, player, from = command.oldSubpath, to = command.newSubpath, command = command)

        is EditorUndoCommand.Delete -> {
            val root = EditorRootResolver.rootFor(server)
            // A missing root and a path that is no longer there are different problems and must not
            // collapse into one message: "'X' is already gone" is flatly wrong when the truth is
            // that no project is open.
            if (root == null) Inverted.Refused("project-root not configured")
            else {
                val target = root.resolveSubpath(command.rootSubpath)
                if (target == null) Inverted.Refused("'${command.rootSubpath}' is already gone")
                else when (val outcome =
                    EditorFileOpsHandlers.deleteSubtree(server, player, command.rootSubpath, target)) {
                    is DeleteOutcome.Failed -> Inverted.Refused(outcome.reason)
                    // The FRESH bank, not `command`: whatever was on disk at this instant is what a
                    // later undo must bring back. Re-seating the original would revert every edit
                    // made between the undo and this redo, silently.
                    is DeleteOutcome.Deleted -> Inverted.Applied(outcome.command)
                    // Re-deleted, but this replay cannot be undone again. Not a refusal — the files
                    // really are gone — so seat nothing, exactly as undo() does for a create whose
                    // removal could not be banked. An entry that refuses on every press would mask
                    // every entry beneath it forever.
                    is DeleteOutcome.DeletedUnbankable -> Inverted.Applied(null)
                    is DeleteOutcome.DeletedHistoryDisabled -> Inverted.Applied(null)
                }
            }
        }

        is EditorUndoCommand.RestoreRevision ->
            replayRestore(server, command, aimedAt = command.toTimestampMillis)
    }

    /**
     * Both directions of a [EditorUndoCommand.RestoreRevision] are one function: a restore aimed at
     * [aimedAt] — `fromTimestampMillis` to undo, `toTimestampMillis` to redo.
     *
     * The seated command is deliberately NOT `command`. Its `from` is `outcome.fromTimestampMillis`
     * — the revision the restore's own quiesce just banked, i.e. where THIS replay came from — so a
     * later reversal aims at a revision that actually exists and holds the content that was on disk
     * a moment ago. Re-seating `command.fromTimestampMillis` would aim every subsequent undo at the
     * one original revision, which is not what the intervening replays left on disk. `to` stays put:
     * it names the revision this whole entry is *about*, and it does not move as the pair is
     * replayed.
     *
     * Player-independent, unlike the other branches: `StructureRestoreOps` takes no `ServerPlayer`
     * and reports through its outcome, so `undo`/`redo` phrase the failure.
     */
    private fun replayRestore(
        server: MinecraftServer,
        command: EditorUndoCommand.RestoreRevision,
        aimedAt: Long,
    ): Inverted = when (val outcome = StructureRestoreOps.restore(server, command.subpath, aimedAt)) {
        // Refusals never pop — a pruned revision or an unplaced structure is a conflict the player
        // can resolve and retry, exactly like a stale relocate.
        is RestoreOutcome.Refused -> Inverted.Refused(outcome.reason)
        is RestoreOutcome.Restored -> Inverted.Applied(
            command.copy(fromTimestampMillis = outcome.fromTimestampMillis),
        )
    }

    private fun restoreBanked(
        server: MinecraftServer,
        player: ServerPlayer,
        banked: EditorUndoCommand.Delete?,
    ): Inverted.Refused? {
        if (banked == null) return Inverted.Refused("the removal could not be banked, so it cannot be redone")
        return when (val result = restore(server, player, banked)) {
            is Inverted.Refused -> result
            is Inverted.Applied -> null
        }
    }

    /** Outcome of removing a node an undone create had produced. */
    private sealed interface Removed {
        data class Refused(val reason: String) : Removed

        /** [banked] is null when the removal could not be banked — redo will be unavailable. */
        data class Gone(val banked: EditorUndoCommand.Delete?) : Removed
    }

    private fun removeCreated(server: MinecraftServer, player: ServerPlayer, subpath: String): Removed {
        val root = EditorRootResolver.rootFor(server) ?: return Removed.Refused("project-root not configured")
        val target = root.resolveSubpath(subpath) ?: return Removed.Refused("'$subpath' is no longer there")
        return when (val outcome = EditorFileOpsHandlers.deleteSubtree(server, player, subpath, target)) {
            is DeleteOutcome.Failed -> Removed.Refused(outcome.reason)
            is DeleteOutcome.Deleted -> Removed.Gone(outcome.command)
            // Removed, but unbankable. The undo itself succeeded — the player asked for the created
            // node to go away and it did — so this is not a refusal. Only redo is lost.
            is DeleteOutcome.DeletedUnbankable -> Removed.Gone(null)
            // Same reasoning, but a standing property of the player's settings rather than a
            // failure: with local history off nothing can be banked, so redo is simply unavailable.
            is DeleteOutcome.DeletedHistoryDisabled -> Removed.Gone(null)
        }
    }

    /**
     * Re-place the folder containing [subpath], so the world matches the files again after a spec
     * appears or disappears, and tell the client what that folder now holds.
     *
     * The `EditorFolderLoadedS2C` is not optional bookkeeping: `sendTree` refreshes the TREE, not
     * the client's `loadedSpecIds` for a folder, so an undo that only re-placed server-side would
     * leave the client listing a spec whose file is gone, with no error and no self-correction.
     * `handleNewSpec` sends exactly this packet from the same `placeFolder` report; its inverse must
     * too.
     *
     * `placeFolder` THROWS on failure, which is what the `runCatching` below is for: a re-place that
     * blew up is logged here and swallowed (and no packet is sent — there is no report to send). It
     * must never fail the undo — the file operation it follows has already happened, so reporting
     * failure would tell the player nothing changed when the tree in fact did.
     */
    private fun replaceFolderOf(server: MinecraftServer, player: ServerPlayer, subpath: String) {
        val root = EditorRootResolver.rootFor(server) ?: return
        val folderSubpath = subpath.substringBeforeLast('/', "")
        runCatching { EditorDimLifecycle.placeFolder(server, root, folderSubpath) }
            .onFailure { LOGGER.error("[project/undo] re-place '{}': {}", folderSubpath, it.message, it) }
            .onSuccess { report ->
                ServerPlayNetworking.send(player, EditorFolderLoadedS2C(
                    subpath = report.subpath,
                    loadedSpecIds = report.loaded,
                    parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
                    layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
                ))
            }
    }

    private fun moveBack(
        server: MinecraftServer,
        player: ServerPlayer,
        from: String,
        to: String,
        command: EditorUndoCommand.Relocate,
    ): Inverted {
        val root = EditorRootResolver.rootFor(server)
            ?: return Inverted.Refused("project-root not configured")
        val source = root.resolveSubpath(from)
            ?: return Inverted.Refused("'$from' moved or was deleted since")
        val parentSubpath = to.substringBeforeLast('/', "")
        val parent = root.resolveSubpath(parentSubpath)
            ?: return Inverted.Refused("'$parentSubpath' no longer exists")
        val target = parent.resolve(to.substringAfterLast('/'))
        if (target.exists()) return Inverted.Refused("'$to' is occupied again")

        val moved = EditorFileOpsHandlers.relocate(
            server, player,
            oldSubpath = from,
            source = source,
            target = target,
            newSubpath = to,
            operation = if (command.kind == RelocateKind.RENAME) "undo rename" else "undo move",
            placedMessage = "restored to $to",
            kind = command.kind,
            // The stack is managed by undo()/redo() above; recording here would push a duplicate
            // entry for a move the player did not perform.
            record = false,
        )
        // The preconditions above cover staleness, not IO: a lock, a permission problem, or a
        // destination whose parent stopped being a folder all fail inside `relocate`, after every
        // check here has passed. Without this branch the entry would be popped for a move that
        // never happened. `relocate` has already told the player why, hence alreadyReported.
        if (!moved) return Inverted.Refused("the move could not be completed", alreadyReported = true)
        return Inverted.Applied(command)
    }

    private fun restore(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand.Delete,
    ): Inverted {
        val root = EditorRootResolver.rootFor(server)
            ?: return Inverted.Refused("project-root not configured")
        // The precondition: the path must still be free. Restoring over content that arrived after
        // the delete would destroy work nobody asked to lose.
        if (root.resolveSubpath(command.rootSubpath) != null) {
            return Inverted.Refused("'${command.rootSubpath}' exists again")
        }
        val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)
        if (report.failures.isNotEmpty()) {
            // TOTAL failure — the parent folder is gone, or every blob has been pruned — leaves the
            // filesystem exactly as it was, so consuming the entry would claim an undo that never
            // happened and strand a redo entry that can never fire (`reapply` would answer "already
            // gone"). Refuse, and let the player retry once they have fixed the cause.
            if (report.restored == 0 && report.foldersCreated == 0) {
                return Inverted.Refused("nothing could be restored: ${report.failures.joinToString("; ")}")
            }
            // A PARTIAL restore is different, and is NOT rolled back — deleting what was just
            // recovered would be worse. Report honestly and let the entry be consumed, since the
            // tree really has changed.
            fail(player, "restored ${report.restored} of ${report.total} files: ${report.failures.joinToString("; ")}")
        }
        return Inverted.Applied(command)
    }
}
