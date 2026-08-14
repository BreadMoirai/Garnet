package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.DeletePathC2S
import com.breadmoirai.garnet.editor.network.DuplicatePathC2S
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.network.EditorTreeHandlers
import com.breadmoirai.garnet.editor.network.MovePathC2S
import com.breadmoirai.garnet.editor.network.NewEditorSpecC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.structure.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.StructureEditWatcher
import com.breadmoirai.garnet.editor.undo.CreatedFileKind
import com.breadmoirai.garnet.editor.undo.EditorUndoCommand
import com.breadmoirai.garnet.editor.undo.EditorUndoOps
import com.breadmoirai.garnet.editor.undo.EditorUndoStack
import com.breadmoirai.garnet.editor.undo.RelocateKind
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.moveTo
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** This spec's alias for the shared harness — see `com.breadmoirai.garnet.test.withEditorServer`. */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) =
    withEditorServer("undo-net", block)

/**
 * The bounding size of the structure stored in [file], loaded through a genuine [StructureTemplate]
 * rather than compared as bytes: gzip re-compression is not byte-deterministic, so raw bytes cannot
 * tell "same content" from "recompressed content", while a template's size changes the moment the
 * captured block set does.
 */
private fun templateSizeOf(server: MinecraftServer, file: Path): Vec3i {
    val tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap())
    val template = StructureTemplate()
    template.load(server.registryAccess().lookupOrThrow(Registries.BLOCK), tag)
    return template.size
}

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

    test("handleCreateFolder records a CreateFolder command") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe EditorUndoCommand.CreateFolder("toplevel")
        }
    }

    test("handleNewStructure records a CreateFile command with the resolved name") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget"))
            // The handler appends ".nbt" itself, so the command must record the RESOLVED name —
            // recording the requested one would make undo look for a file that does not exist.
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.CreateFile("redstone/gadget.nbt", CreatedFileKind.STRUCTURE)
        }
    }

    test("handleDuplicate records the server-derived copy name") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gadget.nbt").writeBytes(byteArrayOf(1))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("gadget.nbt"))

            val command = EditorUndoStack.peekUndo(player.uuid)
            command.shouldBeInstanceOf<EditorUndoCommand.Duplicate>()
            // Whatever EditorNames.duplicateName chose, the command must name the file that now
            // exists — this is the case the store-the-packet approach could not express.
            root.resolve(command.createdSubpath).exists().shouldBeTrue()
            command.createdSubpath shouldNotBe "gadget.nbt"
        }
    }

    test("handleRename records a RENAME relocate") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("redstone", "logic", RelocateKind.RENAME)
        }
    }

    test("handleMove records a MOVE relocate") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("dest").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("a", "dest"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("a", "dest/a", RelocateKind.MOVE)
        }
    }

    test("a rejected rename records nothing") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("a", "b"))
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }

    // Fix round 1: handleNewSpec was the one wired operation with zero recording coverage.
    // EditorNewSpec.create only validates `name` (regex) and appends ".spec.kts" -- it never
    // transforms the stem itself, so there is no input for which the resolved filename differs
    // from `payload.name` beyond that suffix. A naive implementation that recorded `payload.name`
    // directly (no suffix, and for the empty-activeSubpath case no folder prefix) would still
    // diverge visibly from what these two tests assert, which is what pins the real behaviour:
    // the RESOLVED filename via `Path.name`, and correct subpath composition at both the project
    // root and a nested active folder.
    test("handleNewSpec records a CreateFile command at the project root") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorSession.setActive(player.uuid, "")
            EditorTreeHandlers.handleNewSpec(server, player, NewEditorSpecC2S("fresh"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.CreateFile("fresh.spec.kts", CreatedFileKind.SPEC)
        }
    }

    test("handleNewSpec records a CreateFile command in a nested active folder") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("parent/leaf").createDirectories()
            EditorSession.setActive(player.uuid, "parent/leaf")
            EditorTreeHandlers.handleNewSpec(server, player, NewEditorSpecC2S("fresh"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.CreateFile("parent/leaf/fresh.spec.kts", CreatedFileKind.SPEC)
        }
    }

    // Fix round 1: this branch was audited but never asserted -- moving a node into the folder it
    // already lives in must resend the tree but push nothing, since nothing moved.
    test("handleMove into the folder it already lives in pushes nothing") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("dest/a").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("dest/a", "dest"))
            EditorUndoStack.peekUndo(player.uuid).shouldBeNull()
        }
    }

    test("undo of a create folder removes it; redo puts it back") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))

            EditorUndoOps.undo(server, player)
            root.resolve("toplevel").exists().shouldBeFalse()

            EditorUndoOps.redo(server, player)
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("undo of a rename moves it back") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            EditorUndoOps.undo(server, player)

            root.resolve("redstone").isDirectory().shouldBeTrue()
            root.resolve("logic").exists().shouldBeFalse()
        }
    }

    test("undo of a move restores the original parent") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("a").createDirectories()
            root.resolve("dest").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("a", "dest"))

            EditorUndoOps.undo(server, player)

            root.resolve("a").isDirectory().shouldBeTrue()
            root.resolve("dest/a").exists().shouldBeFalse()
        }
    }

    test("undo of a duplicate removes the copy, not the original") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gadget.nbt").writeBytes(byteArrayOf(1))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("gadget.nbt"))
            val copy = (EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Duplicate).createdSubpath

            EditorUndoOps.undo(server, player)

            root.resolve(copy).exists().shouldBeFalse()
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }

    test("undo of a delete restores the whole subtree") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("redstone/clocks/a.spec.kts").writeBytes("a".toByteArray())
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            EditorUndoOps.undo(server, player)

            root.resolve("redstone/clocks/a.spec.kts").readBytes() shouldBe "a".toByteArray()
        }
    }

    test("undo refuses and keeps the entry when the node moved underneath it") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            drainPayloads(player)

            // Someone else moves it out from under the pending undo.
            root.resolve("logic").moveTo(root.resolve("elsewhere"))

            EditorUndoOps.undo(server, player)

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            // The entry SURVIVES: the player can retry once the conflict is resolved, rather than
            // silently skipping to an older action they did not ask to undo.
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("redstone", "logic", RelocateKind.RENAME)
            // A refusal touches NEITHER deque -- asserting only the undo side would miss an
            // implementation that seated a redo entry for an inverse that never ran.
            EditorUndoStack.peekRedo(player.uuid).shouldBeNull()
        }
    }

    test("undo refuses when the delete's path is occupied again") {
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gone.spec.kts").writeBytes("x".toByteArray())
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("gone.spec.kts"))
            root.resolve("gone.spec.kts").writeBytes("something else".toByteArray())
            drainPayloads(player)

            EditorUndoOps.undo(server, player)

            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            // Never clobber content that arrived after the delete.
            root.resolve("gone.spec.kts").readBytes() shouldBe "something else".toByteArray()
            EditorUndoStack.peekUndo(player.uuid).shouldBeInstanceOf<EditorUndoCommand.Delete>()
            EditorUndoStack.peekRedo(player.uuid).shouldBeNull()
        }
    }

    test("undo refuses and keeps the entry when NOTHING could be restored") {
        // Fix round 1 / Important 1: restoreSubtree signals total failure as a report, not a throw.
        // Treating that as success popped the Delete entry with the filesystem untouched, and seated
        // a redo entry that could never fire ("already gone"). A partial restore still succeeds --
        // that is the case the "reports a partial restore" test above pins -- so the two must be
        // distinguished, which is what RestoreReport.foldersCreated is for.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("holder").createDirectories()
            root.resolve("holder/a.spec.kts").writeBytes("a".toByteArray())
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("holder/a.spec.kts"))

            // The parent the restore would rebuild beneath is now gone, so not one byte can land.
            root.resolve("holder").deleteExisting()
            drainPayloads(player)

            EditorUndoOps.undo(server, player)

            root.resolve("holder").exists().shouldBeFalse()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
            EditorUndoStack.peekUndo(player.uuid).shouldBeInstanceOf<EditorUndoCommand.Delete>()
            EditorUndoStack.peekRedo(player.uuid).shouldBeNull()
        }
    }

    test("redo of a duplicate brings the copy back WITH its bytes") {
        // The create round-trip test above uses an empty folder, so it proves only that a bank was
        // stapled on -- not that the bank carries content. This is the failure the brief warned
        // about: a redo that recreates an empty shell where the file had contents.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("gadget.spec.kts").writeBytes("payload-bytes".toByteArray())
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("gadget.spec.kts"))
            val copy = (EditorUndoStack.peekUndo(player.uuid) as EditorUndoCommand.Duplicate).createdSubpath
            root.resolve(copy).readBytes() shouldBe "payload-bytes".toByteArray()

            EditorUndoOps.undo(server, player)
            root.resolve(copy).exists().shouldBeFalse()

            EditorUndoOps.redo(server, player)

            root.resolve(copy).exists().shouldBeTrue()
            root.resolve(copy).readBytes() shouldBe "payload-bytes".toByteArray()
            // The original is untouched throughout.
            root.resolve("gadget.spec.kts").readBytes() shouldBe "payload-bytes".toByteArray()
        }
    }

    test("a redo does not discard the redo entries above it") {
        // The entire reason EditorUndoStack.pushUndoWithoutClearingRedo exists. Swap it for push()
        // and every other test in this file still passes, while the redo branch is silently thrown
        // away on the first redo.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "one"))
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "two"))

            EditorUndoOps.undo(server, player)   // removes "two"; redo = [two]
            EditorUndoOps.undo(server, player)   // removes "one"; redo = [two, one]
            EditorUndoOps.redo(server, player)   // replays "one"; redo must still hold [two]

            root.resolve("one").isDirectory().shouldBeTrue()
            root.resolve("two").exists().shouldBeFalse()
            val remaining = EditorUndoStack.peekRedo(player.uuid)
            remaining.shouldBeInstanceOf<EditorUndoCommand.CreateFolder>()
            remaining.subpath shouldBe "two"

            // And it is genuinely still redoable, not just present.
            EditorUndoOps.redo(server, player)
            root.resolve("two").isDirectory().shouldBeTrue()
        }
    }

    test("redo of a delete banks the CURRENT bytes, so a later undo does not revert an intervening edit") {
        // Final review / MUST FIX 2. `deleteSubtree` hands back a Delete carrying a bank of exactly
        // what was on disk at THAT moment. redo() used to discard it and re-seat the ORIGINAL
        // command, whose bank predates the undo -- so any edit made between the undo and the redo
        // was silently reverted by the next undo, with no error anywhere.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val spec = root.resolve("clock.spec.kts")
            spec.writeBytes("original".toByteArray())

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.spec.kts"))
            EditorUndoOps.undo(server, player)
            spec.readBytes() shouldBe "original".toByteArray()

            // The player edits the file the undo just gave back, then redoes the delete.
            spec.writeBytes("edited-after-the-undo".toByteArray())

            EditorUndoOps.redo(server, player)
            spec.exists().shouldBeFalse()

            EditorUndoOps.undo(server, player)

            spec.readBytes() shouldBe "edited-after-the-undo".toByteArray()
        }
    }

    test("redo of a delete quiesces first, so an in-world edit to the restored structure is not lost") {
        // Final review / MUST FIX 1. The pre-delete `commitDirtyUnder` used to live in
        // `handleDelete`, so the redo path reached `deleteSubtree` with none of it: bankFile read
        // the stale .nbt while the edit was still only in the world, and `deleteAndTearDown` then
        // cleared the dirty state -- destroying the edit with no error and no way back.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            val file = root.resolve("clock.nbt")
            EditorNewStructure.create(root, "clock")

            // Get to a state where the pending undo entry is a Delete: delete, then undo.
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))
            EditorUndoOps.undo(server, player)
            file.exists().shouldBeTrue()

            // restoreSubtree deliberately brings structures back UNPLACED, so the restored file has
            // to be placed again before it can be edited in-world.
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)
            val sizeBeforeEdit = templateSizeOf(server, file)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            // Drive the watcher directly: the harness's setBlock mixin is flaky.
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorUndoOps.redo(server, player)
            file.exists().shouldBeFalse()

            EditorUndoOps.undo(server, player)

            file.exists().shouldBeTrue()
            // The bank the redo took must contain the block that was still only in the world when
            // the redo started. Without the quiesce this comes back byte-identical to the pre-edit
            // structure, and the size is unchanged.
            templateSizeOf(server, file) shouldNotBe sizeBeforeEdit
        }
    }

    test("undo with an empty stack reports an error and does nothing") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            drainPayloads(player)
            EditorUndoOps.undo(server, player)
            drainPayloads(player).filterIsInstance<EditorErrorS2C>().shouldNotBeEmpty()
        }
    }

    test("undo keeps the entry when the relocate itself fails") {
        // The one failure mode the stack must not have: `relocate` reports its own failures and used
        // to return Unit, so a move that threw INSIDE it still popped the entry and seated it on
        // redo -- the stack claiming an undo that never happened, and the player losing the entry
        // that would have let them retry. moveBack's preconditions cover staleness, not IO.
        //
        // The lever: resolveSubpath only checks that the destination's parent EXISTS, not that it is
        // a folder. Replacing the original parent folder with a regular file therefore passes every
        // precondition and makes `moveTo` throw deterministically on every platform -- unlike
        // occupying the target itself, which the `target.exists()` precondition refuses first.
        withServer { server, player, root ->
            EditorUndoStack.clear(player.uuid)
            root.resolve("parent/a").createDirectories()
            root.resolve("dest").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("parent/a", "dest"))
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("parent/a", "dest/a", RelocateKind.MOVE)

            root.resolve("parent").deleteExisting()
            root.resolve("parent").writeBytes(byteArrayOf(0))
            drainPayloads(player)

            EditorUndoOps.undo(server, player)

            root.resolve("dest/a").isDirectory().shouldBeTrue()      // nothing moved
            EditorUndoStack.peekUndo(player.uuid) shouldBe
                EditorUndoCommand.Relocate("parent/a", "dest/a", RelocateKind.MOVE)
            EditorUndoStack.peekRedo(player.uuid).shouldBeNull()
            // relocate reports its own failure; undo must not send a second packet for the same
            // event, which is what Inverted.Refused.alreadyReported exists to prevent.
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("a rename of a placed structure survives undo still placed") {
        withServer { server, player, _ ->
            EditorUndoStack.clear(player.uuid)
            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("", "gadget"))
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("gadget.nbt"))
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("gadget.nbt", "widget.nbt"))
            EditorDimRegistry.of(server).placedBoxOf("widget.nbt").shouldNotBeNull()

            EditorUndoOps.undo(server, player)

            // relocate re-places and re-keys; undo goes through the same function, so this is the
            // guard that undo did not take a shortcut around it.
            EditorDimRegistry.of(server).placedBoxOf("gadget.nbt").shouldNotBeNull()
            EditorDimRegistry.of(server).placedBoxOf("widget.nbt").shouldBeNull()
        }
    }
})
