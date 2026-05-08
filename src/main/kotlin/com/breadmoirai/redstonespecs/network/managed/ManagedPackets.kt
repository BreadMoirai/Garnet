package com.breadmoirai.redstonespecs.network.managed

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun id(p: String) = Identifier.fromNamespaceAndPath("redstonespecs", "managed_$p")

// === Tree listing ===

data class ManagedLeafEntry(val subpath: String, val specCount: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedLeafEntry> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedLeafEntry::subpath,
            ByteBufCodecs.VAR_INT, ManagedLeafEntry::specCount,
            ::ManagedLeafEntry,
        )
    }
}

data class ManagedTreeSnapshotS2C(
    val leaves: List<ManagedLeafEntry>,
    val intermediates: List<String>,
    val currentSubpath: String?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedTreeSnapshotS2C>(id("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedTreeSnapshotS2C> = object : StreamCodec<ByteBuf, ManagedTreeSnapshotS2C> {
            override fun decode(buf: ByteBuf): ManagedTreeSnapshotS2C {
                val leaves = ManagedLeafEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf)
                val intermediates = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
                val hasCurrent = buf.readBoolean()
                val current = if (hasCurrent) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return ManagedTreeSnapshotS2C(leaves, intermediates, current)
            }
            override fun encode(buf: ByteBuf, value: ManagedTreeSnapshotS2C) {
                ManagedLeafEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, value.leaves)
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, value.intermediates)
                buf.writeBoolean(value.currentSubpath != null)
                if (value.currentSubpath != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.currentSubpath)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === C2S ===

class ListManagedTreeC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ListManagedTreeC2S>(id("list_tree"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ListManagedTreeC2S> = StreamCodec.unit(ListManagedTreeC2S())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class LoadManagedFolderC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadManagedFolderC2S>(id("load_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadManagedFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadManagedFolderC2S::subpath,
            ::LoadManagedFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class UnloadManagedFolderC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UnloadManagedFolderC2S>(id("unload"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UnloadManagedFolderC2S> = StreamCodec.unit(UnloadManagedFolderC2S())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class SaveNowC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveNowC2S>(id("save_now"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveNowC2S> = StreamCodec.unit(SaveNowC2S())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class NewManagedSpecC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewManagedSpecC2S>(id("new_spec"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewManagedSpecC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewManagedSpecC2S::name,
            ::NewManagedSpecC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === S2C ===

data class ManagedFolderLoadedS2C(
    val subpath: String,
    val loadedSpecIds: List<String>,
    val parseErrors: List<String>,
    val layoutErrors: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedFolderLoadedS2C>(id("folder_loaded"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedFolderLoadedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedFolderLoadedS2C::subpath,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::loadedSpecIds,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::parseErrors,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedFolderLoadedS2C::layoutErrors,
            ::ManagedFolderLoadedS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ManagedSaveReportS2C(val perSpec: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedSaveReportS2C>(id("save_report"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedSaveReportS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ManagedSaveReportS2C::perSpec,
            ::ManagedSaveReportS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ManagedErrorS2C(val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ManagedErrorS2C>(id("error"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ManagedErrorS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ManagedErrorS2C::reason,
            ::ManagedErrorS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
