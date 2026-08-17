package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.editor.explorer.data.scanFolder
import com.breadmoirai.garnet.editor.explorer.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.explorer.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.structure.data.CommitOutcome
import com.breadmoirai.garnet.editor.structure.ops.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.ops.StructureCommit
import com.breadmoirai.garnet.editor.undo.data.EditorUndoStack
import com.breadmoirai.garnet.editor.undo.network.UndoStateS2C
import com.breadmoirai.garnet.editor.workspace.world.EditorRootResolver
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Shared helpers for the editor/network handler objects. */
object EditorHandlerSupport {

    fun fail(player: ServerPlayer, reason: String) {
        ServerPlayNetworking.send(player, EditorErrorS2C(reason))
    }

    /**
     * The absolute path of [parentSubpath] under the configured root, or null after sending the
     * player an error. `""` means the root itself, which `resolveSubpath` already handles; anything
     * absolute or escaping the root comes back null from that call and is refused here.
     */
    fun resolveParentFolder(
        server: MinecraftServer,
        player: ServerPlayer,
        parentSubpath: String,
    ): Path? {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return null
        }
        val folder = root.resolveSubpath(parentSubpath) ?: run {
            fail(player, "folder not found or escapes root: $parentSubpath"); return null
        }
        if (!folder.isDirectory()) {
            fail(player, "not a folder: $parentSubpath"); return null
        }
        return folder
    }

    /** Names already present in [folder], for the duplicate check. */
    fun siblingNames(folder: Path): List<String> =
        folder.listDirectoryEntries().map { it.name }

    /**
     * Commit every dirty structure at or under [subpath] before an operation relocates or destroys
     * it. Null when everything is quiesced; otherwise the reason the caller should abort, with no
     * operation-name prefix — callers prepend their own ("rename failed: ...").
     *
     * Dirty state is keyed by subpath, so an operation that changes or invalidates a path without
     * quiescing first strands every pending edit beneath it under a key nothing resolves again:
     * `dirtySubpaths()` never empties (permanently defeating `tick()`'s idle fast path), and
     * `commitAll` on BEFORE_SAVE / SERVER_STOPPING cannot resolve the old subpath either, so the
     * edits never reach the `.nbt` at all.
     *
     * A `NotApplicable` outcome whose dirty flag SURVIVED is treated as a failure alongside
     * `Failed`. It means the subpath was dirty but its root or file was momentarily unresolvable, so
     * `commit` correctly left the flag set (see `StructureCommit.commit`'s KDoc, case 2) — and
     * proceeding would strand that entry exactly like an outright failure. A `NotApplicable` that
     * DID clear the flag is fine and falls through.
     */
    fun commitDirtyUnder(server: MinecraftServer, subpath: String): String? {
        val autoSave = StructureAutoSave.of(server)
        val dirtyUnder = autoSave.dirtySubpaths().filter {
            it == subpath || it.startsWith("$subpath/")
        }
        for (dirtySubpath in dirtyUnder) {
            val outcome = StructureCommit.commit(server, dirtySubpath, LocalHistoryStore.REASON_AUTOSAVE)
            if (outcome is CommitOutcome.Failed) {
                return "could not save pending edits for '$dirtySubpath': ${outcome.reason}"
            }
            if (outcome is CommitOutcome.NotApplicable && autoSave.dirtySubpaths().contains(dirtySubpath)) {
                return "pending edits for '$dirtySubpath' are not resolvable right now"
            }
        }
        return null
    }

    fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured")
            return
        }
        val current = EditorSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, EditorTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
    }

    /**
     * Push the player's current undo/redo availability. Called after every mutating operation and
     * after every undo/redo — the toolbar renders availability rather than guessing at it.
     */
    fun sendUndoState(player: ServerPlayer) {
        ServerPlayNetworking.send(player, UndoStateS2C(
            undoLabel = EditorUndoStack.peekUndo(player.uuid)?.label,
            redoLabel = EditorUndoStack.peekRedo(player.uuid)?.label,
        ))
    }
}
