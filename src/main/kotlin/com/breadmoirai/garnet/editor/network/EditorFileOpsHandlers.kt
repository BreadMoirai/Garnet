package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.data.*
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.resolveParentFolder
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.siblingNames
import com.breadmoirai.garnet.editor.world.*
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectory
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

        // A placed structure is keyed by subpath in EditorDimRegistry, so renaming under it would
        // strand both the placed box and the region assignment if we don't unload/reload it. But the
        // move (an IO op — can fail on a lock, permission problem, etc.) must succeed FIRST: tearing
        // down the placed-structure state (clearing its blocks, dropping its registry keys) before
        // the move is confirmed would leave the structure's blocks erased and its registry entry gone
        // while the player is told the rename failed and the (untouched, still-old-named) file sits
        // there unrecoverably out of sync with the world. Only touch that state once the move is
        // confirmed.
        val registry = EditorDimRegistry.of(server)
        val wasPlaced = registry.placedBoxOf(payload.subpath)

        // Quiesce before the move: see EditorHandlerSupport.commitDirtyUnder for why a relocation
        // that skips this strands every pending edit beneath the renamed path.
        commitDirtyUnder(server, payload.subpath)?.let {
            fail(player, "rename failed: $it"); return
        }

        val target = parent.resolve(newName)
        try {
            source.moveTo(target)
        } catch (e: Exception) {
            LOGGER.error("[project/rename] {} -> {}: {}", payload.subpath, newSubpath, e.message, e)
            fail(player, "rename failed: ${e.message}"); return
        }

        // History is keyed by each file's own absolute path, so every moved .nbt (the renamed file
        // itself, or every descendant .nbt when a whole FOLDER was renamed) must carry its history
        // across, or it silently loses every revision it has accumulated (Finding 3). This cannot
        // itself fail the rename from the player's perspective — the file(s) already moved — so a
        // problem here is logged, not reported as a rename failure (Finding 6): sharing one `try`
        // with the file move above would have let a hypothetical history-move exception report
        // "rename failed" after the file had already relocated.
        try {
            LocalHistoryStore.moveDescendantHistories(source, target)
        } catch (e: Exception) {
            LOGGER.error(
                "[project/rename] history move for {} -> {}: {}", payload.subpath, newSubpath, e.message, e,
            )
        }

        if (wasPlaced != null) {
            // Drop the registry entry BEFORE clearing blocks, not after (Task 7 fix round 2 /
            // residual on Finding 1). clearBounds writes AIR through the 3-arg level.setBlock,
            // which the setBlock mixin hooks unconditionally for any successful server write; if
            // the OLD subpath were still registered at that instant, EditorDimRegistry.structureSubpathAt
            // would still attribute those positions to it, and the watcher would re-mark the OLD
            // subpath dirty immediately after the commit loop above just cleared it. Since
            // `wasPlaced.origin`/`wasPlaced.size` were already captured into a local before any
            // registry mutation, clearBounds needs nothing further from the registry, so dropping
            // the entry first is safe: with structureBySubpath no longer mapping the old subpath at
            // all (and its freed region never recycled — nextStructureIndex is monotonic), those
            // positions attribute to no subpath, and clearBounds's own writes cannot re-dirty
            // anything.
            registry.unplaceStructure(payload.subpath)
            StructurePersistence.clearBounds(registry.projectLevel(), wasPlaced.origin, wasPlaced.size)
        }

        if (wasPlaced != null) {
            EditorStructureHandlers.placeStructureFrom(server, player, newSubpath, target, "renamed to $newSubpath")
        }

        // Rekey every OTHER registry entry nested under the renamed path (e.g. structures placed
        // inside a renamed folder). The renamed node's own entry, if any, was already handled above by
        // the wasPlaced block, so by this point rekeyForRename's exact-match branch is a no-op for it —
        // only descendants still keyed under the old subpath remain to be moved.
        registry.rekeyForRename(payload.subpath, newSubpath)

        EditorSession.repointSession(player, payload.subpath, newSubpath)

        sendTree(server, player)
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
}
