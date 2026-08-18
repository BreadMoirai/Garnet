package com.breadmoirai.garnet.editor.structure.network

import com.breadmoirai.garnet.editor.network.payloadId
import com.breadmoirai.garnet.editor.structure.data.CommittedStructure
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SaveNowC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveNowC2S>(payloadId("save_now"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveNowC2S> = StreamCodec.unit(SaveNowC2S())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class EditorSaveReportS2C(val perSpec: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorSaveReportS2C>(payloadId("save_report"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorSaveReportS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EditorSaveReportS2C::perSpec,
            ::EditorSaveReportS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === Structure C2S ===

data class PlaceStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<PlaceStructureC2S>(payloadId("place_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, PlaceStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlaceStructureC2S::subpath,
            ::PlaceStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SaveStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveStructureC2S>(payloadId("save_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveStructureC2S::subpath,
            ::SaveStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === Structure S2C ===

/**
 * Sent on every committed auto-save. Broadcast rather than addressed: structure regions are
 * server-global, so any player looking at one wants the update.
 *
 * Carries more than the status line needs — [blockCount] and [savedAtMillis] exist for the
 * structure info panel, which consumes this same packet.
 */
data class StructureAutoSavedS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val blockCount: Int,
    val savedAtMillis: Long,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureAutoSavedS2C>(payloadId("structure_autosaved"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureAutoSavedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureAutoSavedS2C::subpath,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeX,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeY,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::sizeZ,
            ByteBufCodecs.VAR_INT, StructureAutoSavedS2C::blockCount,
            ByteBufCodecs.VAR_LONG, StructureAutoSavedS2C::savedAtMillis,
            ::StructureAutoSavedS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * The `data`-to-wire mapping for a committed structure. This is the ONLY place that knows a
 * [CommittedStructure] has a wire form — `structure/data` is a leaf layer and must not name the
 * payload, and `structure/ops` broadcasts through `StructureCommit.broadcast`, which converts here.
 */
fun CommittedStructure.toAutoSavedPayload() = StructureAutoSavedS2C(
    subpath, sizeX, sizeY, sizeZ, blockCount, savedAtMillis,
)

data class StructureResultS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val message: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureResultS2C>(payloadId("structure_result"))
        val STREAM_CODEC: StreamCodec<ByteBuf, StructureResultS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::subpath,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeX,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeY,
            ByteBufCodecs.VAR_INT, StructureResultS2C::sizeZ,
            ByteBufCodecs.STRING_UTF8, StructureResultS2C::message,
            ::StructureResultS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
