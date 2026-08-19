package com.breadmoirai.garnet.editor.structure.data

/**
 * The result of a single [com.breadmoirai.garnet.editor.structure.ops.StructureCommit.commit]
 * attempt. Deliberately distinguishes "there was nothing to do" from "an attempt was made and it
 * failed" (Task 7 fix round 1 / Finding 4) — a caller that collapses both into a bare `null` cannot
 * tell a genuinely clean structure apart from one whose edits are still only in the world and never
 * made it to disk, which is exactly the distinction a user pressing "Save Structure" needs reported
 * honestly.
 */
sealed interface CommitOutcome {
    /**
     * A real write landed; [structure] records what changed.
     *
     * This deliberately holds the domain record and **not** the `StructureAutoSavedS2C` wire
     * payload it is eventually broadcast as. `data` is a leaf layer; coupling a pure value type to
     * the wire format inverted that. Whoever broadcasts converts at the network boundary — see
     * [CommittedStructure].
     */
    data class Committed(val structure: CommittedStructure) : CommitOutcome

    /** The capture already matches the committed `.nbt` (or there was nothing to scan at all). */
    data object NoChange : CommitOutcome

    /**
     * [com.breadmoirai.garnet.editor.structure.ops.StructureCommit.commit] was called for a subpath
     * that isn't placed, or whose root/file doesn't resolve. Unlike [Failed], the dirty state is
     * cleared here ONLY when the structure isn't placed — see the judgement call recorded on
     * [com.breadmoirai.garnet.editor.structure.ops.StructureCommit.commit]'s KDoc for why an
     * unresolvable root/file must NOT clear the flag while the structure is still placed.
     */
    data object NotApplicable : CommitOutcome

    /** The history write or the `.nbt` write genuinely failed; the dirty state was NOT cleared. */
    data class Failed(val reason: String) : CommitOutcome
}
