package com.breadmoirai.garnet.editor.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FileTreeNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.resolve
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.SelectionMode
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

    // LazyTree has no selectionMode parameter of its own in this Jewel version — selection mode is
    // a property of SelectableLazyListState, constructed here rather than passed at the call site.
    private fun newTreeState() = TreeState(SelectableLazyListState(LazyListState(), SelectionMode.Single))

    /** The selected node's path, or null. Single-selection: the tree is configured Single-mode. */
    val selectedPath: String?
        get() = treeState.selectedKeys.firstOrNull() as? String

    fun select(path: String) {
        treeState.selectedKeys = setOf(path)
    }

    val expandedPaths: Set<String>
        get() = treeState.openNodes.filterIsInstance<String>().toSet()

    fun toggleExpanded(path: String) = treeState.toggleNode(path)

    /** Collapse every expanded node. Selection is left alone — IntelliJ's Collapse All does the same. */
    fun collapseAll() {
        treeState.openNodes = emptySet()
    }

    /** True when [selectedPath] resolves to a `.nbt` file flagged dirty in the current snapshot. */
    fun selectedHasUnsaved(): Boolean {
        val path = selectedPath ?: return false
        val node = ProjectTreeState.snapshot?.root?.resolve(path)
        return node is FileNode && node.hasUnsaved
    }

    /** The tree id of the project root itself. `FolderNode.resolve("")` and
     *  `EditorRoot.resolveSubpath("")` both already mean "the root", so this needs no translation. */
    const val ROOT_PATH: String = ""

    /**
     * Convert a snapshot root into a Jewel [Tree]. The root folder is emitted as the single
     * top-level node under id [ROOT_PATH] (`""`), with its children nested beneath.
     *
     * When [edit] is a pending [ExplorerEdit.Creating], a placeholder child is appended to the
     * target folder so the name field renders at the depth and position the new item will occupy.
     * The placeholder's data is a throwaway [FileNode]; only its id is meaningful, and `TreeRow`
     * switches on that id to draw a field instead of a label.
     */
    fun buildTreeFrom(root: FolderNode, edit: ExplorerEdit? = null): Tree<FileTreeNode> {
        val pendingParent = (edit as? ExplorerEdit.Creating)?.parentPath
        return buildTree {
            addNode(root, ROOT_PATH) {
                root.children.forEach { child -> addFileTreeNode(child, child.name, pendingParent) }
                if (pendingParent == ROOT_PATH) addPendingLeaf(ROOT_PATH)
            }
        }
    }

    /** The `/`-joined path a tree element was built with. */
    fun pathOf(element: Tree.Element<FileTreeNode>): String = element.id as String

    /** Test/reset hook: drops selection and expansion by replacing the state wholesale. */
    fun reset() {
        treeState = newTreeState()
    }
}

/** The placeholder row a pending create renders into. */
private fun TreeGeneratorScope<FileTreeNode>.addPendingLeaf(parentPath: String) {
    addLeaf(FileNode(PENDING_NODE_NAME, ""), ExplorerEdit.pendingIdFor(parentPath))
}

/** Name carried by the placeholder's throwaway FileNode; never displayed (TreeRow draws a field). */
private const val PENDING_NODE_NAME = ""

/**
 * Recursive builder. Both `TreeBuilder` and `ChildrenGeneratorScope` implement [TreeGeneratorScope],
 * so one extension covers every depth. The `id` is the node's path, which is what makes Jewel's
 * selection/expansion sets path-keyed. [pendingParent], when it matches a folder's path, appends
 * that folder's pending-create placeholder after its real children.
 */
private fun TreeGeneratorScope<FileTreeNode>.addFileTreeNode(
    node: FileTreeNode,
    path: String,
    pendingParent: String?,
) {
    when (node) {
        is FolderNode -> addNode(node, path) {
            node.children.forEach { child -> addFileTreeNode(child, "$path/${child.name}", pendingParent) }
            if (pendingParent == path) addPendingLeaf(path)
        }
        is FileNode -> addLeaf(node, path)
    }
}
