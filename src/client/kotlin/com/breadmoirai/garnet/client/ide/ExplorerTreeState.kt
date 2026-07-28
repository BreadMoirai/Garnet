package com.breadmoirai.garnet.client.ide

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FileTreeNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.resolve
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.tree.Tree
import org.jetbrains.jewel.foundation.lazy.tree.TreeGeneratorScope
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree

/**
 * Expand/selection state for the Explorer tree, owned by Jewel.
 *
 * Jewel's [TreeState] is the single source of truth — there is deliberately no mirrored `expanded`
 * set or `selectedPath` field, because two copies of tree state drift. The trick that makes this
 * work is that `addNode`/`addLeaf` take an explicit `id`, so we use each node's `/`-joined path and
 * Jewel's own `openNodes`/`selectedKeys` sets simply *are* sets of path strings.
 *
 * [TreeState] is hoisted here rather than created with `rememberTreeState()` inside composition, so
 * packet handlers, panel actions, and clientTests can read and drive it from outside a composable.
 */
object ExplorerTreeState {

    var treeState: TreeState by mutableStateOf(newTreeState())
        private set

    private fun newTreeState() = TreeState(SelectableLazyListState(LazyListState()))

    /** The selected node's path, or null. Single-selection: the tree is configured Single-mode. */
    val selectedPath: String?
        get() = treeState.selectedKeys.firstOrNull() as? String

    fun select(path: String) {
        treeState.selectedKeys = setOf(path)
    }

    val expandedPaths: Set<String>
        get() = treeState.openNodes.filterIsInstance<String>().toSet()

    fun toggleExpanded(path: String) = treeState.toggleNode(path)

    /** True when [selectedPath] resolves to a `.nbt` file flagged dirty in the current snapshot. */
    fun selectedHasUnsaved(): Boolean {
        val path = selectedPath ?: return false
        val node = ProjectTreeState.snapshot?.root?.resolve(path)
        return node is FileNode && node.hasUnsaved
    }

    /**
     * Convert a snapshot root into a Jewel [Tree]. The root folder itself is not emitted — its
     * children become the top-level rows, matching the previous hand-rolled tree.
     */
    fun buildTreeFrom(root: FolderNode): Tree<FileTreeNode> = buildTree {
        root.children.forEach { child -> addFileTreeNode(child, child.name) }
    }

    /** The `/`-joined path a tree element was built with. */
    fun pathOf(element: Tree.Element<FileTreeNode>): String = element.id as String

    /** Test/reset hook: drops selection and expansion by replacing the state wholesale. */
    fun reset() {
        treeState = newTreeState()
    }
}

/**
 * Recursive builder. Both `TreeBuilder` and `ChildrenGeneratorScope` implement [TreeGeneratorScope],
 * so one extension covers every depth. The `id` is the node's path, which is what makes Jewel's
 * selection/expansion sets path-keyed.
 */
private fun TreeGeneratorScope<FileTreeNode>.addFileTreeNode(node: FileTreeNode, path: String) {
    when (node) {
        is FolderNode -> addNode(node, path) {
            node.children.forEach { child -> addFileTreeNode(child, "$path/${child.name}") }
        }
        is FileNode -> addLeaf(node, path)
    }
}
