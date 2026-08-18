package com.breadmoirai.garnet.editor.history.network

import com.breadmoirai.garnet.editor.workspace.world.EditorRootResolver
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Which structure each player currently has open in the Local History panel, and the fan-out that
 * keeps those panels current.
 *
 * One entry per player, not a set: the panel shows exactly one structure at a time, so a
 * set-or-clear write matches that state precisely and no unwatch for the old subpath can race a
 * watch for a new one.
 *
 * Entries are dropped on disconnect (`Garnet.onInitialize`'s `DISCONNECT` registration, alongside
 * `EditorSession.clear`/`EditorUndoStack.clear`), so a rejoining player never inherits a stale
 * watch.
 */
object HistoryWatchers {

    private val subpathByPlayer = ConcurrentHashMap<UUID, String>()

    /** [subpath] `""` clears the watch. */
    fun watch(playerId: UUID, subpath: String) {
        if (subpath.isEmpty()) subpathByPlayer.remove(playerId) else subpathByPlayer[playerId] = subpath
    }

    fun watchedBy(playerId: UUID): String? = subpathByPlayer[playerId]

    fun clear(playerId: UUID) {
        subpathByPlayer.remove(playerId)
    }

    /**
     * Send [player] the current revision list for [subpath], oldest first — the order
     * [LocalHistoryStore.revisions] returns; the panel reverses for display.
     *
     * An unresolvable root or subpath sends an EMPTY list rather than nothing: the panel needs to
     * hear that a structure it was watching has gone (deleted, renamed, root repointed), and silence
     * would leave a stale list on screen indefinitely.
     */
    fun pushTo(server: MinecraftServer, player: ServerPlayer, subpath: String) {
        val file = EditorRootResolver.rootFor(server)?.resolveSubpath(subpath)
        val entries = if (file == null) emptyList() else LocalHistoryStore.revisions(file).map {
            RevisionEntry(it.timestampMillis, it.sizeX, it.sizeY, it.sizeZ, it.blockCount, it.reason)
        }
        // Unsolicited when called from the commit fan-out, so guard exactly as
        // `StructureCommit.broadcast` does: on a dedicated server an unknown play-phase payload can
        // get a vanilla/unmodded client disconnected (F6). A reply to a C2S the player just sent
        // does not need the guard, but sharing one helper is simpler than two send paths, and the
        // guard is a no-op for a client that provably speaks this channel.
        if (ServerPlayNetworking.canSend(player, StructureHistoryS2C.TYPE)) {
            ServerPlayNetworking.send(player, StructureHistoryS2C(subpath, entries))
        }
    }

    /** Push [subpath]'s list to every player watching it. */
    fun pushAll(server: MinecraftServer, subpath: String) {
        for (player in server.playerList.players) {
            if (subpathByPlayer[player.uuid] == subpath) pushTo(server, player, subpath)
        }
    }
}
