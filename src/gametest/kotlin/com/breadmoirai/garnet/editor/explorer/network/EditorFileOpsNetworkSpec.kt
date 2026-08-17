package com.breadmoirai.garnet.editor.explorer.network

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.explorer.network.DeletePathC2S
import com.breadmoirai.garnet.editor.explorer.network.DuplicatePathC2S
import com.breadmoirai.garnet.editor.explorer.network.MovePathC2S
import com.breadmoirai.garnet.editor.explorer.network.NewStructureC2S
import com.breadmoirai.garnet.editor.structure.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.explorer.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.explorer.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.explorer.network.RenamePathC2S
import com.breadmoirai.garnet.editor.structure.network.StructureResultS2C
import com.breadmoirai.garnet.editor.workspace.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.structure.ops.StructureAutoSave
import com.breadmoirai.garnet.editor.structure.ops.StructureCommit
import com.breadmoirai.garnet.editor.structure.ops.StructureEditWatcher
import com.breadmoirai.garnet.editor.explorer.data.EditorSession
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.editor.structure.ops.StructurePersistence
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.withEditorServer
import com.breadmoirai.garnet.harness.GarnetTestSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/** This spec's alias for the shared harness — see `com.breadmoirai.garnet.test.withEditorServer`. */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) =
    withEditorServer("fileops-net", block)

