package com.breadmoirai.garnet.editor.explorer.network

import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FileTreeNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.network.payloadId
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

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

data class EditorTreeSnapshotS2C(
    val root: FolderNode,
    val currentSubpath: String?,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorTreeSnapshotS2C>(payloadId("tree_snapshot"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorTreeSnapshotS2C> = object : StreamCodec<ByteBuf, EditorTreeSnapshotS2C> {
            override fun decode(buf: ByteBuf): EditorTreeSnapshotS2C {
                val root = FILE_TREE_STREAM_CODEC.decode(buf) as? FolderNode
                    ?: error("EditorTreeSnapshotS2C root must be a folder")
                val hasCurrent = buf.readBoolean()
                val current = if (hasCurrent) ByteBufCodecs.STRING_UTF8.decode(buf) else null
                return EditorTreeSnapshotS2C(root, current)
            }
            override fun encode(buf: ByteBuf, value: EditorTreeSnapshotS2C) {
                FILE_TREE_STREAM_CODEC.encode(buf, value.root)
                buf.writeBoolean(value.currentSubpath != null)
                if (value.currentSubpath != null) ByteBufCodecs.STRING_UTF8.encode(buf, value.currentSubpath)
            }
        }
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === C2S ===

class ListEditorTreeC2S private constructor() : CustomPacketPayload {
    companion object {
        // StreamCodec.unit captures an instance by identity and rejects any other. Callers
        // must therefore send INSTANCE rather than constructing fresh `ListEditorTreeC2S()` —
        // doing so throws IllegalStateException "Can't encode A, expected B" on the encoder.
        val INSTANCE = ListEditorTreeC2S()
        val TYPE = CustomPacketPayload.Type<ListEditorTreeC2S>(payloadId("list_tree"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ListEditorTreeC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class LoadEditorFolderC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadEditorFolderC2S>(payloadId("load_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadEditorFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadEditorFolderC2S::subpath,
            ::LoadEditorFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetEditorRootC2S(val path: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetEditorRootC2S>(payloadId("set_root"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SetEditorRootC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetEditorRootC2S::path,
            ::SetEditorRootC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class UnloadEditorFolderC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UnloadEditorFolderC2S>(payloadId("unload"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UnloadEditorFolderC2S> = StreamCodec.unit(UnloadEditorFolderC2S())
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class NewEditorSpecC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewEditorSpecC2S>(payloadId("new_spec"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewEditorSpecC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewEditorSpecC2S::name,
            ::NewEditorSpecC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === S2C ===

data class EditorFolderLoadedS2C(
    val subpath: String,
    val loadedSpecIds: List<String>,
    val parseErrors: List<String>,
    val layoutErrors: List<String>,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorFolderLoadedS2C>(payloadId("folder_loaded"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorFolderLoadedS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EditorFolderLoadedS2C::subpath,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EditorFolderLoadedS2C::loadedSpecIds,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EditorFolderLoadedS2C::parseErrors,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EditorFolderLoadedS2C::layoutErrors,
            ::EditorFolderLoadedS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class EditorErrorS2C(val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorErrorS2C>(payloadId("error"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorErrorS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EditorErrorS2C::reason,
            ::EditorErrorS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Create an empty `<name>.nbt` inside [parentSubpath] (`""` = the project root). */
data class NewStructureC2S(val parentSubpath: String, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewStructureC2S>(payloadId("new_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, NewStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, NewStructureC2S::parentSubpath,
            ByteBufCodecs.STRING_UTF8, NewStructureC2S::name,
            ::NewStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Create a folder named [name] inside [parentSubpath] (`""` = the project root). */
data class CreateFolderC2S(val parentSubpath: String, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<CreateFolderC2S>(payloadId("create_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, CreateFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, CreateFolderC2S::parentSubpath,
            ByteBufCodecs.STRING_UTF8, CreateFolderC2S::name,
            ::CreateFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Rename the file or folder at [subpath] to [newName] (a bare name, not a path). */
data class RenamePathC2S(val subpath: String, val newName: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<RenamePathC2S>(payloadId("rename_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, RenamePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, RenamePathC2S::subpath,
            ByteBufCodecs.STRING_UTF8, RenamePathC2S::newName,
            ::RenamePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Duplicate the file or folder at [subpath] beside itself. Carries no name: only the server sees the
 * real filesystem, so it derives the deduplicated name itself rather than trusting a client whose
 * tree snapshot may be stale.
 */
data class DuplicatePathC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<DuplicatePathC2S>(payloadId("duplicate_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, DuplicatePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DuplicatePathC2S::subpath,
            ::DuplicatePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Delete the file, or the whole folder subtree, at [subpath]. */
data class DeletePathC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<DeletePathC2S>(payloadId("delete_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, DeletePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DeletePathC2S::subpath,
            ::DeletePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/**
 * Move the file or folder at [subpath] into [destFolderSubpath] (`""` = the project root), keeping
 * its name.
 */
data class MovePathC2S(val subpath: String, val destFolderSubpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<MovePathC2S>(payloadId("move_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, MovePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MovePathC2S::subpath,
            ByteBufCodecs.STRING_UTF8, MovePathC2S::destFolderSubpath,
            ::MovePathC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
