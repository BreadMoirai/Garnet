package com.breadmoirai.garnet.client.ide

import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.project.ProjectNames
import com.breadmoirai.garnet.project.resolve
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Validate-then-send for the Explorer's create/rename actions. Sibling of [RootPickerController]:
 * [sender] is a seam so clientTests can assert on payloads without a live connection.
 *
 * Validation runs here as well as on the server. The client's snapshot can be stale, so the server
 * is authoritative — but pre-checking is what lets the inline field stay open and show an error
 * instead of closing and surfacing a ProjectErrorS2C a round-trip later.
 */
object ExplorerActions {

    var sender: (CustomPacketPayload) -> Unit = { ClientPlayNetworking.send(it) }

    fun resetForTest() {
        sender = { ClientPlayNetworking.send(it) }
    }

    /** Null on success (packet sent), else the reason nothing was sent. */
    fun commitCreate(parentPath: String, kind: NewNodeKind, typed: String): String? {
        val finalName = ProjectNames.resolveFinalName(typed, kind)
        ProjectNames.validate(finalName, siblingsOf(parentPath))?.let { return it }
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
        val finalName = typed.trim()
        // Exclude the node being renamed from its own sibling set, so re-committing an unchanged
        // name is a harmless no-op rather than a bogus "already exists".
        val currentName = path.substringAfterLast('/')
        val siblings = siblingsOf(path.substringBeforeLast('/', "")).filterNot { it == currentName }
        ProjectNames.validate(finalName, siblings)?.let { return it }
        sender(RenamePathC2S(path, finalName))
        return null
    }

    private fun siblingsOf(parentPath: String): List<String> {
        val root = ProjectTreeState.snapshot?.root ?: return emptyList()
        val node = root.resolve(parentPath) as? FolderNode ?: return emptyList()
        return node.children.map { it.name }
    }
}
