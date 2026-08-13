package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.network.DeletePathC2S
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.undo.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.EditorUndoStack
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.test.deleteRecursively
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.withEditorServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
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

            // Parents before children -- a property of Path.walk without BREADTH_FIRST. restoreSubtree
            // additionally sorts by relPath length before createDirectories, so it does not strictly
            // depend on this order, but the manifest reading naturally parents-first is still worth
            // pinning here.
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

    test("restoreSubtree brings back folders, a .spec.kts and a .nbt") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("contents-a".toByteArray())
            val nbtBytes = root.resolve("redstone/g.nbt").let { p ->
                p.writeBytes(byteArrayOf(1, 2, 3, 4)); p.readBytes()
            }

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))
            root.resolve("redstone").exists().shouldBeFalse()

            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

            report.failures.shouldBeEmpty()
            report.restored shouldBe report.total
            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
            root.resolve("redstone/clocks/a.spec.kts").readBytes() shouldBe "contents-a".toByteArray()
            root.resolve("redstone/g.nbt").readBytes() shouldBe nbtBytes
        }
    }

    test("restoreSubtree recreates an empty folder") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("empty/inner").createDirectories()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("empty"))
            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            EditorFileOpsHandlers.restoreSubtree(server, player, command)

            // A folder with no files has nothing banked — the manifest is the only record it
            // existed, which is why the manifest carries folders separately from banked files.
            root.resolve("empty/inner").isDirectory().shouldBeTrue()
        }
    }

    test("restoreSubtree reports a partial restore when a blob is gone") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("part").createDirectories()
            root.resolve("part/a.spec.kts").writeBytes("a".toByteArray())
            root.resolve("part/b.spec.kts").writeBytes("b".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("part"))
            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete

            // Destroy one banked blob behind the store's back, simulating a prune or a wiped
            // history directory between the delete and the undo.
            val victim = command.banked.first()
            LocalHistoryStore.dirFor(victim.absolutePath).resolve(victim.revision.file).deleteExisting()

            val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

            report.restored shouldBe 1
            report.total shouldBe 2
            report.failures shouldHaveSize 1
            // The report alone doesn't prove anything landed on disk -- confirm the surviving file
            // (banked[1], since banked[0] was the victim) actually came back with its content.
            val survivor = command.banked[1]
            val survivorPath = root.resolve("part").resolve(survivor.relPath)
            survivorPath.exists().shouldBeTrue()
            survivorPath.readBytes() shouldBe "b".toByteArray()
        }
    }

    test("restoreSubtree writes files under the CURRENT root, not the absolutePath they had at delete time") {
        // BankedFile carries both relPath and absolutePath precisely because the project root is
        // swappable ("Open Folder..."); an implementation that wrote to absolutePath instead of
        // resolving relPath against the current root would pass every other test in this file (they
        // never repoint the root) and still be wrong.
        withServer { server, player, rootA ->
            EditorUndoStack.clear(player.uuid)
            rootA.resolve("gadget.spec.kts").writeBytes("from-root-a".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("gadget.spec.kts"))
            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            command.banked.single().absolutePath shouldBe rootA.resolve("gadget.spec.kts").toAbsolutePath()

            val rootB = createTempDirectory("undo-net-root-b")
            val prevContext = EditorServerContext.get(server)
            try {
                EditorServerContext.set(server, EditorServerContext(EditorRoot(rootB)))

                val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

                report.failures.shouldBeEmpty()
                rootB.resolve("gadget.spec.kts").exists().shouldBeTrue()
                rootB.resolve("gadget.spec.kts").readBytes() shouldBe "from-root-a".toByteArray()
                // Never written back under the old root -- it no longer exists there to write to.
                rootA.resolve("gadget.spec.kts").exists().shouldBeFalse()
            } finally {
                if (prevContext != null) EditorServerContext.set(server, prevContext)
                deleteRecursively(rootB)
            }
        }
    }

    test("restoreSubtree round-trips a genuine structure .nbt through the typed history path") {
        // The earlier "brings back folders, a .spec.kts and a .nbt" test's .nbt is 4 garbage bytes,
        // which fails NbtIo.readCompressed and so banks RAW -- it never touches the typed
        // (NbtIo.writeCompressed) branch. This test uses a real structure so the typed path -- the
        // feature's primary use case -- gets genuine coverage instead of only being reached by the
        // "blob is gone" failure test, where it throws.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val file = EditorNewStructure.create(root, "widget")
            val originalTag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
            val originalTemplate = StructureTemplate()
            originalTemplate.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), originalTag)
            val originalSize = originalTemplate.size

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("widget.nbt"))
            root.resolve("widget.nbt").exists().shouldBeFalse()

            val command = EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Delete
            val report = EditorFileOpsHandlers.restoreSubtree(server, player, command)

            report.failures.shouldBeEmpty()
            // Gzip re-compression is not byte-deterministic, so compare through a genuine NBT/
            // structure load rather than raw bytes.
            val restoredTag = NbtIo.readCompressed(
                root.resolve("widget.nbt"), NbtAccounter.unlimitedHeap(),
            )
            val restoredTemplate = StructureTemplate()
            restoredTemplate.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), restoredTag)
            restoredTemplate.size shouldBe originalSize
        }
    }
})
