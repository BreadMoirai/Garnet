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
     * The typed text turned into the actual on-disk name for a *rename* of [currentName]: trimmed,
     * and carrying [currentName]'s extension over when the user typed a bare stem.
     *
     * Renaming "house.nbt" to "cottage" means "cottage.nbt", not an extensionless file the Explorer
     * would render as an unknown type and refuse to place — retyping the extension every time is
     * busywork, and forgetting it silently breaks the file. Typing an extension explicitly still
     * wins, including changing it ("cottage.txt") or, for the deliberate case, `resolveFinalName`'s
     * `.nbt` normalization does not apply here: a rename must be able to produce any name the user
     * actually asked for.
     *
     * [isFolder] suppresses the whole rule: folder names may legitimately contain dots ("my.stuff"),
     * so there is no extension there to preserve. The caller knows which it has from the node type.
     *
     * A leading dot names the file rather than introducing an extension (".gitignore"), so it is
     * neither treated as an extension on [currentName] nor as one on [typed].
     */
    fun resolveRenameName(typed: String, currentName: String, isFolder: Boolean): String {
        val trimmed = typed.trim()
        if (isFolder || trimmed.isEmpty()) return trimmed
        if (trimmed.lastIndexOf('.') > 0) return trimmed
        val dot = currentName.lastIndexOf('.')
        return if (dot > 0) trimmed + currentName.substring(dot) else trimmed
    }

    /**
     * The name a duplicate of [sourceName] should take among [siblings]: `house.nbt` →
     * `house copy.nbt` → `house copy 2.nbt`, counting up until the name is free.
     *
     * The suffix goes BEFORE the last dot so a duplicated structure is still a `.nbt` the Explorer
     * will place — "house.nbt copy" would be an inert file the tree renders as an unknown type.
     *
     * [isFolder] exists because a folder has no extension to preserve, and folder names may
     * legitimately contain dots ("my.stuff"): splitting those on the last dot would produce
     * "my copy.stuff". The caller knows which it has — the server from `isDirectory()`, the client
     * from the node type — so this never has to guess from the name's shape.
     *
     * Collision matching is case-insensitive, matching [validate]: a candidate differing from an
     * existing sibling only by case would be rejected by [validate] a moment later on the
     * case-insensitive filesystems this project runs on (NTFS, APFS).
     */
    fun duplicateName(sourceName: String, siblings: Collection<String>, isFolder: Boolean): String {
        // dot > 0, not >= 0: a leading dot (".gitignore") names the file, it does not introduce an
        // extension, so such a name must keep its dot in the stem.
        val dot = if (isFolder) -1 else sourceName.lastIndexOf('.')
        val stem = if (dot > 0) sourceName.substring(0, dot) else sourceName
        val extension = if (dot > 0) sourceName.substring(dot) else ""

        val taken = siblings.mapTo(HashSet()) { it.lowercase() }
        var candidate = "$stem copy$extension"
        var counter = 2
        while (candidate.lowercase() in taken) {
            candidate = "$stem copy $counter$extension"
            counter++
        }
        return candidate
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
