package com.breadmoirai.garnet.editor.undo.network

import com.breadmoirai.garnet.editor.network.id
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

// === Undo/redo ===

class UndoC2S private constructor() : CustomPacketPayload {
    companion object {
        // Must be sent as INSTANCE — StreamCodec.unit captures this object by identity.
        val INSTANCE = UndoC2S()
        val TYPE = CustomPacketPayload.Type<UndoC2S>(id("undo"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class RedoC2S private constructor() : CustomPacketPayload {
    companion object {
        val INSTANCE = RedoC2S()
        val TYPE = CustomPacketPayload.Type<RedoC2S>(id("redo"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RedoC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * The acting player's undo/redo availability. A null label means that button is disabled.
 *
 * Per-player state, so unlike [StructureAutoSavedS2C] this is never broadcast — another player's
 * stack is none of this client's business.
 *
 * Optional strings use the leading-boolean-flag idiom, matching
 * [EditorTreeSnapshotS2C.currentSubpath].
 */
data class UndoStateS2C(val undoLabel: String?, val redoLabel: String?) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UndoStateS2C>(id("undo_state"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoStateS2C> = object : StreamCodec<ByteBuf, UndoStateS2C> {
            override fun decode(buf: ByteBuf): UndoStateS2C {
                val undo = if (buf.readBoolean()) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                val redo = if (buf.readBoolean()) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return UndoStateS2C(undo, redo)
            }
            override fun encode(buf: ByteBuf, value: UndoStateS2C) {
                buf.writeBoolean(value.undoLabel != null)
                if (value.undoLabel != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.undoLabel)
                buf.writeBoolean(value.redoLabel != null)
                if (value.redoLabel != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.redoLabel)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
