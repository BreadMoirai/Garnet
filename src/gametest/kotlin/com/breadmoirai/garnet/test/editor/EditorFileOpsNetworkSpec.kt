package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.network.EditorFileOpsHandlers
import com.breadmoirai.garnet.editor.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.data.EditorNewStructure
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.world.StructureAutoSave
import com.breadmoirai.garnet.editor.world.StructureCommit
import com.breadmoirai.garnet.editor.world.StructureEditWatcher
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.onServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Model of `EditorStructureNetworkSpec`'s harness: temp project root + a mock server player,
 * wired through `EditorServerContext` so the handlers resolve the temp root.
 *
 * Also redirects [SharedSettings.localHistoryDir] to a per-call temp directory for every test in
 * this file (Task 7 fix round 2, minor): several of these tests place and/or commit structures,
 * which writes real `LocalHistoryStore` revisions, and without this every one of them would litter
 * the real `<gameDir>/.garnet/local-history` instead of a disposable temp dir. No test's assertions
 * depend on the exact path (keys hash from each test's own unique temp root), so this is purely
 * about not leaving blobs behind on the machine actually running the suite.
 */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) {
    val prevHistDir = SharedSettings.localHistoryDir
    val histDir = createTempDirectory("fileops-net-hist")
    SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
    try {
        withTempRoot("fileops-net") { tmp ->
            onServer {
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)
                block(this, player, tmp)
                EditorSession.clear(player.uuid)
            }
        }
    } finally {
        SharedSettings.localHistoryDir = prevHistDir
        histDir.toFile().deleteRecursively()
    }
}

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
})
