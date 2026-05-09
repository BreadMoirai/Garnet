package com.breadmoirai.redstonespecs.persistence

import java.nio.file.Files
import java.nio.file.Path

/**
 * Server-side scan of the world's spec directory. Returns a sorted list of
 * `.spec.kts` filenames (relative to [specsDir]). Used by the runner-screen
 * picker to populate its dropdown.
 */
object SpecDirectoryScan {
    fun list(specsDir: Path): List<String> {
        if (!Files.isDirectory(specsDir)) return emptyList()
        return Files.list(specsDir).use { stream ->
            stream
                .filter { it.fileName.toString().endsWith(".spec.kts") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }
    }
}
