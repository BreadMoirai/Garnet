package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FileTreeNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

private fun id(p: String) = Identifier.fromNamespaceAndPath("garnet", "project_$p")

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
        val TYPE = CustomPacketPayload.Type<EditorTreeSnapshotS2C>(id("tree_snapshot"))
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
        val TYPE = CustomPacketPayload.Type<ListEditorTreeC2S>(id("list_tree"))
        val STREAM_CODEC: StreamCodec<ByteBuf, ListEditorTreeC2S> = StreamCodec.unit(INSTANCE)
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class LoadEditorFolderC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<LoadEditorFolderC2S>(id("load_folder"))
        val STREAM_CODEC: StreamCodec<ByteBuf, LoadEditorFolderC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, LoadEditorFolderC2S::subpath,
            ::LoadEditorFolderC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SetEditorRootC2S(val path: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SetEditorRootC2S>(id("set_root"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SetEditorRootC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SetEditorRootC2S::path,
            ::SetEditorRootC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

class UnloadEditorFolderC2S : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<UnloadEditorFolderC2S>(id("unload"))
        val STREAM_CODEC: StreamCodec<ByteBuf, UnloadEditorFolderC2S> = StreamCodec.unit(UnloadEditorFolderC2S())
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

data class NewEditorSpecC2S(val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewEditorSpecC2S>(id("new_spec"))
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
        val TYPE = CustomPacketPayload.Type<EditorFolderLoadedS2C>(id("folder_loaded"))
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

data class EditorSaveReportS2C(val perSpec: List<String>) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorSaveReportS2C>(id("save_report"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorSaveReportS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), EditorSaveReportS2C::perSpec,
            ::EditorSaveReportS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class EditorErrorS2C(val reason: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<EditorErrorS2C>(id("error"))
        val STREAM_CODEC: StreamCodec<ByteBuf, EditorErrorS2C> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EditorErrorS2C::reason,
            ::EditorErrorS2C,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

// === Structure C2S ===

data class PlaceStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<PlaceStructureC2S>(id("place_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, PlaceStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, PlaceStructureC2S::subpath,
            ::PlaceStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

data class SaveStructureC2S(val subpath: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<SaveStructureC2S>(id("save_structure"))
        val STREAM_CODEC: StreamCodec<ByteBuf, SaveStructureC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, SaveStructureC2S::subpath,
            ::SaveStructureC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}

/** Create an empty `<name>.nbt` inside [parentSubpath] (`""` = the project root). */
data class NewStructureC2S(val parentSubpath: String, val name: String) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<NewStructureC2S>(id("new_structure"))
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
        val TYPE = CustomPacketPayload.Type<CreateFolderC2S>(id("create_folder"))
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
        val TYPE = CustomPacketPayload.Type<RenamePathC2S>(id("rename_path"))
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
        val TYPE = CustomPacketPayload.Type<DuplicatePathC2S>(id("duplicate_path"))
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
        val TYPE = CustomPacketPayload.Type<DeletePathC2S>(id("delete_path"))
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
        val TYPE = CustomPacketPayload.Type<MovePathC2S>(id("move_path"))
        val STREAM_CODEC: StreamCodec<ByteBuf, MovePathC2S> = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MovePathC2S::subpath,
            ByteBufCodecs.STRING_UTF8, MovePathC2S::destFolderSubpath,
            ::MovePathC2S,
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
        val TYPE = CustomPacketPayload.Type<StructureAutoSavedS2C>(id("structure_autosaved"))
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
 * A structure's revisions, oldest first — as [com.breadmoirai.garnet.history.LocalHistoryStore.revisions]
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

data class StructureResultS2C(
    val subpath: String,
    val sizeX: Int, val sizeY: Int, val sizeZ: Int,
    val message: String,
) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<StructureResultS2C>(id("structure_result"))
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
