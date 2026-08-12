package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.*
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.resolveParentFolder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.siblingNames
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.undo.BankedFile
import com.breadmoirai.garnet.editor.undo.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.EditorUndoStack
import com.breadmoirai.garnet.editor.undo.ManifestEntry
import com.breadmoirai.garnet.editor.world.*
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.history.Revision
import com.breadmoirai.garnet.structure.StructurePersistence
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.PathWalkOption
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.walk

private val LOGGER = LoggerFactory.getLogger("Garnet")

/** The result of [EditorFileOpsHandlers.deleteSubtree]. */
sealed interface DeleteOutcome {
    /** Nothing was deleted; [reason] is already phrased for the player. */
    data class Failed(val reason: String) : DeleteOutcome
    /** Deleted, and fully restorable — push [command]. */
    data class Deleted(val command: EditorUndoCommand.Delete) : DeleteOutcome
    /**
     * Deleted, but at least one file could not be banked, so the operation is NOT undoable and the
     * caller must push nothing. A partially restorable entry is worse than none: undo would report
     * success while silently losing files.
     */
    data class DeletedUnbankable(val reason: String) : DeleteOutcome
    /**
     * Deleted, not undoable, and NOT worth telling the player about: local history is switched off,
     * so `LocalHistoryStore` returns null for every write and nothing can be banked by definition.
     *
     * A separate variant rather than a flag on [DeletedUnbankable] because the two differ in what
     * the caller must DO, not merely in wording: an unexpected banking failure earns an error
     * packet, while this one must stay silent. Folding them together would leave the silence
     * depending on a boolean every future caller has to remember to read — and the failure mode of
     * forgetting is an error packet on every successful delete in a supported configuration, which
     * is exactly the bug this variant exists to prevent.
     */
    data object DeletedHistoryDisabled : DeleteOutcome
}

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
     *
     * Everything after the resolution/validation and the best-effort quiesce below lives in
     * [deleteSubtree], which the undo/redo ops reuse; this handler only adds the undo bookkeeping.
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

        when (val outcome = deleteSubtree(server, player, payload.subpath, target)) {
            is DeleteOutcome.Failed -> { fail(player, outcome.reason); return }
            is DeleteOutcome.Deleted -> EditorUndoStack.push(player.uuid, outcome.command)
            // Deleted and not undoable, but deliberately SILENT: the player switched local history
            // off, so undo being unavailable is a standing property of their own configuration, not
            // news about this delete. An error packet here would fire on every successful delete.
            is DeleteOutcome.DeletedHistoryDisabled -> Unit
            is DeleteOutcome.DeletedUnbankable -> {
                // Deleted, but not undoable. Say so rather than leaving an Undo button that would
                // silently restore an incomplete subtree.
                LOGGER.warn("[project/delete] {} is not undoable: {}", payload.subpath, outcome.reason)
                fail(player, "deleted '${payload.subpath}', but it cannot be undone: ${outcome.reason}")
            }
        }

        sendTree(server, player)
        sendUndoState(player)
    }

    /**
     * Walk [target]'s subtree, bank every file into `LocalHistoryStore`, then unlink and tear down.
     *
     * Order is bank → unlink → teardown. Banking BEFORE the unlink is the only ordering that works:
     * once the bytes are gone there is nothing left to read. The pre-existing best-effort
     * `commitDirtyUnder` still runs before this (in the caller), so a dirty structure's pending
     * world edits are already in the file by the time it is read here.
     *
     * Every file is banked unconditionally rather than only those whose newest revision looks
     * stale: an equality check would be a guess about content, and unconditional banking is what
     * closes the two real gaps — `.spec.kts` files were never in the store at all, and a freshly
     * duplicated `.nbt` has no history by design.
     *
     * **Manifest order is an invariant, not an accident.** `Path.walk` with `INCLUDE_DIRECTORIES`
     * and WITHOUT `BREADTH_FIRST` is depth-first pre-order, so a directory is always emitted before
     * anything inside it. The restore side relies on exactly that to `createDirectories` in
     * manifest order without sorting or re-deriving parents. Adding `PathWalkOption.BREADTH_FIRST`
     * would still be parents-first, but any change that emits children before their parent — or any
     * caller that reorders the manifest — silently breaks restore, so preserve it.
     *
     * A single-file target gets a one-entry manifest whose `relPath` is `""`, the same sentinel the
     * directory branch uses for the deleted root itself: in both cases it means "the deleted node",
     * resolved by the restore against the target path rather than beneath it.
     */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    internal fun deleteSubtree(
        server: MinecraftServer,
        player: ServerPlayer,
        subpath: String,
        target: Path,
    ): DeleteOutcome {
        // Checked once, up front, rather than inferred from bankFile returning null: with history
        // off EVERY file "fails" to bank, and reporting that per delete would turn a supported
        // configuration into one that errors on every successful operation. Skipping the walk
        // entirely also spares the reads whose results could not be stored anyway.
        if (!SharedSettings.localHistoryEnabled) {
            LOGGER.debug("[project/delete] {} is not undoable: local history is disabled", subpath)
            deleteAndTearDown(server, player, subpath, target)?.let { return it }
            return DeleteOutcome.DeletedHistoryDisabled
        }

        val manifest = mutableListOf<ManifestEntry>()
        val banked = mutableListOf<BankedFile>()
        var bankFailure: String? = null

        if (target.isDirectory()) {
            manifest.add(ManifestEntry("", isFolder = true))
            // walk() has no error-tolerant mode, so an unreadable subdirectory throws out of the
            // sequence. Left unguarded that would propagate through handleDelete into
            // MinecraftServer.execute, which logs and swallows it -- the player would get neither a
            // delete nor an error. Nothing has been unlinked at this point, so reporting a plain
            // failure here is honest, and matches every other fallible IO in this file.
            runCatching {
                target.walk(PathWalkOption.INCLUDE_DIRECTORIES).forEach { entry ->
                    if (entry == target) return@forEach
                    val rel = target.relativize(entry).joinToString("/")
                    if (entry.isDirectory()) {
                        manifest.add(ManifestEntry(rel, isFolder = true))
                    } else {
                        manifest.add(ManifestEntry(rel, isFolder = false))
                        when (val revision = bankFile(server, entry)) {
                            null -> bankFailure = bankFailure ?: "could not bank '$rel'"
                            else -> banked.add(BankedFile(rel, entry.toAbsolutePath(), revision))
                        }
                    }
                }
            }.exceptionOrNull()?.let { e ->
                LOGGER.error("[project/delete] walk of {}: {}", subpath, e.message, e)
                return DeleteOutcome.Failed("delete failed: ${e.message}")
            }
        } else {
            manifest.add(ManifestEntry("", isFolder = false))
            when (val revision = bankFile(server, target)) {
                null -> bankFailure = "could not bank '$subpath'"
                else -> banked.add(BankedFile("", target.toAbsolutePath(), revision))
            }
        }

        deleteAndTearDown(server, player, subpath, target)?.let { return it }

        bankFailure?.let { return DeleteOutcome.DeletedUnbankable(it) }
        return DeleteOutcome.Deleted(EditorUndoCommand.Delete(subpath, manifest, banked))
    }

    /**
     * Unlink [target] and tear down everything keyed to [subpath]. Returns [DeleteOutcome.Failed]
     * when the unlink itself failed — in which case NOTHING has been unplaced and the world is
     * untouched, so the error the player sees is the whole story — and null on success.
     *
     * Split out of [deleteSubtree] only so the history-disabled path can reuse it without walking
     * and reading a subtree whose bytes could not be stored anyway. The ordering below is the whole
     * reason this is one function: every step depends on the unlink having already succeeded.
     */
    private fun deleteAndTearDown(
        server: MinecraftServer,
        player: ServerPlayer,
        subpath: String,
        target: Path,
    ): DeleteOutcome.Failed? {
        try {
            if (target.isDirectory()) {
                // copyRecursively's counterpart returns false rather than throwing on a partial
                // failure, so an unchecked call would silently report success for a subtree that is
                // still half on disk.
                if (!target.toFile().deleteRecursively()) {
                    error("could not delete every entry under $subpath")
                }
            } else {
                target.deleteExisting()
            }
        } catch (e: Exception) {
            LOGGER.error("[project/delete] {}: {}", subpath, e.message, e)
            return DeleteOutcome.Failed("delete failed: ${e.message}")
        }

        // structureSubpaths(), not placedStructureSubpaths(): a place that errored partway leaves a
        // region assignment with no placed box, and that entry must go too.
        val registry = EditorDimRegistry.of(server)
        val doomed = registry.structureSubpaths().filter { it == subpath || it.startsWith("$subpath/") }
        for (doomedSubpath in doomed) {
            // unplaceStructure BEFORE clearBounds, never after: clearBounds writes AIR through the
            // 3-arg level.setBlock, which the setBlock mixin hooks unconditionally, so a
            // still-registered subpath would have those very writes re-marked dirty by the mixin.
            val box = registry.unplaceStructure(doomedSubpath)
            if (box != null) {
                StructurePersistence.clearBounds(registry.projectLevel(), box.origin, box.size)
            }
        }

        val autoSave = StructureAutoSave.of(server)
        for (dirty in autoSave.dirtySubpaths().filter { it == subpath || it.startsWith("$subpath/") }) {
            autoSave.clear(dirty)
            // The backoff map is keyed by subpath too; leaving an entry behind leaks one per delete
            // for the life of the server.
            StructureCommit.clearBackoff(server, dirty)
        }

        EditorSession.clearSessionUnder(player.uuid, subpath)
        return null
    }

    /**
     * Bank one file's current bytes, returning the revision or null on any failure.
     *
     * A `.nbt` goes through the typed path so its revision carries real size metadata (matching
     * what `handlePlaceStructure` banks); anything else goes through `writeRawRevision`. A `.nbt`
     * that fails to parse as NBT falls back to raw bytes rather than being lost — the goal is
     * restorability, not a well-formed structure record.
     */
    private fun bankFile(server: MinecraftServer, file: Path): Revision? {
        val bytes = try { file.readBytes() } catch (e: Exception) {
            LOGGER.error("[project/delete] read for banking '{}': {}", file, e.message, e)
            return null
        }
        if (file.name.endsWith(".nbt", ignoreCase = true)) {
            val tag = runCatching { NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap()) }.getOrNull()
            if (tag != null) {
                val template = StructureTemplate()
                template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag)
                val size = template.size
                return LocalHistoryStore.writeRevision(
                    file, tag, size.x, size.y, size.z,
                    blockCount = 0, reason = LocalHistoryStore.REASON_PRE_DELETE,
                )
            }
        }
        return LocalHistoryStore.writeRawRevision(file, bytes, LocalHistoryStore.REASON_PRE_DELETE)
    }
}
