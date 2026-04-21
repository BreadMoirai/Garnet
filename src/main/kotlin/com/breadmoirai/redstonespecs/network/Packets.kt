package com.breadmoirai.redstonespecs.network

import io.netty.buffer.ByteBuf
import net.minecraft.core.BlockPos
import net.minecraft.network.codec.ByteBufCodecs
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

/** Sent by client to trigger a spec run. [runAll] runs every SpecCase sequentially; otherwise only the active one. */
data class RunSpecC2SPayload(val originPos: BlockPos, val runAll: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RunSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "run_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, RunSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, RunSpecC2SPayload::originPos,
            ByteBufCodecs.BOOL, RunSpecC2SPayload::runAll,
            ::RunSpecC2SPayload,
        )
    }

    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Sent by client to restore the circuit to its pre-run snapshot. */
data class ResetSpecC2SPayload(val originPos: BlockPos) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ResetSpecC2SPayload>(
            Identifier.fromNamespaceAndPath("redstonespecs", "reset_spec")
        )
        val STREAM_CODEC: StreamCodec<ByteBuf, ResetSpecC2SPayload> = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResetSpecC2SPayload::originPos,
            ::ResetSpecC2SPayload,
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
