package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.editor.data.scanFolder
import com.breadmoirai.garnet.editor.world.EditorRootResolver
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/** Shared helpers for the editor/network handler objects. */
object EditorHandlerSupport {

    fun fail(player: ServerPlayer, reason: String) {
        ServerPlayNetworking.send(player, EditorErrorS2C(reason))
    }

    /**
     * The absolute path of [parentSubpath] under the configured root, or null after sending the
     * player an error. `""` means the root itself, which `resolveSubpath` already handles; anything
     * absolute or escaping the root comes back null from that call and is refused here.
     */
    fun resolveParentFolder(
        server: MinecraftServer,
        player: ServerPlayer,
        parentSubpath: String,
    ): Path? {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured"); return null
        }
        val folder = root.resolveSubpath(parentSubpath) ?: run {
            fail(player, "folder not found or escapes root: $parentSubpath"); return null
        }
        if (!folder.isDirectory()) {
            fail(player, "not a folder: $parentSubpath"); return null
        }
        return folder
    }

    /** Names already present in [folder], for the duplicate check. */
    fun siblingNames(folder: Path): List<String> =
        folder.listDirectoryEntries().map { it.name }

    fun sendTree(server: MinecraftServer, player: ServerPlayer) {
        val root = EditorRootResolver.rootFor(server) ?: run {
            fail(player, "project-root not configured")
            return
        }
        val current = EditorSession.get(player.uuid)?.activeSubpath
        ServerPlayNetworking.send(player, EditorTreeSnapshotS2C(
            root = scanFolder(root.path),
            currentSubpath = current,
        ))
    }
}
