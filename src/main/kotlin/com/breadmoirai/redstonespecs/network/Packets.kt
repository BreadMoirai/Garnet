package com.breadmoirai.redstonespecs.network

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

data class OpenEditorS2CPayload(
    val originPos: BlockPos,
    val entryRelPos: BlockPos,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<OpenEditorS2CPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "open_editor")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, OpenEditorS2CPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenEditorS2CPayload::originPos,
            BlockPos.STREAM_CODEC, OpenEditorS2CPayload::entryRelPos,
            ::OpenEditorS2CPayload,
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Sent by client to undo the last entry removal (triggered by ctrl+z keybind, Phase 7). */
class UndoC2SPayload : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UndoC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "undo")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, UndoC2SPayload> =
            StreamCodec.unit(UndoC2SPayload())
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
