package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.DeletePathC2S
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.undo.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.EditorUndoStack
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.writeBytes

/** This spec's alias for the shared harness — see `com.breadmoirai.garnet.test.withEditorServer`. */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) =
    withEditorServer("undo-net", block)

class EditorUndoNetworkSpec : GarnetTestSpec({

    test("delete banks a pre-delete revision for a .spec.kts") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("spec { }".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))

            spec.exists().shouldBeFalse()
            // History is keyed by absolute path and outlives the file, which is what makes the
            // undo possible at all.
            LocalHistoryStore.revisions(spec).shouldNotBeEmpty()
            LocalHistoryStore.revisions(spec).last().reason shouldBe LocalHistoryStore.REASON_PRE_DELETE
        }
    }

    test("delete banks a freshly duplicated .nbt that had no history") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val copy = root.resolve("gadget copy.nbt")
            copy.writeBytes(byteArrayOf(0x1f.toByte(), 0x8b.toByte()))
            // handleDuplicate deliberately gives a copy no history, so without banking there would
            // be nothing to restore from here.
            LocalHistoryStore.revisions(copy).shouldBeEmpty()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("gadget copy.nbt"))

            LocalHistoryStore.revisions(copy).shouldNotBeEmpty()
        }
    }

    test("delete pushes a Delete command carrying the whole subtree manifest") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("a".toByteArray())
            root.resolve("redstone/b.spec.kts").writeBytes("b".toByteArray())
            // An EMPTY folder is the entry no file's parent segments can imply. Without it, an
            // implementation that walked files only and synthesised folder entries from each file's
            // ancestors would produce exactly the same manifest and pass -- and then restore would
            // silently drop every empty folder in the subtree.
            root.resolve("redstone/empty").createDirectories()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            val command = EditorUndoStack.peekUndo(player.uuid)
            command.shouldNotBeNull()
            command.shouldBeInstanceOf<EditorUndoCommand.Delete>()
            command.rootSubpath shouldBe "redstone"
            // Every folder (redstone itself, clocks, and the empty one) and both files.
            command.manifest.filter { it.isFolder }.map { it.relPath } shouldContainExactlyInAnyOrder
                listOf("", "clocks", "empty")
            command.banked.map { it.relPath } shouldContainExactlyInAnyOrder listOf("clocks/a.spec.kts", "b.spec.kts")

            // Parents before children, which is what lets a restore createDirectories in manifest
            // order without sorting. Depth-first pre-order is a property of Path.walk without
            // BREADTH_FIRST, so this pins an invariant the restore side depends on.
            val order = command.manifest.map { it.relPath }
            order.indexOf("") shouldBeLessThan order.indexOf("clocks")
            order.indexOf("clocks") shouldBeLessThan order.indexOf("clocks/a.spec.kts")
        }
    }

    test("deleting a single file records it under the empty-string relPath") {
        // Task 5 resolves a single-file restore against the target path itself, not beneath it, so
        // the "" sentinel -- the same one that means "the deleted root" in the folder branch -- is
        // load-bearing convention rather than an incidental detail.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("spec { }".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))

            val command = EditorUndoStack.peekUndo(player.uuid)
            command.shouldNotBeNull()
            command.shouldBeInstanceOf<EditorUndoCommand.Delete>()
            command.rootSubpath shouldBe "clock.spec.kts"
            command.manifest.single().relPath shouldBe ""
            command.manifest.single().isFolder shouldBe false
            command.banked.single().relPath shouldBe ""
            // The store key is the absolute path as it stood at delete time, which is what outlives
            // the file; relPath alone could not find the bytes again.
            command.banked.single().absolutePath shouldBe spec.toAbsolutePath()
        }
    }

    test("a genuine banking failure deletes, reports an error, and pushes nothing") {
        // The DeletedUnbankable branch, and the reason DeleteOutcome distinguishes it from a clean
        // delete at all: a partially banked subtree pushed as a Delete command would let undo
        // report success while silently losing whatever could not be banked. Distinct from the
        // history-disabled case below, which is not an error and stays silent.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("spec { }".toByteArray())
            drainPayloads(player)

            // Force writeRawRevision to fail deterministically and portably by occupying
            // index.json's path with a directory -- the same trick EditorFileOpsNetworkSpec's
            // commit-failure tests use.
            val historyDir = LocalHistoryStore.dirFor(spec)
            historyDir.createDirectories()
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))

                spec.exists().shouldBeFalse()                              // deleted anyway
                EditorUndoStack.peekUndo(player.uuid).shouldBeNull()       // but NOT undoable
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("with local history disabled a delete succeeds silently and pushes nothing") {
        // REGRESSION: with history off, LocalHistoryStore returns null for EVERY write, so a
        // bankFile-returns-null check alone would take the DeletedUnbankable branch on every single
        // delete and fire an EditorErrorS2C at a player whose delete actually succeeded. Undo being
        // unavailable is a standing property of the player's own setting, not news per operation.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("spec { }".toByteArray())
            drainPayloads(player)

            val prevEnabled = SharedSettings.localHistoryEnabled
            SharedSettings.localHistoryEnabled = false
            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))
            } finally {
                SharedSettings.localHistoryEnabled = prevEnabled
            }

            spec.exists().shouldBeFalse()
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
            // The whole point of the fix: no error packet for a delete that worked.
            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldBeEmpty()
        }
    }

    test("a failed delete pushes nothing") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("does-not-exist"))
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }
})
