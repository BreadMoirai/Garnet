package com.breadmoirai.garnet.editor.undo

import com.breadmoirai.garnet.editor.history.data.Revision
import java.nio.file.Path

/** Which flavour of relocation produced a [EditorUndoCommand.Relocate] — messages only. */
enum class RelocateKind { RENAME, MOVE }

/** Which create-a-file handler produced a [EditorUndoCommand.CreateFile]. */
enum class CreatedFileKind { STRUCTURE, SPEC }

/** One node of a deleted subtree, relative to the deleted root. */
data class ManifestEntry(val relPath: String, val isFolder: Boolean)

/**
 * One banked file of a deleted subtree.
 *
 * [relPath] is relative to the deleted root and is what a restore writes back to, resolved against
 * the CURRENT project root. [absolutePath] is the file's path as it stood at delete time and is the
 * `LocalHistoryStore` key — that store hashes the absolute path, and a deleted file's history
 * directory deliberately outlives the file. The two are separate fields because the project root
 * can be repointed ("Open Folder…") between the delete and the undo.
 */
data class BankedFile(val relPath: String, val absolutePath: Path, val revision: Revision)

/**
 * A record of a file operation the server actually performed, carrying everything its inverse needs.
 *
 * Deliberately NOT the C2S packet that triggered it: `DuplicatePathC2S` does not say what name the
 * server derived, and `DeletePathC2S` carries neither the subtree shape nor its contents. See
 * docs/persistence/editor-undo-stack.md.
 *
 * Named `EditorUndoCommand`, not `EditorCommand` — the latter is the brigadier command object in
 * `editor/command/`.
 */
sealed interface EditorUndoCommand {

    /** Human-readable, rendered verbatim in the toolbar tooltip as "Undo <label>". */
    val label: String

    /**
     * The three create-shaped commands each carry a nullable [banked].
     *
     * Undoing a create means deleting what was created — and the content of that node cannot be
     * reconstructed from the command, because it came from a create handler rather than from
     * anything recorded here. So the undo's own `deleteSubtree` banks it, and the resulting
     * [Delete] is stapled onto the command as it moves to the redo deque. Redo is then a restore.
     * Null means "not undone yet" (fresh command) or "the removal could not be banked", in which
     * case redo is unavailable and says so.
     */
    data class CreateFolder(
        val subpath: String,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "create folder '$subpath'"
    }

    data class CreateFile(
        val subpath: String,
        val kind: CreatedFileKind,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "create '$subpath'"
    }

    /** [createdSubpath] is the server-derived copy name, which is the whole reason this exists. */
    data class Duplicate(
        val createdSubpath: String,
        val banked: Delete? = null,
    ) : EditorUndoCommand {
        override val label get() = "duplicate '$createdSubpath'"
    }

    data class Relocate(
        val oldSubpath: String,
        val newSubpath: String,
        val kind: RelocateKind,
    ) : EditorUndoCommand {
        override val label get() = when (kind) {
            RelocateKind.RENAME -> "rename to '$newSubpath'"
            RelocateKind.MOVE -> "move to '$newSubpath'"
        }
    }

    data class Delete(
        val rootSubpath: String,
        val manifest: List<ManifestEntry>,
        val banked: List<BankedFile>,
    ) : EditorUndoCommand {
        override val label get() = "delete '$rootSubpath'"
    }

    /**
     * A Local History restore. Reversible without carrying any content: the pre-restore state was
     * itself banked by the restore's own quiesce, so undo is just *the same operation aimed at
     * [fromTimestampMillis]*, and redo aims back at [toTimestampMillis].
     */
    data class RestoreRevision(
        val subpath: String,
        val fromTimestampMillis: Long,
        val toTimestampMillis: Long,
    ) : EditorUndoCommand {
        override val label get() = "restore '$subpath'"
    }
}
