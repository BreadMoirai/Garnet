package com.breadmoirai.redstonespecs.managed

import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * A managed-specs root: an absolute folder containing nested folders of `.spec.kts` files.
 * Path traversal is rejected at this boundary — every server-side action that takes a
 * client-supplied subpath MUST go through `resolveSubpath` and reject `null`.
 */
data class ManagedRoot(val path: Path) {
    init {
        require(path.isAbsolute) { "ManagedRoot path must be absolute: $path" }
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
        val real = candidate.toRealPath()
        val rootReal = path.toRealPath()
        return if (real.startsWith(rootReal)) real else null
    }

    fun isDirectory(subpath: String): Boolean {
        val resolved = resolveSubpath(subpath) ?: return false
        return resolved.isDirectory()
    }
}
