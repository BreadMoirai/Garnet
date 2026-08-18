package com.breadmoirai.garnet.editor.structure.network

import com.breadmoirai.garnet.editor.history.network.HistoryWatchers
import com.breadmoirai.garnet.editor.structure.data.CommittedStructure
import com.breadmoirai.garnet.editor.structure.ops.StructureCommit
import com.breadmoirai.garnet.editor.structure.ops.StructurePersistence
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path

/**
 * Layer: `editor.structure.network` — the client-facing half of the commit pipeline.
 *
 * `StructureCommit` (in `structure/ops`) knows how to get a structure onto disk and nothing about
 * who needs telling; this object knows who needs telling and nothing about how the write works. It
 * exists because `ops` sits below `network` in the editor layering, so a `ServerPlayNetworking.send`
 * has no business living there — `StructureCommit.tick`/`commitAll` hand back what they committed
 * and the sending happens here.
 *
 * **Production calls the wrappers here, not the `ops` functions directly.** `Garnet.kt`'s
 * `END_SERVER_TICK`, `BEFORE_SAVE` and `SERVER_STOPPING` listeners go through [tick] and
 * [commitAll]; calling `StructureCommit.tick` directly commits correctly but announces nothing,
 * which is right for an ops-level test and wrong for the live server.
 */
object StructureSync {

    /**
     * Debounced auto-save pass: commit everything due, then announce it. Wired to
     * `END_SERVER_TICK`.
     */
    fun tick(
        server: MinecraftServer,
        now: Long = server.overworld().gameTime,
        writeNbt: (CompoundTag, Path) -> Unit = StructurePersistence::writeStructureAtomic,
    ) {
        for (committed in StructureCommit.tick(server, now, writeNbt)) broadcast(server, committed)
    }

    /**
     * Backstop flush (world-save, server stop, root swap): commit everything dirty regardless of
     * debounce, announce what landed, and return what did not.
     *
     * The return value is the same `UncommittedStructure` list `StructureCommit.commitAll` has
     * always reported — see its KDoc for why `handleSetRoot` must not ignore it.
     */
    fun commitAll(server: MinecraftServer, reason: String): List<StructureCommit.UncommittedStructure> {
        val result = StructureCommit.commitAll(server, reason)
        for (committed in result.committed) broadcast(server, committed)
        return result.uncommitted
    }

    /**
     * Unsolicited fan-out: tells every OTHER connected player (`exclude`, if given, is typically
     * the player who just triggered the commit and was already replied to directly — see
     * [EditorStructureHandlers.handleSaveStructure]) that a structure changed, so their Explorer
     * status lines can update. Nothing here is a reply to anything these players sent, so — unlike
     * every other S2C in this mod — the receiver isn't provably running the mod at all: a
     * vanilla/unmodded client on a dedicated server can be disconnected for an unknown play-phase
     * payload (F6). Guard every send with `canSend`. [tick] and [commitAll] are the two genuinely
     * unsolicited callers (a debounced auto-save and the periodic/shutdown backstop, neither
     * triggered by a specific player's packet) and both go through this function unfiltered
     * (`exclude = null`).
     */
    fun broadcast(server: MinecraftServer, committed: CommittedStructure, exclude: ServerPlayer? = null) {
        val payload = committed.toAutoSavedPayload()
        for (player in server.playerList.players) {
            if (player === exclude) continue
            // Unlike every other S2C here, this one is unsolicited — it isn't a reply to a C2S, so
            // the receiver isn't provably running the mod. On a dedicated server, sending an unknown
            // play-phase payload to a vanilla/unmodded client can get it disconnected (F6).
            if (ServerPlayNetworking.canSend(player, StructureAutoSavedS2C.TYPE)) {
                ServerPlayNetworking.send(player, payload)
            }
        }
        // Anyone with this structure's Local History panel open just gained a revision. Deliberately
        // outside the `exclude` loop: `exclude` means "already replied to about the SAVE", and that
        // reply carries no revision list — the player who triggered the commit needs this push as
        // much as everyone else. `pushAll` applies the same `canSend` guard.
        HistoryWatchers.pushAll(server, payload.subpath)
    }
}
