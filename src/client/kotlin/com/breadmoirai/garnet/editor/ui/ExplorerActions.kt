package com.breadmoirai.garnet.editor.ui

import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.DeletePathC2S
import com.breadmoirai.garnet.editor.network.DuplicatePathC2S
import com.breadmoirai.garnet.editor.network.MovePathC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.data.NewNodeKind
import com.breadmoirai.garnet.editor.data.EditorNames
import com.breadmoirai.garnet.editor.data.resolve
import com.breadmoirai.garnet.ui.dock.DockState
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Validate-then-send for the Explorer's create/rename actions. Sibling of [RootPickerController]:
 * [sender] is a seam so clientTests can assert on payloads without a live connection.
 *
 * Validation runs here as well as on the server. The client's snapshot can be stale, so the server
 * is authoritative — but pre-checking is what lets the inline field stay open and show an error
 * instead of closing and surfacing a EditorErrorS2C a round-trip later.
 */
object ExplorerActions {

    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }

    fun resetForTest() {
        sender = { ClientPlayNetworking.send(it) }
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitCreate(parentPath: String, kind: NewNodeKind, typed: String): String? {
        val finalName = EditorNames.resolveFinalName(typed, kind)
        EditorNames.validate(finalName, siblingsOf(parentPath))?.let { return it }
        sender(
            when (kind) {
                NewNodeKind.FOLDER -> CreateFolderC2S(parentPath, finalName)
                NewNodeKind.STRUCTURE -> NewStructureC2S(parentPath, finalName)
            },
        )
        return null
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitRename(path: String, typed: String): String? {
        val currentName = path.substringAfterLast('/')
        val finalName = EditorNames.resolveRenameName(typed, currentName, isFolder(path))
        // Exclude the node being renamed from its own sibling set, so re-committing an unchanged
        // name is a harmless no-op rather than a bogus "already exists".
        val siblings = siblingsOf(path.substringBeforeLast('/', "")).filterNot { it == currentName }
        EditorNames.validate(finalName, siblings)?.let { return it }
        sender(RenamePathC2S(path, finalName))
        return null
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitDuplicate(path: String): String? {
        if (path.isEmpty()) return "cannot duplicate the project root"
        // The name is derived server-side, so there is nothing to pre-validate here.
        sender(DuplicatePathC2S(path))
        return null
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitDelete(path: String): String? {
        if (path.isEmpty()) return "cannot delete the project root"
        sender(DeletePathC2S(path))
        return null
    }

    /**
     * Null on success (packet sent), else the reason nothing was sent.
     *
     * Mirrors `handleMove`'s validation so the dialog can refuse a destination in place instead of
     * closing and surfacing an EditorErrorS2C a round-trip later. The server re-runs all of it
     * against the real filesystem and stays authoritative.
     */
    fun commitMove(path: String, destFolder: String): String? {
        if (path.isEmpty()) return "cannot move the project root"
        // Full path SEGMENT, not a string prefix: moving "redstone" into the sibling
        // "redstoneworks" is legal and must not be caught here.
        if (destFolder == path || destFolder.startsWith("$path/")) {
            return "cannot move '$path' into itself"
        }
        val name = path.substringAfterLast('/')
        if (path.substringBeforeLast('/', "") == destFolder) {
            return "'$name' is already in that folder"
        }
        EditorNames.validate(name, siblingsOf(destFolder))?.let { return it }
        sender(MovePathC2S(path, destFolder))
        return null
    }

    /**
     * Show [path]'s revisions in the Local History panel, placing the structure first if it is not
     * already placed.
     *
     * The panel only ever shows a PLACED structure — that invariant is what keeps the server's
     * restore path single, with a footprint to clear and a commit to write through. So this is
     * "place, then look at", never "look at without placing".
     *
     * Null on success, else the reason nothing happened.
     */
    fun openLocalHistory(path: String): String? {
        if (!path.endsWith(".nbt")) return "local history is only available for structures"
        if (OpenStructureState.subpath != path) sender(PlaceStructureC2S(path))
        DockState.showPanel("garnet.localHistory")
        return null
    }

    /**
     * The names already present in [parentPath], for duplicate-name validation.
     *
     * Three distinct situations all degrade to "no known siblings" here: no snapshot loaded yet,
     * [parentPath] resolving to a file rather than a folder, and [parentPath] not resolving at all
     * (a stale client snapshot pointing at a folder the server has since renamed/removed). That is
     * safe only because [commitCreate]/[commitRename] are a pre-check, not the source of truth — the
     * server re-validates against the real filesystem and rejects what this silently waved through.
     */
    /**
     * Whether [path] names a folder in the current snapshot.
     *
     * Defaults to `false` (treat as a file) when the snapshot cannot answer, matching [siblingsOf]:
     * the worst case is that a dotted folder rename keeps a suffix the user dropped, which the user
     * can see in the tree and redo, whereas guessing "folder" would silently strip a file's
     * extension.
     */
    private fun isFolder(path: String): Boolean =
        ProjectTreeState.snapshot?.root?.resolve(path) is FolderNode

    private fun siblingsOf(parentPath: String): List<String> {
        val root = ProjectTreeState.snapshot?.root ?: return emptyList()
        val node = root.resolve(parentPath) as? FolderNode ?: return emptyList()
        return node.children.map { it.name }
    }
}
