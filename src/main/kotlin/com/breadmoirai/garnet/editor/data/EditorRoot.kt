package com.breadmoirai.garnet.editor.data

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A managed-specs root: an absolute folder containing nested folders of `.spec.kts` files.
 * Path traversal is rejected at this boundary — every server-side action that takes a
 * client-supplied subpath MUST go through `resolveSubpath` and reject `null`.
 */
data class EditorRoot(val path: Path) {
    init {
        require(path.isAbsolute) { "EditorRoot path must be absolute: $path" }
    }

    /**
     * Returns the absolute, real path of `subpath` if and only if it stays under `path`.
     * Empty subpath is allowed and returns the root itself. Absolute or escaping subpaths return null.
     */
    fun resolveSubpath(subpath: String): Path? {
        val isAbs = try {
            Path.of(subpath).isAbsolute
        } catch (e: java.nio.file.InvalidPathException) {
            return null
        }
        if (isAbs) return null
        val candidate = path.resolve(subpath).normalize()
        if (!candidate.exists()) return null
        // toRealPath can throw IOException (disconnected network root, a transient lock racing the
        // exists() check above, ...). This is now reachable from the END_SERVER_TICK path via
        // StructureCommit.commit -> resolveSubpath, so an uncaught exception here is a hard server
        // crash (B4). Treat an unresolvable path the same as a missing one: null, which routes
        // callers into their existing "not found" handling (NotApplicable for StructureCommit).
        val real = runCatching { candidate.toRealPath() }.getOrNull() ?: return null
        val rootReal = runCatching { path.toRealPath() }.getOrNull() ?: return null
        return if (real.startsWith(rootReal)) real else null
    }

    fun isDirectory(subpath: String): Boolean {
        val resolved = resolveSubpath(subpath) ?: return false
        return resolved.isDirectory()
    }
}
