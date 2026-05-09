package com.breadmoirai.redstonespecs.managed

import java.nio.file.Path
import java.security.MessageDigest

object ManagedSaveNaming {
    /**
     * Filesystem-safe, MC-save-name-safe label for a managed-root path. Used as the
     * `<saveDir>` name for the per-root persistent singleplayer world.
     *
     * Strategy: `managed-` prefix + last path component (sanitized to `[a-zA-Z0-9_-]`)
     * + `-<8-hex>` SHA-1 hash of the absolute path. The hash suffix disambiguates roots
     * whose tails sanitize to the same string (e.g. `/a/specs` vs `/b/specs`), which would
     * otherwise share a save dir.
     */
    fun saveName(root: Path): String {
        val tail = (root.fileName?.toString() ?: "root")
        val sanitized = tail.map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_') c else '_' }.joinToString("")
        val absHash = pathHash(root.toAbsolutePath().toString())
        return "managed-${sanitized.ifBlank { "root" }}-$absHash"
    }

    private fun pathHash(s: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        return md.take(4).joinToString("") { "%02x".format(it) }  // 8 hex chars
    }
}
