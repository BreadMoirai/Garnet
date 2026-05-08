package com.breadmoirai.redstonespecs.managed

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.streams.toList

data class ManagedLeaf(val subpath: String, val specFiles: List<String>)

data class ManagedFolderTree(
    val leaves: List<ManagedLeaf>,
    val intermediates: Set<String>,
) {
    companion object {
        private const val SPEC_EXT = ".spec.kts"

        fun scan(root: ManagedRoot): ManagedFolderTree {
            if (!Files.isDirectory(root.path)) return ManagedFolderTree(emptyList(), emptySet())

            val leaves = mutableListOf<ManagedLeaf>()
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
                    if (specs.isNotEmpty()) leaves.add(ManagedLeaf(rel, specs))
                    if (hasSubdirs) intermediates.add(rel)
                }
            }

            return ManagedFolderTree(leaves.sortedBy { it.subpath }, intermediates)
        }
    }
}
