package com.breadmoirai.garnet.editor.network

import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.netty.buffer.Unpooled

class FileTreeCodecTest : FunSpec({

    test("round-trips a nested folder tree including empty folders and no-extension files") {
        val tree = FolderNode("root", listOf(
            FolderNode("adders", listOf(
                FolderNode("full-adder", listOf(
                    FileNode("full.spec.kts", "kts"),
                    FileNode("notes", ""),
                )),
            )),
            FolderNode("empty", emptyList()),
            FileNode("loose.txt", "txt"),
        ))

        val buf = Unpooled.buffer()
        FILE_TREE_STREAM_CODEC.encode(buf, tree)
        val decoded = FILE_TREE_STREAM_CODEC.decode(buf)

        decoded shouldBe tree
    }

    test("EditorTreeSnapshotS2C round-trips with and without currentSubpath") {
        val tree = FolderNode("root", listOf(
            FolderNode("adders", listOf(FileNode("full.spec.kts", "kts"))),
        ))
        for (current in listOf<String?>(null, "adders")) {
            val payload = EditorTreeSnapshotS2C(root = tree, currentSubpath = current)
            val buf = Unpooled.buffer()
            EditorTreeSnapshotS2C.STREAM_CODEC.encode(buf, payload)
            val decoded = EditorTreeSnapshotS2C.STREAM_CODEC.decode(buf)
            decoded shouldBe payload
        }
    }

    test("SetEditorRootC2S round-trips its path through STREAM_CODEC") {
        val payload = SetEditorRootC2S("/abs/some/workspace")
        val buf = io.netty.buffer.Unpooled.buffer()
        SetEditorRootC2S.STREAM_CODEC.encode(buf, payload)
        val decoded = SetEditorRootC2S.STREAM_CODEC.decode(buf)
        decoded shouldBe payload
    }

    test("FileNode hasUnsaved survives round-trip") {
        val node: com.breadmoirai.garnet.editor.data.FileTreeNode = FileNode("gadget.nbt", "nbt", hasUnsaved = true)
        val buf = io.netty.buffer.Unpooled.buffer()
        FILE_TREE_STREAM_CODEC.encode(buf, node)
        (FILE_TREE_STREAM_CODEC.decode(buf) as FileNode).hasUnsaved shouldBe true
    }
})
