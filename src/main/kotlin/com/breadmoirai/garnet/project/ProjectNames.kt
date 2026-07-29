package com.breadmoirai.garnet.project

/** What an Explorer "New" action creates. */
enum class NewNodeKind { FOLDER, STRUCTURE }

/**
 * Name rules for Explorer create/rename, shared by the client's pre-commit check and the server's
 * re-check.
 *
 * One implementation on purpose: the client validates against its tree snapshot so an invalid name
 * never leaves the field, but that snapshot can be stale, so the server re-runs the identical rule
 * against the real filesystem. Two copies of this logic would drift into a UI that accepts what the
 * server rejects.
 */
object ProjectNames {

    /** The typed text turned into the actual on-disk name: trimmed, with `.nbt` added for structures. */
    fun resolveFinalName(typed: String, kind: NewNodeKind): String {
        val trimmed = typed.trim()
        if (kind != NewNodeKind.STRUCTURE) return trimmed
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.substringAfterLast('.', "").equals("nbt", ignoreCase = true)) trimmed
        else "$trimmed.nbt"
    }

    /**
     * Null when [finalName] is a usable name among [siblings], else the reason it is not.
     * [siblings] are the names already present in the destination folder.
     */
    fun validate(finalName: String, siblings: Collection<String>): String? {
        if (finalName.isBlank()) return "name must not be blank"
        if (finalName.contains('/') || finalName.contains('\\')) return "name must not contain a path separator"
        if (finalName == "." || finalName == "..") return "'$finalName' is not a valid name"
        if (siblings.any { it.equals(finalName, ignoreCase = true) }) return "'$finalName' already exists"
        return null
    }
}
