package com.breadmoirai.garnet.editor.history.network

import com.breadmoirai.garnet.editor.network.id
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

// === Local history ===

/**
 * One banked revision, as the client sees it.
 *
 * Deliberately `Revision` minus its `file` field: the blob filename is a server-side filesystem
 * detail, and a client that selects a revision by timestamp rather than by name cannot ask the
 * server to read an arbitrary path.
 */
data class RevisionEntry(
    val timestampMillis: Long,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val blockCount: Int,
    val reason: String,
)

val REVISION_ENTRY_STREAM_CODEC: StreamCodec<ByteBuf, RevisionEntry> = StreamCodec.composite(
    ByteBufCodecs.VAR_LONG, RevisionEntry::timestampMillis,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeX,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeY,
    ByteBufCodecs.VAR_INT, RevisionEntry::sizeZ,
    ByteBufCodecs.VAR_INT, RevisionEntry::blockCount,
    ByteBufCodecs.STRING_UTF8, RevisionEntry::reason,
    ::RevisionEntry,
)

/**
 * "I am looking at this structure's history; send it and keep me posted."
 *
 * An EMPTY [subpath] means "no longer looking". One packet rather than a watch/unwatch pair because
 * the server's state is a single entry per player, so a set-or-clear write matches it exactly and
 * there is no ordering hazard where an unwatch for the old subpath races a watch for the new one.
 */
data class WatchStructureHistoryC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<WatchStructureHistoryC2S>(id("watch_history"))
        val STREAM_CODEC: StreamCodec<ByteBuf, WatchStructureHistoryC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, WatchStructureHistoryC2S::subpath,
            ::WatchStructureHistoryC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * A structure's revisions, oldest first — as [com.breadmoirai.garnet.editor.history.data.LocalHistoryStore.revisions]
 * returns them. The panel reverses for display; keeping the store's own order on the wire means a
 * future consumer does not inherit a presentation decision.
 *
 * Sent both as the reply to [WatchStructureHistoryC2S] and as an unsolicited push after any commit
 * or restore for a watched subpath.
 */
data class StructureHistoryS2C(
    val subpath: String,
    val revisions: List<RevisionEntry>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureHistoryS2C>(id("structure_history"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureHistoryS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureHistoryS2C::subpath,
            REVISION_ENTRY_STREAM_CODEC.apply(ByteBufCodecs.list()), StructureHistoryS2C::revisions,
            ::StructureHistoryS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Restore one revision, identified by TIMESTAMP rather than list index.
 *
 * An index is only meaningful against the list the client happened to be holding: an autosave
 * landing between render and click would shift it silently, restoring the revision next to the one
 * that was clicked with nothing able to detect it. The server refuses an unknown timestamp rather
 * than guessing at the nearest.
 */
data class RestoreRevisionC2S(
    val subpath: String,
    val timestampMillis: Long,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RestoreRevisionC2S>(id("restore_revision"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RestoreRevisionC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RestoreRevisionC2S::subpath,
            ByteBufCodecs.VAR_LONG, RestoreRevisionC2S::timestampMillis,
            ::RestoreRevisionC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
