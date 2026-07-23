package com.breadmoirai.redstonespecs.network.project

import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun id(p: String) = Identifier.fromNamespaceAndPath("redstonespecs", "project_$p")

// === Tree listing ===

data class ProjectLeafEntry(val subpath: String, val specCount: Int) {
    companion object {
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectLeafEntry> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ProjectLeafEntry::subpath,
            ByteBufCodecs.VAR_INT, ProjectLeafEntry::specCount,
            ::ProjectLeafEntry,
        )
    }
}

data class ProjectTreeSnapshotS2C(
    val leaves: List<ProjectLeafEntry>,
    val intermediates: List<String>,
    val currentSubpath: String?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectTreeSnapshotS2C>(id("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> = object : StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> {
            override fun decode(buf: ByteBuf): ProjectTreeSnapshotS2C {
                val leaves = ProjectLeafEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf)
                val intermediates = ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).decode(buf)
                val hasCurrent = buf.readBoolean()
                val current = if (hasCurrent) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return ProjectTreeSnapshotS2C(leaves, intermediates, current)
            }
            override fun encode(buf: ByteBuf, value: ProjectTreeSnapshotS2C) {
                ProjectLeafEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, value.leaves)
                ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()).encode(buf, value.intermediates)
                buf.writeBoolean(value.currentSubpath != null)
                if (value.currentSubpath != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.currentSubpath)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === C2S ===

class ListProjectTreeC2S private constructor() : CustomPacketPayload {
    companion object {
        // StreamCodec.unit captures an instance by identity and rejects any other. Callers
        // must therefore send INSTANCE rather than constructing fresh `ListProjectTreeC2S()` —
        // doing so throws IllegalStateException "Can't encode A, expected B" on the encoder.
        val INSTANCE = ListProjectTreeC2S()
        val TYPE = CustomPacketPayload.Type<ListProjectTreeC2S>(id("list_tree"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ListProjectTreeC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class LoadProjectFolderC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadProjectFolderC2S>(id("load_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadProjectFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadProjectFolderC2S::subpath,
            ::LoadProjectFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class UnloadProjectFolderC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UnloadProjectFolderC2S>(id("unload"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UnloadProjectFolderC2S> = StreamCodec.unit(UnloadProjectFolderC2S())
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

data class NewProjectSpecC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewProjectSpecC2S>(id("new_spec"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewProjectSpecC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewProjectSpecC2S::name,
            ::NewProjectSpecC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === S2C ===

data class ProjectFolderLoadedS2C(
    val subpath: String,
    val loadedSpecIds: List<String>,
    val parseErrors: List<String>,
    val layoutErrors: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectFolderLoadedS2C>(id("folder_loaded"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectFolderLoadedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ProjectFolderLoadedS2C::subpath,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProjectFolderLoadedS2C::loadedSpecIds,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProjectFolderLoadedS2C::parseErrors,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProjectFolderLoadedS2C::layoutErrors,
            ::ProjectFolderLoadedS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ProjectSaveReportS2C(val perSpec: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectSaveReportS2C>(id("save_report"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectSaveReportS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), ProjectSaveReportS2C::perSpec,
            ::ProjectSaveReportS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class ProjectErrorS2C(val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectErrorS2C>(id("error"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectErrorS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ProjectErrorS2C::reason,
            ::ProjectErrorS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
