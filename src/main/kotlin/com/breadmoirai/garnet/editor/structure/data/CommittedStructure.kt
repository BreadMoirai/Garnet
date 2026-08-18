package com.breadmoirai.garnet.editor.structure.data

/**
 * What a successful structure commit actually wrote: which subpath, how big the result is, how
 * many blocks it holds, and when it landed.
 *
 * This is the *domain* record of a commit, deliberately distinct from
 * `structure/network/StructureAutoSavedS2C`, which is the wire form the same facts are broadcast
 * in. They currently carry identical fields, and that is not duplication to collapse: the payload's
 * shape is frozen by the network protocol (its field order *is* the stream codec — see
 * `PayloadIds.kt`), while this type is free to gain or lose fields as the commit pipeline changes.
 * Merging them would make every protocol-visible change look like a refactor and vice versa.
 *
 * Keeping them separate is also what lets `structure/data` stay a leaf. [CommitOutcome.Committed]
 * previously carried the payload directly, which inverted the layering — a pure value type coupled
 * to the wire format. The `CommittedStructure -> StructureAutoSavedS2C` mapping now lives at the
 * network boundary (`StructurePackets.kt`), which is the only place that should know the wire form
 * exists.
 */
data class CommittedStructure(
    val subpath: String,
    val sizeX: Int,
    val sizeY: Int,
    val sizeZ: Int,
    val blockCount: Int,
    val savedAtMillis: Long,
)
