package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.data.EditorNewStructure
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Blocks
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes

/**
 * Model of `EditorStructureNetworkSpec`'s harness: temp project root + a mock server player,
 * wired through `EditorServerContext` so `EditorNetworking` resolves the temp root.
 */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) {
    withTempRoot("fileops-net") { tmp ->
        onServer {
            EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
            val player = makeMockServerPlayer(this)
            drainPayloads(player)
            block(this, player, tmp)
            EditorSession.clear(player.uuid)
        }
    }
}

class EditorFileOpsNetworkSpec : GarnetTestSpec({

    test("handleCreateFolder creates a folder at the project root") {
        withServer { server, player, root ->
            EditorNetworking.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder creates a nested folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNetworking.handleCreateFolder(server, player, CreateFolderC2S("redstone", "clocks"))
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
            EditorNetworking.handleCreateFolder(server, player, CreateFolderC2S("../evil", "x"))
            evil.resolve("x").exists().shouldBeFalse()
        }
    }

    test("handleCreateFolder rejects a name containing a separator") {
        withServer { server, player, root ->
            EditorNetworking.handleCreateFolder(server, player, CreateFolderC2S("", "a/b"))
            root.resolve("a").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates in the named folder, not the session's active folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("other").createDirectories()
            EditorSession.setActive(player.uuid, "other")

            EditorNetworking.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget.nbt"))

            root.resolve("redstone/gadget.nbt").exists().shouldBeTrue()
            root.resolve("other/gadget.nbt").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates at the project root for an empty parent") {
        withServer { server, player, root ->
            EditorNetworking.handleNewStructure(server, player, NewStructureC2S("", "gadget.nbt"))
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }

    test("handleRename renames a folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNetworking.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            root.resolve("logic").isDirectory().shouldBeTrue()
            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleRename moves a structure's unsaved sidecar with it") {
        withServer { server, player, root ->
            val nbt = root.resolve("clock.nbt")
            EditorNewStructure.create(root, "clock")
            StructurePersistence.unsavedSidecarOf(nbt).writeBytes(byteArrayOf(1, 2, 3))

            EditorNetworking.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            root.resolve("ring.nbt").exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(root.resolve("ring.nbt")).exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(nbt).exists().shouldBeFalse()
        }
    }

    test("handleRename rejects a new name that already exists") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            EditorNetworking.handleRename(server, player, RenamePathC2S("a", "b"))
            root.resolve("a").isDirectory().shouldBeTrue()   // untouched
        }
    }

    test("handleRename rejects a new name containing a separator") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            EditorNetworking.handleRename(server, player, RenamePathC2S("a", "x/y"))
            root.resolve("a").isDirectory().shouldBeTrue()
        }
    }

    test("renaming a placed structure unloads it and reloads it under the new name") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNetworking.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            EditorNetworking.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("ring.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("renaming a placed AND dirty structure re-places from its sidecar, not the stale saved file") {
        // REGRESSION: handlePlaceStructure already prefers the ".nbt.unsaved" sidecar over the saved
        // file when one exists (see its own body). handleRename's re-place call used to hard-code
        // hasUnsaved = false and pass the SAVED file unconditionally -- so renaming a structure with
        // unsaved edits repainted the world from the stale save, and the next flushDirtyStructures
        // would then capture that reverted region right back over the (still-present) sidecar,
        // destroying the unsaved edits for good. This covers the intersection the suite otherwise
        // misses: a structure that is BOTH placed AND dirty, then renamed.
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNetworking.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            drainPayloads(player)

            // Make the placed region genuinely dirty via the REAL path (StructurePersistence's
            // flush), not a hand-written garbage sidecar the re-place below could never actually load
            // as a structure: place a block inside the region, then flush.
            val registry = EditorDimRegistry.of(server)
            val origin = registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()
            registry.projectLevel().setBlock(origin, Blocks.STONE.defaultBlockState(), 3)
            EditorNetworking.flushDirtyStructures(server)
            StructurePersistence.unsavedSidecarOf(root.resolve("clock.nbt")).exists().shouldBeTrue()

            EditorNetworking.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            val result = drainPayloads(player).filterIsInstance<StructureResultS2C>().last()
            result.subpath shouldBe "ring.nbt"
            result.hasUnsaved.shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(root.resolve("ring.nbt")).exists().shouldBeTrue()
        }
    }

    test("renaming a folder rekeys every structure placed beneath it onto the new subpath") {
        // REGRESSION: EditorDimRegistry keys placedBoxes/structureBySubpath by full subpath.
        // handleRename only ever checked registry.placedBoxOf(payload.subpath) -- the EXACT renamed
        // path -- so a placed structure nested under a renamed FOLDER kept its old-path registry key
        // forever: its blocks orphan in-world, flushDirtyStructures does
        // `resolveSubpath(oldSubpath) ?: continue` and silently skips it, and clicking the new path
        // re-places a second copy in a fresh region.
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            EditorNewStructure.create(root.resolve("redstone"), "clock")
            EditorNetworking.handlePlaceStructure(server, player, PlaceStructureC2S("redstone/clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("redstone/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("redstone/clock.nbt").shouldNotBeNull()

            EditorNetworking.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            registry.placedBoxOf("redstone/clock.nbt").shouldBeNull()
            registry.structureRegionOriginOf("redstone/clock.nbt").shouldBeNull()
            registry.placedBoxOf("logic/clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("logic/clock.nbt").shouldNotBeNull()
        }
    }

    test("a failed rename leaves a placed structure's registry state and blocks intact") {
        withServer { server, player, root ->
            EditorNewStructure.create(root, "clock")
            EditorNetworking.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = EditorDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldNotBeNull()

            // Force a real moveTo failure: hold the source file open without FILE_SHARE_DELETE, the
            // same "Windows file lock" failure mode the review that prompted this test called out.
            // RandomAccessFile on Windows does not request share-delete, so Files.move onto/of this
            // path fails with a genuine FileSystemException while the handle is held.
            val lock = java.io.RandomAccessFile(root.resolve("clock.nbt").toFile(), "rw")
            try {
                EditorNetworking.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))
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

            EditorNetworking.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            EditorSession.get(player.uuid)!!.activeSubpath shouldBe "logic/clocks"
        }
    }
})
