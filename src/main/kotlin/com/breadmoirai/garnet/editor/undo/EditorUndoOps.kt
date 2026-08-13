package com.breadmoirai.garnet.editor.undo

import com.breadmoirai.garnet.editor.network.DeleteOutcome
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState
import com.breadmoirai.garnet.editor.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.world.EditorRootResolver
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
 */
object EditorUndoOps {

    /** What an inverse produced: a failure reason, or the command to seat on the redo deque. */
    private sealed interface Inverted {
        /**
         * [alreadyReported] means the primitive that refused has ALREADY sent the player an
         * `EditorErrorS2C` of its own (`relocate` reports its own failures), so [undo]/[redo] must
         * not send a second packet for the same failure. Do not "simplify" this flag away: without
         * it a failed relocate produces two error toasts for one event.
         */
        data class Refused(val reason: String, val alreadyReported: Boolean = false) : Inverted

        /**
         * [redoable] is usually the original command; creates return a copy carrying their bank.
         *
         * Null means "the inverse happened, but it cannot be redone" — an undone create whose
         * removal could not be banked (local history off, or a banking failure). Seating such an
         * entry on the redo deque would light the Redo button on something that refuses on every
         * press, and since refusals never pop, that entry would permanently mask every redo beneath
         * it.
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
        val problem = reapply(server, player, command)
        if (problem != null) {
            // Same rule as undo(): the redo entry stays put, and a primitive that already reported
            // its own failure is not reported twice.
            if (!problem.alreadyReported) fail(player, "can't redo ${command.label} — ${problem.reason}")
            return
        }
        EditorUndoStack.popRedo(player.uuid)
        // push() would clear the redo deque, discarding every entry above this one. This is a
        // replay, not a new action, so the redo branch must survive.
        EditorUndoStack.pushUndoWithoutClearingRedo(player.uuid, command)
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
                    if (command.kind == CreatedFileKind.SPEC) replaceFolderOf(server, command.subpath)
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
    }

    /**
     * Null on success, else the refusal — whose `reason` reads as a clause following
     * "can't redo X — ", and whose `alreadyReported` says whether the player has already heard it.
     */
    private fun reapply(
        server: MinecraftServer,
        player: ServerPlayer,
        command: EditorUndoCommand,
    ): Inverted.Refused? = when (command) {
        // A create cannot be replayed as a create — the content came from a create handler, not
        // from anything this command recorded. It is replayed as a RESTORE of the bank the undo
        // took on its way out, which is exactly what `banked` is for.
        is EditorUndoCommand.CreateFolder -> restoreBanked(server, player, command.banked)
        is EditorUndoCommand.CreateFile -> restoreBanked(server, player, command.banked).also { problem ->
            // On SUCCESS only (problem == null): the spec file is back, so the folder must be
            // re-placed to show it again. `?.also` here would be exactly backwards — it fires on
            // failure.
            if (problem == null && command.kind == CreatedFileKind.SPEC) {
                replaceFolderOf(server, command.subpath)
            }
        }

        is EditorUndoCommand.Duplicate -> restoreBanked(server, player, command.banked)

        is EditorUndoCommand.Relocate ->
            when (val result = moveBack(server, player, from = command.oldSubpath, to = command.newSubpath, command = command)) {
                is Inverted.Refused -> result
                is Inverted.Applied -> null
            }

        is EditorUndoCommand.Delete -> {
            val root = EditorRootResolver.rootFor(server)
            val target = root?.resolveSubpath(command.rootSubpath)
            if (target == null) Inverted.Refused("'${command.rootSubpath}' is already gone")
            else when (val outcome = EditorFileOpsHandlers.deleteSubtree(server, player, command.rootSubpath, target)) {
                is DeleteOutcome.Failed -> Inverted.Refused(outcome.reason)
                else -> null
            }
        }
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
     * appears or disappears.
     *
     * `placeFolder` THROWS on failure, which is what the `runCatching` below is for: a re-place that
     * blew up is logged here and swallowed. It must never fail the undo — the file operation it
     * follows has already happened, so reporting failure would tell the player nothing changed when
     * the tree in fact did.
     */
    private fun replaceFolderOf(server: MinecraftServer, subpath: String) {
        val root = EditorRootResolver.rootFor(server) ?: return
        val folderSubpath = subpath.substringBeforeLast('/', "")
        runCatching { EditorDimLifecycle.placeFolder(server, root, folderSubpath) }
            .onFailure { LOGGER.error("[project/undo] re-place '{}': {}", folderSubpath, it.message, it) }
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
