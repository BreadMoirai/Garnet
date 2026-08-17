package com.breadmoirai.garnet.editor.explorer.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.toList

data class ProjectLeaf(val subpath: String, val specFiles: List<String>)

data class EditorFolderTree(
    val leaves: List<ProjectLeaf>,
    val intermediates: Set<String>,
) {
    companion object {
        private const val SPEC_EXT = ".spec.kts"

        fun scan(root: EditorRoot): EditorFolderTree {
            if (!Files.isDirectory(root.path)) return EditorFolderTree(emptyList(), emptySet())

            val leaves = mutableListOf<ProjectLeaf>()
            val intermediates = sortedSetOf<String>()

            Files.walk(root.path).use { stream ->
                stream.filter { it.isDirectory() && it != root.path }.toList().sorted().forEach { dir ->
                    val rel = root.path.relativize(dir).toString().replace('\\', '/')
                    val specs = Files.list(dir).use { s ->
                        s.filter { it.isRegularFile() && it.name.endsWith(SPEC_EXT) }
                            .map { it.name }
                            .toList()
                            .sorted()
                    }
                    val hasSubdirs = Files.list(dir).use { s -> s.anyMatch { it.isDirectory() } }
                    if (specs.isNotEmpty()) leaves.add(ProjectLeaf(rel, specs))
                    if (hasSubdirs) intermediates.add(rel)
                }
            }

            return EditorFolderTree(leaves.sortedBy { it.subpath }, intermediates)
        }
    }
}
