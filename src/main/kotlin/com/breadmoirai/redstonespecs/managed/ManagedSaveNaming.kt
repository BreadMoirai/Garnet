package com.breadmoirai.redstonespecs.managed

import java.nio.file.Path

object ManagedSaveNaming {
    /**
     * Filesystem-safe, MC-save-name-safe label for a managed-root path. Used as the
     * `<saveDir>` name for the per-root persistent singleplayer world.
     *
     * Strategy: `managed-` prefix + last path component, sanitized to `[a-zA-Z0-9_-]`.
     * Collisions across distinct roots are possible but rare — UI surfaces the full path so
     * the user can rename a folder if needed. (No hash suffix in v1; revisit if collisions happen.)
     */
    fun saveName(root: Path): String {
        val tail = (root.fileName?.toString() ?: "root")
        val sanitized = tail.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }.joinToString("")
        return "managed-${sanitized.ifBlank { "root" }}"
    }
}