class EditorFileOpsNetworkSpec : GarnetTestSpec({

    test("handleCreateFolder creates a folder at the project root") {
        withServer { server, player, root ->
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder creates a nested folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("redstone", "clocks"))
            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder rejects a parent that escapes the root") {
        withServer { server, player, root ->
            // REGRESSION: the escape target must genuinely EXIST. EditorRoot.resolveSubpath checks
            // exists() BEFORE the containment check, so a non-existent "../evil" would return null at
            // the existence check alone and never exercise containment at all -- a test using a
            // non-existent target passes even if the containment check were deleted outright. Create a
            // real directory outside the root so this only passes if containment itself rejects it.
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("../evil", "x"))
            evil.resolve("x").exists().shouldBeFalse()
        }
    }

    test("handleCreateFolder rejects a name containing a separator") {
        withServer { server, player, root ->
            EditorFileOpsHandlers.handleCreateFolder(server, player, CreateFolderC2S("", "a/b"))
            root.resolve("a").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates in the named folder, not the session's active folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("other").createDirectories()
            EditorSession.setActive(player.uuid, "other")

            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget.nbt"))

            root.resolve("redstone/gadget.nbt").exists().shouldBeTrue()
            root.resolve("other/gadget.nbt").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates at the project root for an empty parent") {
        withServer { server, player, root ->
            EditorStructureHandlers.handleNewStructure(server, player, NewStructureC2S("", "gadget.nbt"))
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }

    test("handleRename renames a folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            root.resolve("logic").isDirectory().shouldBeTrue()
            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleRename rejects a new name that already exists") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("a", "b"))
            root.resolve("a").isDirectory().shouldBeTrue()   // untouched
        }
    }

    test("handleRename rejects a new name containing a separator") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("a", "x/y"))
            root.resolve("a").isDirectory().shouldBeTrue()
        }
    }

    test("renaming a placed structure unloads it and reloads it under the new name") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("ring.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("unplaceStructure before clearBounds keeps a live setBlock mixin from re-dirtying the old subpath") {
        // REGRESSION (Task 7 fix round 2, residual on Finding 1): handleRename's rename-teardown
        // used to call StructurePersistence.clearBounds (which writes AIR through the 3-arg
        // level.setBlock, hooked unconditionally by the setBlock mixin) BEFORE
        // registry.unplaceStructure. A live mixin firing during those writes would still see the
        // OLD subpath registered (EditorDimRegistry.structureSubpathAt still mapped those
        // positions to it) and re-mark it dirty immediately after the commit-before-move loop had
        // just cleared it -- from then on StructureCommit.commit(oldSubpath) can never resolve the
        // (now-moved) file again, so the old key stays dirty forever and defeats tick()'s idle fast
        // path for the rest of the session. handleRename now calls unplaceStructure FIRST.
        //
        // The gametest harness's setBlock mixin is FLAKY (a plain level.setBlock call sometimes
        // fails to trigger StructureEditWatcher here, not reliably never — see
        // feedback_setblock_mixin_flaky_in_gametest), so an end-to-end call to handleRename can't
        // be trusted to exercise this: unplaceStructure has already run, in EITHER order, by the
        // time handleRename returns, so a post-hoc onBlockChanged call can't tell which order
        // production code used, and a flaky mixin would make the test itself flaky besides. This
        // test instead replicates the exact two-call sequence
        // EditorFileOpsHandlers.handleRename's teardown now uses, and simulates what a live mixin would
        // report at the moment clearBounds writes -- with a NEGATIVE CONTROL proving the test can
        // actually detect the bug (the old order) before trusting it to prove the fix (the new
        // order).
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val level = registry.projectLevel()
            val placed = registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            val probePos = placed.origin

            // Negative control: the OLD (buggy) order. clearBounds runs while "clock.nbt" is still
            // registered, so a simulated mixin report at a cleared position still attributes to it.
            StructurePersistence.clearBounds(level, placed.origin, placed.size)
            StructureEditWatcher.onBlockChanged(level, probePos)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()
            StructureAutoSave.of(server).clear("clock.nbt") // reset before exercising the real fix

            // The fixed order, exactly as handleRename's teardown now runs it: unplaceStructure
            // FIRST, so clearBounds's writes land in a region nothing maps to any more.
            registry.unplaceStructure("clock.nbt")
            StructurePersistence.clearBounds(level, placed.origin, placed.size)
            StructureEditWatcher.onBlockChanged(level, probePos)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
        }
    }

    test("renaming a placed AND dirty structure commits first, so no edits are lost") {
        // REGRESSION (reframed): under the old sidecar model a rename could repaint the world from
        // the stale saved file and lose unsaved edits. With auto-save there is no dirty buffer, so
        // the invariant is now that handleRename commits BEFORE moving the file — otherwise the
        // dirty box would be stranded against the old subpath and the edits would be dropped.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            // The edit was committed under the OLD name before the move, then carried across.
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            val result = drainPayloads(player).filterIsInstance<StructureResultS2C>().last()
            result.subpath shouldBe "ring.nbt"
            LocalHistoryStore.revisions(root.resolve("ring.nbt")).size shouldBe 2
        }
    }

    test("renaming a folder rekeys every structure placed beneath it onto the new subpath") {
        // REGRESSION: EditorDimRegistry keys placedBoxes/structureBySubpath by full subpath.
        // handleRename only ever checked registry.placedBoxOf(payload.subpath) -- the EXACT renamed
        // path -- so a placed structure nested under a renamed FOLDER kept its old-path registry key
        // forever: its blocks orphan in-world, StructureCommit.commit resolves the subpath via
        // `rootFor(server).resolveSubpath(subpath) ?: return null` and silently skips it, and
        // clicking the new path re-places a second copy in a fresh region.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("redstone/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("redstone/clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
            registry.structureRegionOriginOf("redstone/clock.nbt").shouldBeNull()
            registry.placedBoxOf("logic/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("logic/clock.nbt").shouldNotBeNull()
        }
    }

    test("renaming a folder commits a dirty structure placed directly inside it") {
        // REGRESSION (Task 7 fix round 1 / Finding 1): handleRename's commit-before-move guard used
        // to be gated on placedBoxOf(payload.subpath) -- the EXACT renamed path. A folder is never
        // itself a placed structure, so renaming a folder committed nothing; rekeyForRename moved
        // the descendant's registry entries onto the new subpath, but StructureAutoSave has no such
        // rekey, so the descendant's dirty entry was stranded under its OLD, now-unresolvable
        // subpath forever -- and its pending edits would never reach the .nbt.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("redstone/clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("redstone/clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            // Committed under the OLD subpath before the move -- neither the old (gone) nor the new
            // key is left dirty; the edit made it into the .nbt via a real commit, not a rekey.
            StructureAutoSave.of(server).isDirty("redstone/clock.nbt").shouldBeFalse()
            StructureAutoSave.of(server).isDirty("logic/clock.nbt").shouldBeFalse()
            LocalHistoryStore.revisions(root.resolve("logic/clock.nbt")).size shouldBe 2
        }
    }

    test("renaming a folder commits a dirty structure nested two levels deep") {
        withServer { server, player, root ->
            root.resolve("redstone/gates").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/gates"), "and")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/gates/and.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("redstone/gates/and.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("redstone/gates/and.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            StructureAutoSave.of(server).isDirty("redstone/gates/and.nbt").shouldBeFalse()
            StructureAutoSave.of(server).isDirty("logic/gates/and.nbt").shouldBeFalse()
            LocalHistoryStore.revisions(root.resolve("logic/gates/and.nbt")).size shouldBe 2
        }
    }

    test("renaming a folder moves local history for every structure placed inside it, not just the renamed path itself") {
        // Isolates Finding 3 from Findings 1/2: the structure is placed but NOT dirty, so the
        // commit-before-move loop has nothing to do for it, and any history movement observed here
        // is purely due to the folder-level moveDescendantHistories walk.
        val prevPlatformWidth = SharedSettings.newStructurePlatformWidth
        // This test isolates the history-move fallout from the commit-before-move fallout, so it
        // needs placing to genuinely leave the structure NOT dirty. The default platform writes
        // blocks through the setBlock mixin on every placement, which would mark it dirty here too.
        SharedSettings.newStructurePlatformWidth = 0
        try {
            withServer { server, player, root ->
                root.resolve("redstone").createDirectories()
                EditorNewStructure.create(root.resolve("redstone"), "clock")
                EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
                drainPayloads(player)
                LocalHistoryStore.revisions(root.resolve("redstone/clock.nbt")).size shouldBe 1 // placed baseline
                StructureAutoSave.of(server).isDirty("redstone/clock.nbt").shouldBeFalse()

                EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

                LocalHistoryStore.revisions(root.resolve("redstone/clock.nbt")) shouldHaveSize 0
                LocalHistoryStore.revisions(root.resolve("logic/clock.nbt")) shouldHaveSize 1
            }
        } finally {
            SharedSettings.newStructurePlatformWidth = prevPlatformWidth
        }
    }

    test("a failed commit during rename aborts the rename entirely and reports an error") {
        // REGRESSION (Task 7 fix round 1 / Finding 2): handleRename used to ignore commit's return
        // value entirely. A genuine commit failure (history write or .nbt write) must abort the
        // whole rename -- proceeding to move the file anyway would invalidate the old subpath and
        // strand those edits permanently, since nothing can ever resolve/commit them again.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            // Force the commit's history write to fail deterministically and portably: occupy
            // index.json's path with a directory (same trick as StructureAutoSaveSpec's
            // "a commit whose history write fails leaves the .nbt untouched").
            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

                // Aborted before touching the filesystem at all.
                root.resolve("clock.nbt").exists().shouldBeTrue()
                root.resolve("ring.nbt").exists().shouldBeFalse()
                // The dirty entry survives under the OLD subpath -- it was never stranded, because
                // the rename that would have stranded it never happened.
                StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                // This test deliberately leaves "clock.nbt" dirty and its history dir booby-trapped;
                // clean both up so no state leaks into a later test sharing this server.
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("a failed rename leaves a placed structure's registry state and blocks intact") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()

            // Force a real moveTo failure: hold the source file open without FILE_SHARE_DELETE, the
            // same "Windows file lock" failure mode the review that prompted this test called out.
            // RandomAccessFile on Windows does not request share-delete, so Files.move onto/of this
            // path fails with a genuine FileSystemException while the handle is held.
            val lock = java.io.RandomAccessFile(root.resolve("clock.nbt").toFile(), "rw")
            try {
                EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))
            } finally {
                lock.close()
            }

            root.resolve("clock.nbt").exists().shouldBeTrue()
            root.resolve("ring.nbt").exists().shouldBeFalse()
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
        }
    }

    test("renaming an ancestor folder repoints the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorSession.setActive(player.uuid, "redstone/clocks")

            EditorFileOpsHandlers.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe "logic/clocks"
        }
    }

    test("handleDuplicate copies a structure to a ' copy' sibling") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            root.resolve("clock copy.nbt").exists().shouldBeTrue()
            root.resolve("clock copy.nbt").readBytes() shouldBe root.resolve("clock.nbt").readBytes()
        }
    }

    test("handleDuplicate counts up when the copy name is taken") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            root.resolve("clock copy.nbt").exists().shouldBeTrue()
            root.resolve("clock copy 2.nbt").exists().shouldBeTrue()
        }
    }

    test("handleDuplicate copies a folder subtree") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("redstone"))

            root.resolve("redstone copy").isDirectory().shouldBeTrue()
            root.resolve("redstone copy/clocks/tick.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clocks/tick.nbt").exists().shouldBeTrue()   // source untouched
        }
    }

    test("handleDuplicate refuses the project root") {
        withServer { server, player, root ->
            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S(""))
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleDuplicate rejects a subpath that escapes the root") {
        withServer { server, player, root ->
            // The escape target must genuinely EXIST — resolveSubpath checks exists() before
            // containment, so a non-existent target would pass this test even with containment
            // deleted outright. Same reasoning as the handleCreateFolder escape test above.
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            evil.resolve("secret.nbt").writeBytes(ByteArray(4))

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("../evil/secret.nbt"))

            evil.resolve("secret copy.nbt").exists().shouldBeFalse()
            root.resolve("secret copy.nbt").exists().shouldBeFalse()
        }
    }

    test("duplicating a dirty placed structure captures its in-world edits, not stale disk content") {
        // The whole reason handleDuplicate quiesces first. Without the commit, the copy is made from
        // the .nbt as it sat before the edit, and the player gets a silently stale duplicate.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            // The commit ran, so the source is clean and both files now hold the edited structure.
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            root.resolve("clock copy.nbt").readBytes() shouldBe root.resolve("clock.nbt").readBytes()
        }
    }

    test("handleDuplicate neither places the copy nor assigns it a region") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock copy.nbt").shouldBeNull()
            registry.structureRegionOriginOf("clock copy.nbt").shouldBeNull()
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()   // the source is still placed
        }
    }

    test("a failed commit aborts the duplicate rather than copying stale content") {
        // The counterpart to the delete case, and the opposite call: a duplicate made from a .nbt
        // that does not match the world is a silently wrong answer the player has no way to spot,
        // so duplicate ABORTS where delete proceeds.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

                root.resolve("clock copy.nbt").exists().shouldBeFalse()
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("a duplicate starts with no local history and leaves the source's alone") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            // Placing seeds a REASON_PLACED baseline revision on the source.
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            LocalHistoryStore.revisions(root.resolve("clock.nbt")).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleDuplicate(server, player, DuplicatePathC2S("clock.nbt"))

            LocalHistoryStore.revisions(root.resolve("clock copy.nbt")).shouldBeEmpty()
            LocalHistoryStore.revisions(root.resolve("clock.nbt")).shouldNotBeEmpty()
        }
    }

    test("handleDelete removes a structure file") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleDelete removes a folder subtree") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleDelete refuses the project root") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S(""))
            root.resolve("clock.nbt").exists().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleDelete rejects a subpath that escapes the root") {
        withServer { server, player, root ->
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            evil.resolve("secret.nbt").writeBytes(ByteArray(4))

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("../evil/secret.nbt"))

            evil.resolve("secret.nbt").exists().shouldBeTrue()
        }
    }

    test("deleting a placed structure unplaces it and drops its region assignment") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("deleting a dirty structure clears its dirty state so tick cannot recreate the file") {
        // The core hazard: a subpath left in StructureAutoSave after its file is gone makes
        // StructureCommit.tick retry on EVERY tick forever -- either failing repeatedly or writing
        // the .nbt back out, resurrecting the file the player just deleted.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            StructureAutoSave.of(server).dirtySubpaths().contains("clock.nbt").shouldBeFalse()

            // commitAll is what BEFORE_SAVE / SERVER_STOPPING run; it must not resurrect the file.
            StructureCommit.commitAll(server, LocalHistoryStore.REASON_AUTOSAVE)
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("deleting a folder clears dirty state for a structure nested inside it") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("redstone/clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            StructureAutoSave.of(server).isDirty("redstone/clock.nbt").shouldBeFalse()
            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
        }
    }

    test("deleting a structure keeps its local history") {
        // History is the recovery route for a delete, so it deliberately outlives the file.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            val file = root.resolve("clock.nbt")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            LocalHistoryStore.revisions(file).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

            file.exists().shouldBeFalse()
            LocalHistoryStore.revisions(file).shouldNotBeEmpty()
        }
    }

    test("a delete proceeds even when the pre-delete commit fails") {
        // Delete deliberately DIVERGES from rename here. Quiescing banks a final recovery revision,
        // but blocking the delete when that fails would make a structure with a broken history dir
        // undeletable from the editor -- for a node the player is explicitly destroying.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            // Booby-trap the history write exactly as the rename-abort test does: occupy
            // index.json's path with a directory so the commit fails deterministically.
            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))

                file.exists().shouldBeFalse()                                   // deleted anyway
                StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
                registry.placedBoxOf("clock.nbt").shouldBeNull()
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("a failed delete leaves the world and registry intact") {
        // Mirrors "a failed rename leaves a placed structure's registry state and blocks intact",
        // and shares its platform assumption: RandomAccessFile on Windows does not request
        // share-delete, so the unlink genuinely fails while the handle is held.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            val lock = java.io.RandomAccessFile(root.resolve("clock.nbt").toFile(), "rw")
            try {
                EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("clock.nbt"))
            } finally {
                lock.close()
            }

            root.resolve("clock.nbt").exists().shouldBeTrue()
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
        }
    }

    test("deleting an ancestor folder clears the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            EditorSession.setActive(player.uuid, "redstone/clocks")

            EditorFileOpsHandlers.handleDelete(server, player, DeletePathC2S("redstone"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe null
        }
    }

    test("handleMove relocates a structure into another folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
            root.resolve("clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleMove relocates a folder into another folder") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("archive").createDirectories()
            EditorNewStructure.create(root.resolve("redstone/clocks"), "tick")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clocks", "archive"))

            root.resolve("archive/clocks/tick.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clocks").exists().shouldBeFalse()
        }
    }

    test("handleMove to the project root uses an empty destination") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clock.nbt", ""))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            root.resolve("redstone/clock.nbt").exists().shouldBeFalse()
        }
    }

    test("handleMove rejects a destination inside the moved folder's own subtree") {
        // The one invariant rename never had to express: moveTo would either throw or nest the
        // folder inside itself, depending on the filesystem.
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstone/clocks"))

            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
            root.resolve("redstone/clocks/redstone").exists().shouldBeFalse()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove rejects a folder into itself") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstone"))
            root.resolve("redstone").isDirectory().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove matches the subtree on a full path segment, not a string prefix") {
        // Moving "redstone" INTO the sibling "redstoneworks" is legal and must not be caught by the
        // descendant check.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("redstoneworks").createDirectories()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "redstoneworks"))

            root.resolve("redstoneworks/redstone").isDirectory().shouldBeTrue()
        }
    }

    test("handleMove rejects a name that already exists in the destination") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            root.resolve("clock.nbt").exists().shouldBeTrue()   // source untouched
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove rejects a file as the destination") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNewStructure.create(root, "ring")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "ring.nbt"))

            root.resolve("clock.nbt").exists().shouldBeTrue()
            drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
        }
    }

    test("handleMove treats the current parent as a no-op") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone/clock.nbt", "redstone"))

            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
        }
    }

    test("handleMove rejects a destination that escapes the root") {
        withServer { server, player, root ->
            val evil = root.resolveSibling("evil").also { it.createDirectories() }
            EditorNewStructure.create(root, "clock")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "../evil"))

            evil.resolve("clock.nbt").exists().shouldBeFalse()
            root.resolve("clock.nbt").exists().shouldBeTrue()
        }
    }

    test("moving a placed structure unloads it and reloads it under the new subpath") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("redstone/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("moving a folder rekeys every structure placed beneath it and carries their history") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("archive").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            LocalHistoryStore.revisions(root.resolve("redstone/clock.nbt")).shouldNotBeEmpty()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "archive"))

            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("archive/redstone/clock.nbt").shouldNotBeNull()
            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
            LocalHistoryStore.revisions(root.resolve("archive/redstone/clock.nbt")).shouldNotBeEmpty()
        }
    }

    test("moving a dirty structure commits first, so no edits are lost") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)
            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

            StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeFalse()
            root.resolve("redstone/clock.nbt").exists().shouldBeTrue()
        }
    }

    test("a failed commit during move aborts the move entirely and reports an error") {
        // Same contract as rename's abort, reached through the shared relocate: a move that
        // proceeded would invalidate the old subpath and strand those edits permanently.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root, "clock")
            EditorStructureHandlers.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            val level = registry.projectLevel()
            level.setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            StructureEditWatcher.onBlockChanged(level, origin)

            val file = root.resolve("clock.nbt")
            val historyDir = LocalHistoryStore.dirFor(file)
            historyDir.resolve("index.json").deleteIfExists()
            historyDir.resolve("index.json").createDirectory()

            try {
                EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("clock.nbt", "redstone"))

                root.resolve("clock.nbt").exists().shouldBeTrue()
                root.resolve("redstone/clock.nbt").exists().shouldBeFalse()
                StructureAutoSave.of(server).isDirty("clock.nbt").shouldBeTrue()
                registry.placedBoxOf("clock.nbt").shouldNotBeNull()
                drainPayloads(player).filterIsInstance<EditorErrorS2C>() shouldHaveSize 1
            } finally {
                StructureAutoSave.of(server).clear("clock.nbt")
                StructureCommit.clearBackoff(server, "clock.nbt")
                historyDir.resolve("index.json").toFile().delete()
            }
        }
    }

    test("moving an ancestor folder repoints the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            root.resolve("archive").createDirectories()
            EditorSession.setActive(player.uuid, "redstone/clocks")

            EditorFileOpsHandlers.handleMove(server, player, MovePathC2S("redstone", "archive"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe "archive/redstone/clocks"
        }
    }
})
