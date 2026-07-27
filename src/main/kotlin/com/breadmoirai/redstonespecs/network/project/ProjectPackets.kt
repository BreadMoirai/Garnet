package com.breadmoirai.redstonespecs.network.project

import com.breadmoirai.redstonespecs.project.FileNode
import com.breadmoirai.redstonespecs.project.FileTreeNode
import com.breadmoirai.redstonespecs.project.FolderNode
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun id(p: String) = Identifier.fromNamespaceAndPath("redstonespecs", "project_$p")

// === Tree listing ===

private const val TAG_FOLDER: Byte = 0
private const val TAG_FILE: Byte = 1

/** Recursive codec for a [FileTreeNode] tree. Per-node tag byte: 0 = folder, 1 = file. */
val FILE_TREE_STREAM_CODEC: StreamCodec<ByteBuf, FileTreeNode> = object : StreamCodec<ByteBuf, FileTreeNode> {
    override fun decode(buf: ByteBuf): FileTreeNode {
        val tag = buf.readByte()
        val name = ByteBufCodecs.STRING_UTF8.decode(buf)
        return when (tag) {
            TAG_FOLDER -> {
                val count = ByteBufCodecs.VAR_INT.decode(buf)
                val children = ArrayList<FileTreeNode>()
                repeat(count) { children.add(decode(buf)) }
                FolderNode(name, children)
            }
            TAG_FILE -> FileNode(name, ByteBufCodecs.STRING_UTF8.decode(buf))
            else -> throw IllegalStateException("Unknown FileTreeNode tag: $tag")
        }
    }

    override fun encode(buf: ByteBuf, value: FileTreeNode) {
        when (value) {
            is FolderNode -> {
                buf.writeByte(TAG_FOLDER.toInt())
                ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                ByteBufCodecs.VAR_INT.encode(buf, value.children.size)
                value.children.forEach { encode(buf, it) }
            }
            is FileNode -> {
                buf.writeByte(TAG_FILE.toInt())
                ByteBufCodecs.STRING_UTF8.encode(buf, value.name)
                ByteBufCodecs.STRING_UTF8.encode(buf, value.extension)
            }
        }
    }
}

data class ProjectTreeSnapshotS2C(
    val root: FolderNode,
    val currentSubpath: String?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<ProjectTreeSnapshotS2C>(id("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> = object : StreamCodec<ByteBuf, ProjectTreeSnapshotS2C> {
            override fun decode(buf: ByteBuf): ProjectTreeSnapshotS2C {
                val root = FILE_TREE_STREAM_CODEC.decode(buf) as? FolderNode
                    ?: error("ProjectTreeSnapshotS2C root must be a folder")
                val hasCurrent = buf.readBoolean()
                val current = if (hasCurrent) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return ProjectTreeSnapshotS2C(root, current)
            }
            override fun encode(buf: ByteBuf, value: ProjectTreeSnapshotS2C) {
                FILE_TREE_STREAM_CODEC.encode(buf, value.root)
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

data class SetProjectRootC2S(val path: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetProjectRootC2S>(id("set_root"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SetProjectRootC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetProjectRootC2S::path,
            ::SetProjectRootC2S,
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
