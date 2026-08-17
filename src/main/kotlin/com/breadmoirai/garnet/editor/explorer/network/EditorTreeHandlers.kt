package com.breadmoirai.garnet.editor.explorer.network

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.data.*
import com.breadmoirai.garnet.editor.network.*
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.fail
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendTree
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.sendUndoState
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewSpec
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.undo.CreatedFileKind
import com.breadmoirai.garnet.editor.undo.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.EditorUndoStack
import com.breadmoirai.garnet.editor.world.*
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

private val LOGGER = LoggerFactory.getLogger("Garnet")

object EditorTreeHandlers {

    fun handleListTree(server: MinecraftServer, player: ServerPlayer) {
        sendTree(server, player)
    }

    fun handleLoadFolder(server: MinecraftServer, player: ServerPlayer, payload: LoadEditorFolderC2S) {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        if (root.resolveSubpath(payload.subpath) == null) {
            fail(player, "subpath not found or escapes root: ${payload.subpath}"); return
        }
        val ok = EditorTeleport.toFolder(server, player, payload.subpath)
        if (!ok) {
            fail(player, "folder not placed: ${payload.subpath}"); return
        }
        val world = EditorWorld.get(server)
        val loadedIds = world?.perFolder?.get(payload.subpath)?.keys?.toList().orEmpty()
        ServerPlayNetworking.send(player, EditorFolderLoadedS2C(
            subpath = payload.subpath,
            loadedSpecIds = loadedIds,
            parseErrors = emptyList(),
            layoutErrors = emptyList(),
        ))
    }

    fun handleUnload(server: MinecraftServer, player: ServerPlayer) {
        EditorSession.clear(player.uuid)
        ServerPlayNetworking.send(player, EditorSaveReportS2C(emptyList()))
    }

    fun handleSaveNow(server: MinecraftServer, player: ServerPlayer) {
        val results = EditorDimLifecycle.saveAll(server)
        ServerPlayNetworking.send(player, EditorSaveReportS2C(results.map(::formatSaveResult)))
    }

    fun handleNewSpec(server: MinecraftServer, player: ServerPlayer, payload: NewEditorSpecC2S) {
        val activeSubpath = EditorSession.get(player.uuid)?.activeSubpath ?: run {
            fail(player, "no folder selected"); return
        }
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return
        }
        val world = EditorWorld.get(server)
        val folderAbsolute = world?.folderAbsoluteByPath?.get(activeSubpath)
            ?: root.resolveSubpath(activeSubpath)
            ?: run {
                fail(player, "active folder not resolvable: $activeSubpath"); return
            }
        val createdFileName = try {
            EditorNewSpec.create(folderAbsolute, payload.name).name
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] create {}/{}: {}", activeSubpath, payload.name, e.message, e)
            fail(player, "new-spec failed: ${e.message}"); return
        }
        val report = try {
            EditorDimLifecycle.placeFolder(server, root, activeSubpath)
        } catch (e: Exception) {
            LOGGER.error("[project/new-spec] re-place {}: {}", activeSubpath, e.message, e)
            fail(player, "re-place failed: ${e.message}"); return
        }
        EditorUndoStack.push(player.uuid, EditorUndoCommand.CreateFile(
            if (activeSubpath.isEmpty()) createdFileName else "$activeSubpath/$createdFileName",
            CreatedFileKind.SPEC,
        ))
        sendUndoState(player)
        ServerPlayNetworking.send(player, EditorFolderLoadedS2C(
            subpath = report.subpath,
            loadedSpecIds = report.loaded,
            parseErrors = report.parseErrors.map { "${it.filename}: ${it.message}" },
            layoutErrors = report.errors.map { "${it.specId} (${it.filename}): ${it.reason}" },
        ))
    }

    fun handleSetRoot(server: MinecraftServer, player: ServerPlayer, payload: SetEditorRootC2S) {
        val abs = try {
            Path.of(payload.path).toAbsolutePath()
        } catch (e: java.nio.file.InvalidPathException) {
            fail(player, "invalid path: ${payload.path}"); return
        }
        if (!abs.isDirectory()) {
            fail(player, "not a folder: $abs"); return
        }
        // Flush every dirty structure against the OLD root BEFORE touching any state (B1). Once the
        // root swaps, StructureCommit.commit resolves subpaths against the NEW root, so a dirty
        // structure committed after the swap would capture the OLD root's world blocks and write
        // them over the NEW root's same-named file — silently destroying content that belongs to a
        // different project the player never touched.
        //
        // If any of those commits genuinely FAILS TO WRITE (locked file, read-only checkout, AV
        // scan), the swap is REFUSED rather than pushed through. The reset loop below unplaces
        // every structure and clears its blocks out of the world, so proceeding would destroy the
        // only remaining copy of those edits — the world blocks themselves — with nothing on disk
        // and no message to the player. Same rule `handleRename` applies before its file move.
        //
        // A structure that is merely UNRESOLVABLE (no root configured, file missing) is NOT grounds
        // to refuse, unlike in handleRename: nothing could be written for it however long we wait,
        // and "Open Folder" is the very action that fixes an unresolvable root — refusing here
        // would leave a player with a stale placed-and-dirty structure no way out at all. Log it
        // and proceed.
        val uncommitted = StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
        val writeFailures = uncommitted.filter { it.writeFailed }
        if (writeFailures.isNotEmpty()) {
            fail(player,
                "open folder cancelled: unsaved edits could not be committed for " +
                    writeFailures.joinToString(", ") { "'${it.subpath}' (${it.reason})" },
            )
            return
        }
        for (stale in uncommitted) {
            LOGGER.warn(
                "[project/setRoot] '{}' is dirty but unresolvable ({}); swapping root anyway",
                stale.subpath, stale.reason,
            )
        }

        val root = EditorRoot(abs)
        SharedSettings.projectRootPath = abs.toString()
        EditorServerContext.set(server, EditorServerContext(root))

        // Fully reset per-structure state so nothing from the old root carries across: a leftover
        // placedBox would let a later commit for the same subpath under the NEW root capture the
        // OLD root's leftover world blocks, and a leftover region assignment would place the NEW
        // root's file on top of the OLD root's blocks (B1).
        //
        // Iterate structureSubpaths(), not placedStructureSubpaths(): a subpath that got a region
        // assignment but never a placed box — handlePlaceStructure erroring between
        // getOrAssignStructureRegion and setPlacedBox — is absent from the latter, so its
        // assignment would survive the reset and outlive the root it belonged to.
        val registry = EditorDimRegistry.of(server)
        val autoSave = StructureAutoSave.of(server)
        for (subpath in registry.structureSubpaths()) {
            autoSave.clear(subpath)
            StructureCommit.clearBackoff(server, subpath)
        }
        // Clear the old root's blocks out of the project level as the assignments are dropped.
        // Regions are never recycled (nextStructureIndex is monotonic), so blocks left behind here
        // are unreachable for the rest of the session — harmless once, unbounded over many swaps.
        // Each box is the structure's own tight footprint, so this stays far away from a
        // region-wide scan.
        val level = registry.projectLevel()
        for (box in registry.resetAllStructures()) {
            StructurePersistence.clearBounds(level, box.origin, box.size)
        }

        EditorDimLifecycle.placeAll(server, root)
        sendTree(server, player)
    }

    private fun formatSaveResult(r: CellSaveResult): String =
        "${r.specId}|saved=${r.saved}${r.error?.let { "|err=$it" } ?: ""}"
}
