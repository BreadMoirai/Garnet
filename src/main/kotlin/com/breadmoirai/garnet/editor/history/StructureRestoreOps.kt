package com.breadmoirai.garnet.editor.history

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.network.EditorHandlerSupport.commitDirtyUnder
import com.breadmoirai.garnet.editor.structure.CommitOutcome
import com.breadmoirai.garnet.editor.structure.StructureCommit
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.world.EditorRootResolver
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import net.minecraft.server.MinecraftServer
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Garnet")

/** What a restore attempt produced. Callers phrase their own player-facing message. */
sealed interface RestoreOutcome {
    /**
     * [fromTimestampMillis] is the revision that was newest BEFORE the restore — what an undo of
     * this restore aims back at. [toTimestampMillis] is the revision that was restored.
     */
    data class Restored(
        val subpath: String,
        val fromTimestampMillis: Long,
        val toTimestampMillis: Long,
    ) : RestoreOutcome

    data class Refused(val reason: String) : RestoreOutcome
}

/**
 * Moves a placed structure's world copy and `.nbt` back to a banked revision.
 *
 * **Only ever operates on a structure that is currently placed.** That invariant is what keeps this
 * a single code path: there is always a footprint to clear and a [StructureCommit] to write through,
 * so nothing here writes a `.nbt` directly.
 *
 * Player-independent by design: it returns a [RestoreOutcome] instead of sending packets, so the
 * undo/redo replay and the packet handler can each phrase their own message, and so it is testable
 * without a network round trip. See
 * docs/superpowers/specs/2026-08-15-structure-local-history-panel-design.md.
 */
object StructureRestoreOps {

    fun restore(server: MinecraftServer, subpath: String, timestampMillis: Long): RestoreOutcome {
        if (!subpath.endsWith(".nbt")) return RestoreOutcome.Refused("not a structure file: $subpath")
        val root = EditorRootResolver.rootFor(server)
            ?: return RestoreOutcome.Refused("project-root not configured")
        val file = root.resolveSubpath(subpath)
            ?: return RestoreOutcome.Refused("subpath not found or escapes root: $subpath")

        val registry = EditorDimRegistry.of(server)
        // The invariant. Not a fallback to writing the file: an unplaced restore has no world copy
        // to reconcile, and allowing it would fork every step below.
        val placed = registry.placedBoxOf(subpath)
            ?: return RestoreOutcome.Refused("place the structure before restoring: $subpath")

        val revisions = LocalHistoryStore.revisions(file)
        // `lastOrNull`, not `firstOrNull`: `LocalHistoryStore.writeRevision` only disambiguates
        // same-millisecond writes in the blob FILENAME, so two revisions can share a timestamp.
        // Picking the newest of such a group makes the newest-revision refusal below exact and
        // matches what a caller holding `revisions().last()` means by that timestamp.
        val target = revisions.lastOrNull { it.timestampMillis == timestampMillis }
            ?: return RestoreOutcome.Refused("no such revision for $subpath")
        val newest = revisions.last()
        if (target.timestampMillis == newest.timestampMillis) {
            // Revisions are POST-commit: the newest one IS what is on disk, so restoring it is a
            // no-op. The panel renders that row inert; this is the server-side half of the rule.
            return RestoreOutcome.Refused("that revision is already the current content")
        }
        // A raw revision is a `pre-delete` bank of something that is not a structure. Detect it by
        // the garnetRaw marker, NEVER by size: a real .nbt that parses but is not a template records
        // zero sizes too, so size cannot tell the two apart.
        if (LocalHistoryStore.readRawBytes(file, target) != null) {
            return RestoreOutcome.Refused("not a structure snapshot: ${target.reason} revision")
        }
        val tag = LocalHistoryStore.readTag(file, target)
            ?: return RestoreOutcome.Refused("revision blob is missing or unreadable")

        // Quiesce, and ABORT if it fails. Deliberately unlike deleteSubtree's best-effort quiesce:
        // a delete is a request to destroy that content anyway, but here a failed quiesce followed
        // by the re-place below would silently eat live edits nobody asked to lose.
        commitDirtyUnder(server, subpath)?.let {
            return RestoreOutcome.Refused("could not save pending edits before restoring: $it")
        }

        // Re-read AFTER the quiesce: that commit banked a revision, and it is the one an undo of
        // this restore has to aim back at.
        val fromTimestamp = LocalHistoryStore.revisions(file).last().timestampMillis

        val level = registry.projectLevel()
        val origin = registry.getOrAssignStructureRegion(subpath)
        val width = SharedSettings.structureRegionChunks * 16
        // Clear the OLD footprint before placing. A restored structure may be smaller than what it
        // replaces, and the new box is the only thing bounding the commit's scan below — clearing by
        // the new box would strand the old footprint's blocks where the next commit captures them
        // straight back in.
        StructurePersistence.clearBounds(level, placed.origin, placed.size)
        val newBox = StructurePersistence.placeStructureTagCentered(
            tag, level, origin, width, level.minY, level.maxY, SharedSettings.projectGridYBase,
        ) ?: return RestoreOutcome.Refused("could not place revision content for $subpath")
        registry.setPlacedBox(subpath, newBox)
        // No teleport, unlike placeStructureFrom: the player is already standing in this region and
        // being flung to the new roof height mid-restore is disorienting.

        return when (val outcome = StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_RESTORE)) {
            is CommitOutcome.Committed, is CommitOutcome.NoChange -> {
                val toTimestamp = target.timestampMillis
                LOGGER.debug("[restore] {} -> {}", subpath, toTimestamp)
                RestoreOutcome.Restored(subpath, fromTimestamp, toTimestamp)
            }
            is CommitOutcome.Failed ->
                // The world holds the restored content; disk does not. Retrying is the recovery.
                RestoreOutcome.Refused("restore placed but could not be saved: ${outcome.reason}")
            is CommitOutcome.NotApplicable ->
                RestoreOutcome.Refused("restore placed but could not be saved: $subpath is not committable")
        }
    }
}
