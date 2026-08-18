package com.breadmoirai.garnet.editor.history.data

/**
 * One recorded state of a structure. [file] is the blob's filename inside the structure's history
 * directory (`<epochMillis>-<seq>.nbt`); everything else is metadata a browser can show without
 * reading the blob.
 */
data class Revision(
    val file: String,
    val timestampMillis: Long,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blockCount: Int,
    val reason: String,
)

/**
 * A structure's `index.json`. [absolutePath] records the path the directory was keyed from — for
 * hand-debugging an opaque hash directory, and to notice a hash collision rather than silently
 * interleaving two structures' revisions.
 */
data class HistoryIndex(
    val absolutePath: String,
    val revisions: List<Revision>,
)
