package com.breadmoirai.redstonespecs.project

import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** A node in a project's file tree. Stores only its own [name]; paths are computed on demand. */
sealed interface FileTreeNode {
    val name: String
}

/** A folder. [children] are sorted folders-first, then files, alphabetical case-insensitive. */
data class FolderNode(
    override val name: String,
    val children: List<FileTreeNode>,
) : FileTreeNode

/** A file. [extension] is the lowercased last-dot extension, "" when the name has no dot. */
data class FileNode(
    override val name: String,
    val extension: String,
) : FileTreeNode

// Folders before files (false < true), then case-insensitive name order.
private val CHILD_ORDER: Comparator<FileTreeNode> =
    compareBy<FileTreeNode> { it !is FolderNode }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

/**
 * Recursively scan [path] into a [FolderNode] mirroring the filesystem: all files (any
 * extension) and all folders, including empty ones. A non-existent or non-directory path
 * yields an empty root folder named after the path's last segment.
 */
fun scanFolder(path: Path): FolderNode {
    if (!path.isDirectory()) return FolderNode(path.name, emptyList())
    val children = path.listDirectoryEntries()
        .map { entry ->
            if (entry.isDirectory()) scanFolder(entry)
            else FileNode(entry.name, entry.extension.lowercase())
        }
        .sortedWith(CHILD_ORDER)
    return FolderNode(path.name, children)
}

/**
 * Every node under this folder paired with its `/`-joined path relative to this folder, in
 * depth-first pre-order. The receiver itself is emitted first as `"" to this`. Child order
 * follows [FolderNode.children] (folders-first, then files, alphabetical).
 */
fun FolderNode.walk(): Sequence<Pair<String, FileTreeNode>> = sequence {
    suspend fun SequenceScope<Pair<String, FileTreeNode>>.visit(prefix: String, node: FileTreeNode) {
        yield(prefix to node)
        if (node is FolderNode) {
            for (child in node.children) {
                val childPath = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                visit(childPath, child)
            }
        }
    }
    visit("", this@walk)
}

/**
 * Resolve a `/`-joined [path] (relative to this folder; `""` = the folder itself) to a node,
 * or null if any segment is missing or a non-final segment is a file. Matches children by
 * [name]; does not depend on holding a specific node instance.
 */
fun FolderNode.resolve(path: String): FileTreeNode? {
    if (path.isEmpty()) return this
    var current: FileTreeNode = this
    for (segment in path.split('/')) {
        val folder = current as? FolderNode ?: return null
        current = folder.children.firstOrNull { it.name == segment } ?: return null
    }
    return current
}
