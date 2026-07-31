package com.breadmoirai.garnet.editor.data

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
object EditorNames {

    /**
     * The typed text turned into the actual on-disk name: trimmed, with a lowercase `.nbt` extension
     * for structures.
     *
     * Normalizing the case here -- not just detecting it -- matters: this is the one place that
     * decides the final extension, and every consumer (e.g. `handleNewStructure`'s
     * `removeSuffix(".nbt")`) assumes a lowercase suffix it can strip with a plain, case-sensitive
     * call. Accepting "clock.NBT" case-insensitively but returning it un-normalized would leave that
     * removeSuffix a no-op, so `create()` appends its own ".nbt" on top and the file lands on disk as
     * "clock.NBT.nbt". Normalizing here keeps that a one-line, case-sensitive strip everywhere else.
     */
    fun resolveFinalName(typed: String, kind: NewNodeKind): String {
        val trimmed = typed.trim()
        if (kind != NewNodeKind.STRUCTURE) return trimmed
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.substringAfterLast('.', "").equals("nbt", ignoreCase = true))
            trimmed.substringBeforeLast('.') + ".nbt"
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
