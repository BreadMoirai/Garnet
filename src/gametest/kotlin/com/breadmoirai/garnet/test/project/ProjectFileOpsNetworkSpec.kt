package com.breadmoirai.garnet.test.project

import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.network.project.ProjectNetworkRegistry
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.persistence.StructurePersistence
import com.breadmoirai.garnet.project.ProjectDimRegistry
import com.breadmoirai.garnet.project.ProjectNewStructure
import com.breadmoirai.garnet.project.ProjectRoot
import com.breadmoirai.garnet.project.ProjectServerContext
import com.breadmoirai.garnet.project.ProjectSession
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes

/**
 * Model of `ProjectStructureNetworkSpec`'s harness: temp project root + a mock server player,
 * wired through `ProjectServerContext` so `ProjectNetworkRegistry` resolves the temp root.
 */
private suspend fun withServer(block: suspend (server: MinecraftServer, player: ServerPlayer, root: Path) -> Unit) {
    withTempRoot("fileops-net") { tmp ->
        onServer {
            ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
            val player = makeMockServerPlayer(this)
            drainPayloads(player)
            block(this, player, tmp)
            ProjectSession.clear(player.uuid)
        }
    }
}

class ProjectFileOpsNetworkSpec : GarnetTestSpec({

    test("handleCreateFolder creates a folder at the project root") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("", "toplevel"))
            root.resolve("toplevel").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder creates a nested folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("redstone", "clocks"))
            root.resolve("redstone/clocks").isDirectory().shouldBeTrue()
        }
    }

    test("handleCreateFolder rejects a parent that escapes the root") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("../evil", "x"))
            root.resolveSibling("evil").exists().shouldBeFalse()
        }
    }

    test("handleCreateFolder rejects a name containing a separator") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleCreateFolder(server, player, CreateFolderC2S("", "a/b"))
            root.resolve("a").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates in the named folder, not the session's active folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            root.resolve("other").createDirectories()
            ProjectSession.setActive(player.uuid, "other")

            ProjectNetworkRegistry.handleNewStructure(server, player, NewStructureC2S("redstone", "gadget.nbt"))

            root.resolve("redstone/gadget.nbt").exists().shouldBeTrue()
            root.resolve("other/gadget.nbt").exists().shouldBeFalse()
        }
    }

    test("handleNewStructure creates at the project root for an empty parent") {
        withServer { server, player, root ->
            ProjectNetworkRegistry.handleNewStructure(server, player, NewStructureC2S("", "gadget.nbt"))
            root.resolve("gadget.nbt").exists().shouldBeTrue()
        }
    }

    test("handleRename renames a folder") {
        withServer { server, player, root ->
            root.resolve("redstone").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("redstone", "logic"))
            root.resolve("logic").isDirectory().shouldBeTrue()
            root.resolve("redstone").exists().shouldBeFalse()
        }
    }

    test("handleRename moves a structure's unsaved sidecar with it") {
        withServer { server, player, root ->
            val nbt = root.resolve("clock.nbt")
            ProjectNewStructure.create(root, "clock")
            StructurePersistence.unsavedSidecarOf(nbt).writeBytes(byteArrayOf(1, 2, 3))

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            root.resolve("ring.nbt").exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(root.resolve("ring.nbt")).exists().shouldBeTrue()
            StructurePersistence.unsavedSidecarOf(nbt).exists().shouldBeFalse()
        }
    }

    test("handleRename rejects a new name that already exists") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            root.resolve("b").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("a", "b"))
            root.resolve("a").isDirectory().shouldBeTrue()   // untouched
        }
    }

    test("handleRename rejects a new name containing a separator") {
        withServer { server, player, root ->
            root.resolve("a").createDirectories()
            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("a", "x/y"))
            root.resolve("a").isDirectory().shouldBeTrue()
        }
    }

    test("renaming a placed structure unloads it and reloads it under the new name") {
        withServer { server, player, root ->
            ProjectNewStructure.create(root, "clock")
            ProjectNetworkRegistry.handlePlaceStructure(server, player, PlaceStructureC2S("clock.nbt"))
            val registry = ProjectDimRegistry.of(server)
            registry.placedBoxOf("clock.nbt").shouldNotBeNull()

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("clock.nbt", "ring.nbt"))

            registry.placedBoxOf("clock.nbt").shouldBeNull()
            registry.placedBoxOf("ring.nbt").shouldNotBeNull()
            registry.structureRegionOriginOf("clock.nbt").shouldBeNull()
        }
    }

    test("renaming an ancestor folder repoints the active session") {
        withServer { server, player, root ->
            root.resolve("redstone/clocks").createDirectories()
            ProjectSession.setActive(player.uuid, "redstone/clocks")

            ProjectNetworkRegistry.handleRename(server, player, RenamePathC2S("redstone", "logic"))

            ProjectSession.get(player.uuid)!!.activeSubpath shouldBe "logic/clocks"
        }
    }
})
