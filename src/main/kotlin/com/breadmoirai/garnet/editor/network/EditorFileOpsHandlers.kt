package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.data.*
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.resolveParentFolder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.siblingNames
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.*
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorFileOpsHandlers {

    fun handleCreateFolder(server: MinecraftServer, player: ServerPlayer, payload: CreateFolderC2S) {
        val parent = resolveParentFolder(server, player, payload.parentSubpath) ?: return
        val name = payload.name.trim()
        EditorNames.validate(name, siblingNames(parent))?.let {
            fail(player, it); return
        }
        try {
            parent.resolve(name).createDirectory()
        } catch (e: Exception) {
            LOGGER.error("[project/create-folder] {}/{}: {}", payload.parentSubpath, name, e.message, e)
            fail(player, "create-folder failed: ${e.message}"); return
        }
        sendTree(server, player)
    }

    fun handleRename(server: MinecraftServer, player: ServerPlayer, payload: RenamePathC2S) {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot rename the project root"); return
        }
        val newName = payload.newName.trim()
        val parent = source.parent
        // Exclude the node itself so re-committing an unchanged name is a no-op, not a collision.
        val siblings = siblingNames(parent).filterNot { it == source.name }
        EditorNames.validate(newName, siblings)?.let {
            fail(player, it); return
        }

        val parentSubpath = payload.subpath.substringBeforeLast('/', "")
        val newSubpath = if (parentSubpath.isEmpty()) newName else "$parentSubpath/$newName"

        relocate(
            server, player,
            oldSubpath = payload.subpath,
            source = source,
            target = parent.resolve(newName),
            newSubpath = newSubpath,
            operation = "rename",
            placedMessage = "renamed to $newSubpath",
        )
    }

    /**
     * Move the node at [oldSubpath] from [source] to [target], where [target]'s subpath is
     * [newSubpath]. Shared by [handleRename] and [handleMove]: a rename IS a move that happens to
     * keep the same parent, and every step below is already expressed in `oldSubpath → newSubpath`
     * terms.
     *
     * [operation] names the caller in error messages ("rename failed: ..."); [placedMessage] is the
     * status line a re-placed structure reports, which each caller phrases for itself — a rename
     * that announced "moved to ..." would be simply wrong.
     *
     * Each caller does its own resolution and validation first; by the time this runs, the move is
     * known to be legal and only the ordering hazards remain.
     */
    private fun relocate(
        server: MinecraftServer,
        player: ServerPlayer,
        oldSubpath: String,
        source: Path,
        target: Path,
        newSubpath: String,
        operation: String,
        placedMessage: String,
    ) {
        // A placed structure is keyed by subpath in EditorDimRegistry, so relocating under it would
        // strand both the placed box and the region assignment if we don't unload/reload it. But the
        // move (an IO op — can fail on a lock, permission problem, etc.) must succeed FIRST: tearing
        // down the placed-structure state (clearing its blocks, dropping its registry keys) before
        // the move is confirmed would leave the structure's blocks erased and its registry entry gone
        // while the player is told the operation failed and the (untouched, still-old-named) file
        // sits there unrecoverably out of sync with the world. Only touch that state once the move
        // is confirmed.
        val registry = EditorDimRegistry.of(server)
        val wasPlaced = registry.placedBoxOf(oldSubpath)

        // Quiesce before the move: see EditorHandlerSupport.commitDirtyUnder for why a relocation
        // that skips this strands every pending edit beneath the moved path.
        commitDirtyUnder(server, oldSubpath)?.let {
            fail(player, "$operation failed: $it"); return
        }

        try {
            source.moveTo(target)
        } catch (e: Exception) {
            LOGGER.error("[project/{}] {} -> {}: {}", operation, oldSubpath, newSubpath, e.message, e)
            fail(player, "$operation failed: ${e.message}"); return
        }

        // History is keyed by each file's own absolute path, so every moved .nbt (the node itself,
        // or every descendant .nbt when a whole FOLDER moved) must carry its history across, or it
        // silently loses every revision it has accumulated (Finding 3). This cannot itself fail the
        // operation from the player's perspective — the file(s) already moved — so a problem here is
        // logged, not reported as a failure (Finding 6): sharing one `try` with the file move above
        // would have let a hypothetical history-move exception report a failure after the file had
        // already relocated.
        try {
            LocalHistoryStore.moveDescendantHistories(source, target)
        } catch (e: Exception) {
            LOGGER.error(
                "[project/{}] history move for {} -> {}: {}", operation, oldSubpath, newSubpath, e.message, e,
            )
        }

        if (wasPlaced != null) {
            // Drop the registry entry BEFORE clearing blocks, not after (Task 7 fix round 2 /
            // residual on Finding 1). clearBounds writes AIR through the 3-arg level.setBlock,
            // which the setBlock mixin hooks unconditionally for any successful server write; if
            // the OLD subpath were still registered at that instant, EditorDimRegistry.structureSubpathAt
            // would still attribute those positions to it, and the watcher would re-mark the OLD
            // subpath dirty immediately after the commit above just cleared it. Since
            // `wasPlaced.origin`/`wasPlaced.size` were already captured into a local before any
            // registry mutation, clearBounds needs nothing further from the registry, so dropping
            // the entry first is safe: with structureBySubpath no longer mapping the old subpath at
            // all (and its freed region never recycled — nextStructureIndex is monotonic), those
            // positions attribute to no subpath, and clearBounds's own writes cannot re-dirty
            // anything.
            registry.unplaceStructure(oldSubpath)
            StructurePersistence.clearBounds(registry.projectLevel(), wasPlaced.origin, wasPlaced.size)
            EditorStructureHandlers.placeStructureFrom(server, player, newSubpath, target, placedMessage)
        }

        // Rekey every OTHER registry entry nested under the moved path (e.g. structures placed
        // inside a moved folder). The node's own entry, if any, was already handled above by the
        // wasPlaced block, so by this point rekeyForRename's exact-match branch is a no-op for it —
        // only descendants still keyed under the old subpath remain to be moved.
        registry.rekeyForRename(oldSubpath, newSubpath)

        EditorSession.repointSession(player, oldSubpath, newSubpath)

        sendTree(server, player)
    }

    /**
     * Move the node at [payload].subpath into [payload].destFolderSubpath, keeping its name.
     *
     * Shares [relocate] with [handleRename] — a rename is a move that keeps its parent — so only the
     * validation differs. Three of the four checks below have no rename counterpart, because a
     * rename cannot change which folder a node lives in.
     */
    fun handleMove(server: MinecraftServer, player: ServerPlayer, payload: MovePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot move the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }
        // resolveParentFolder handles root resolution, containment, and the not-a-folder case, and
        // reports each itself.
        val destFolder = resolveParentFolder(server, player, payload.destFolderSubpath) ?: return

        // Moving a folder into its own subtree would either throw or nest the folder inside itself,
        // depending on the filesystem. The boundary is a full path SEGMENT, matching
        // EditorDimRegistry.rekeyForRename and EditorSession.repointSession: moving "redstone" into
        // the sibling "redstoneworks" is perfectly legal and a plain startsWith would wrongly
        // reject it.
        if (payload.destFolderSubpath == payload.subpath ||
            payload.destFolderSubpath.startsWith("${payload.subpath}/")
        ) {
            fail(player, "cannot move '${payload.subpath}' into itself"); return
        }

        val name = source.name
        val currentParentSubpath = payload.subpath.substringBeforeLast('/', "")
        if (currentParentSubpath == payload.destFolderSubpath) {
            // Already there. Not an error — resend the tree so a client acting on a stale snapshot
            // still converges — but nothing to move.
            sendTree(server, player); return
        }

        EditorNames.validate(name, siblingNames(destFolder))?.let {
            fail(player, "move failed: $it"); return
        }

        val newSubpath = if (payload.destFolderSubpath.isEmpty()) name
        else "${payload.destFolderSubpath}/$name"

        relocate(
            server, player,
            oldSubpath = payload.subpath,
            source = source,
            target = destFolder.resolve(name),
            newSubpath = newSubpath,
            operation = "move",
            placedMessage = "moved to $newSubpath",
        )
    }

    /**
     * Copy the node at [payload].subpath beside itself under a deduplicated name.
     *
     * Much simpler than [handleRename] because nothing in the world is keyed to a path that did not
     * exist a moment ago: no registry entry to rekey, no placed box to tear down, no session to
     * repoint. The copy is NOT placed and starts with no local history — `LocalHistoryStore` keys
     * revisions by absolute path, so it inherits nothing, and cloning the source's revisions would
     * claim an edit history the copy never had.
     */
    fun handleDuplicate(server: MinecraftServer, player: ServerPlayer, payload: DuplicatePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot duplicate the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val source = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }

        // Quiesce BEFORE reading the bytes, so the copy reflects the structure as it stands in the
        // world rather than the .nbt as it sat before the current edit. A failed commit aborts:
        // producing a silently stale duplicate is worse than producing none.
        commitDirtyUnder(server, payload.subpath)?.let {
            fail(player, "duplicate failed: $it"); return
        }

        val parent = source.parent
        val newName = EditorNames.duplicateName(
            sourceName = source.name,
            siblings = siblingNames(parent),
            isFolder = source.isDirectory(),
        )
        val target = parent.resolve(newName)

        try {
            if (source.isDirectory()) source.toFile().copyRecursively(target.toFile(), overwrite = false)
            else source.copyTo(target)
        } catch (e: Exception) {
            LOGGER.error("[project/duplicate] {} -> {}: {}", payload.subpath, newName, e.message, e)
            fail(player, "duplicate failed: ${e.message}"); return
        }

        sendTree(server, player)
    }

    /**
     * Delete the node at [payload].subpath, recursively for a folder.
     *
     * Order is fallible-IO-first, teardown-second, matching [handleRename]: if the unlink fails,
     * nothing has been unplaced and the world is untouched, so the error the player sees is the
     * whole story.
     *
     * The real hazard here is NOT the unlink — it is the leftover dirty entry. A subpath still in
     * `StructureAutoSave` after its file is gone makes `StructureCommit.tick` retry on every tick
     * forever, either failing repeatedly or writing the `.nbt` back out and resurrecting the file
     * the player just deleted. Clearing the subtree's dirty state is the correctness step, not
     * cleanup. This handler and `tick` both run on the server thread, so the window between the
     * unlink and that clear cannot be observed.
     *
     * `LocalHistoryStore` revisions are deliberately RETAINED — they are the recovery route for a
     * delete. The consequence: a file later created at the same path inherits them.
     */
    fun handleDelete(server: MinecraftServer, player: ServerPlayer, payload: DeletePathC2S) {
        if (payload.subpath.isEmpty()) {
            fail(player, "cannot delete the project root"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val target = root.resolveSubpath(payload.subpath) ?: run {
            fail(player, "path not found or escapes root: ${payload.subpath}"); return
        }

        // Best-effort quiesce: this banks a final recovery revision into the history that outlives
        // the file. Unlike every other caller, a FAILURE here does not abort — blocking a delete
        // because history could not be banked would make a structure with a broken history dir
        // undeletable from the editor, for a node the player is explicitly destroying. Logged, not
        // reported: the delete below succeeds, so an error packet would contradict what happened.
        commitDirtyUnder(server, payload.subpath)?.let {
            LOGGER.warn("[project/delete] proceeding despite failed pre-delete commit for {}: {}", payload.subpath, it)
        }

        try {
            if (target.isDirectory()) {
                // copyRecursively's counterpart returns false rather than throwing on a partial
                // failure, so an unchecked call would silently report success for a subtree that is
                // still half on disk.
                if (!target.toFile().deleteRecursively()) {
                    error("could not delete every entry under ${payload.subpath}")
                }
            } else {
                target.deleteExisting()
            }
        } catch (e: Exception) {
            LOGGER.error("[project/delete] {}: {}", payload.subpath, e.message, e)
            fail(player, "delete failed: ${e.message}"); return
        }

        // structureSubpaths(), not placedStructureSubpaths(): a place that errored partway leaves a
        // region assignment with no placed box, and that entry must go too.
        val registry = EditorDimRegistry.of(server)
        val doomed = registry.structureSubpaths().filter {
            it == payload.subpath || it.startsWith("${payload.subpath}/")
        }
        for (subpath in doomed) {
            // unplaceStructure BEFORE clearBounds, never after: clearBounds writes AIR through the
            // 3-arg level.setBlock, which the setBlock mixin hooks unconditionally, so a
            // still-registered subpath would have those very writes re-marked dirty by the mixin.
            val box = registry.unplaceStructure(subpath)
            if (box != null) {
                StructurePersistence.clearBounds(registry.projectLevel(), box.origin, box.size)
            }
        }

        val autoSave = StructureAutoSave.of(server)
        for (subpath in autoSave.dirtySubpaths().filter {
            it == payload.subpath || it.startsWith("${payload.subpath}/")
        }) {
            autoSave.clear(subpath)
            // The backoff map is keyed by subpath too; leaving an entry behind leaks one per delete
            // for the life of the server.
            StructureCommit.clearBackoff(server, subpath)
        }

        EditorSession.clearSessionUnder(player.uuid, payload.subpath)

        sendTree(server, player)
    }
}
