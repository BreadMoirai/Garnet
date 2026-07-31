package com.breadmoirai.garnet.editor.ui

import com.breadmoirai.garnet.editor.data.NewNodeKind

/**
 * The Explorer's in-tree text-field state. Exactly one edit can be active at a time.
 *
 * Both variants render as a `TextField` in place of a tree row's label; [Creating] additionally
 * needs a row to exist at all, which is what [pendingIdFor] is for — see
 * [ExplorerTreeState.buildTreeFrom].
 */
sealed interface ExplorerEdit {
    /** Typing the name of a new [kind] to be created inside the folder at [parentPath]. */
    data class Creating(val parentPath: String, val kind: NewNodeKind) : ExplorerEdit

    /** Typing a replacement name for the node at [path], whose current name is [original]. */
    data class Renaming(val path: String, val original: String) : ExplorerEdit

    companion object {
        /**
         * Tree id of the placeholder row for a pending create inside [parentPath].
         *
         * NUL is illegal in a filename on every filesystem this mod supports, so this id can never
         * collide with a real `/`-joined path — which matters because Jewel keys selection and
         * expansion off these ids, and a collision would let the placeholder hijack a real node's
         * state.
         */
        fun pendingIdFor(parentPath: String): String = "$parentPath/\u0000new"

        fun isPendingId(id: String): Boolean = id.endsWith("/\u0000new")
    }
}
