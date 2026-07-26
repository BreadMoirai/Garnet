package com.breadmoirai.redstonespecs.network.project

import com.breadmoirai.redstonespecs.project.FileNode
import com.breadmoirai.redstonespecs.project.FolderNode
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
})
