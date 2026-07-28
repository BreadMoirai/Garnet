package com.breadmoirai.garnet.test.project

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.network.project.DiscardStructureC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.PlaceStructureC2S
import com.breadmoirai.garnet.network.project.SaveStructureC2S
import com.breadmoirai.garnet.network.project.StructureResultS2C
import com.breadmoirai.garnet.network.project.ProjectErrorS2C
import com.breadmoirai.garnet.network.project.ProjectNetworkRegistry
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
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
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.exists
import kotlin.io.path.readBytes

class ProjectStructureNetworkSpec : GarnetTestSpec({

    test("place then save round-trips a standalone structure via handlers") {
        withTempRoot("struct-net") { tmp ->
            // Keep the scanned region tiny so the full-height scan is fast in-test.
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1
            ProjectNewStructure.create(tmp, "gadget")  // seed an empty gadget.nbt at root
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                // Place: empty structure -> size 0,0,0, region assigned, player teleported.
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("gadget.nbt"))
                val placed = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                placed.subpath shouldBe "gadget.nbt"

                // Build a block in the assigned region, then save: captures a 1x1x1 box.
                val region = ProjectDimRegistry.of(this).structureRegionOriginOf("gadget.nbt")!!
                val width = SharedSettings.structureRegionChunks * 16
                val lvl = overworld()
                // The gametest world's floor terrain isn't part of the region until this test
                // builds it; clear the column so the auto-fit save below doesn't capture it.
                StructurePersistence.clearBounds(
                    lvl,
                    BlockPos(region.x, lvl.minY, region.z),
                    Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                )
                overworld().setBlock(region.offset(5, 0, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.handleSaveStructure(this, player, SaveStructureC2S("gadget.nbt"))
                val saved = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                saved.sizeX shouldBe 1; saved.sizeY shouldBe 1; saved.sizeZ shouldBe 1

                ProjectSession.clear(player.uuid)
            }
            SharedSettings.structureRegionChunks = prevChunks
        }
    }

    test("place rejects a non-.nbt subpath") {
        withTempRoot("struct-net-bad") { tmp ->
            (tmp.resolve("notes.txt")).toFile().writeText("hi")
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("notes.txt"))
                drainPayloads(player).filterIsInstance<ProjectErrorS2C>() shouldHaveSize 1
                ProjectSession.clear(player.uuid)
            }
        }
    }

    test("save without placing this session is refused and does not touch the file") {
        withTempRoot("struct-net-unplaced") { tmp ->
            ProjectNewStructure.create(tmp, "unplaced")  // seed unplaced.nbt at root, never placed
            val before = java.nio.file.Files.readAllBytes(tmp.resolve("unplaced.nbt"))
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handleSaveStructure(this, player, SaveStructureC2S("unplaced.nbt"))
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ProjectErrorS2C>() shouldHaveSize 1
                payloads.filterIsInstance<StructureResultS2C>() shouldHaveSize 0

                val after = java.nio.file.Files.readAllBytes(tmp.resolve("unplaced.nbt"))
                after shouldBe before

                ProjectDimRegistry.of(this).structureRegionOriginOf("unplaced.nbt").shouldBeNull()

                ProjectSession.clear(player.uuid)
            }
        }
    }

    test("placing a corrupt .nbt replies with an error instead of throwing") {
        withTempRoot("struct-net-corrupt") { tmp ->
            java.nio.file.Files.write(tmp.resolve("corrupt.nbt"), byteArrayOf(1, 2, 3, 4, 5))
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("corrupt.nbt"))
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ProjectErrorS2C>() shouldHaveSize 1
                payloads.filterIsInstance<StructureResultS2C>() shouldHaveSize 0

                ProjectSession.clear(player.uuid)
            }
        }
    }

    test("new structure creates the file and re-sends the tree") {
        withTempRoot("struct-net-new") { tmp ->
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                ProjectSession.setActive(player.uuid, "")  // active folder = root
                drainPayloads(player)
                ProjectNetworkRegistry.handleNewStructure(this, player, NewStructureC2S("fresh"))
                tmp.resolve("fresh.nbt").exists() shouldBe true
                drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>() shouldHaveSize 1
                ProjectSession.clear(player.uuid)
            }
        }
    }

    test("dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear") {
        withTempRoot("struct-dirty") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            SharedSettings.structureRegionChunks = 1
            ProjectNewStructure.create(tmp, "widget") // empty widget.nbt at root
            val committed = tmp.resolve("widget.nbt")
            val sidecar = com.breadmoirai.garnet.persistence.StructurePersistence.unsavedSidecarOf(committed)
            onServer {
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                val placed = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                placed.hasUnsaved shouldBe false

                val region = ProjectDimRegistry.of(this).structureRegionOriginOf("widget.nbt")!!
                val width = SharedSettings.structureRegionChunks * 16
                val lvl = overworld()
                StructurePersistence.clearBounds(
                    lvl, BlockPos(region.x, lvl.minY, region.z),
                    Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                )
                // Snapshot the committed file's bytes so we can prove the flush never touches it.
                val committedBefore = committed.readBytes()
                // Edit the region, then flush (simulates a world-save): sidecar appears, committed untouched.
                lvl.setBlock(region.offset(5, 0, 5), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.flushDirtyStructures(this)
                sidecar.exists() shouldBe true
                // Flush writes ONLY the sidecar — the committed .nbt is byte-for-byte unchanged.
                committed.readBytes().toList() shouldBe committedBefore.toList()

                // Re-place: loads the unsaved sidecar (1x1x1 gold), reports hasUnsaved.
                ProjectNetworkRegistry.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                val replaced = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                replaced.hasUnsaved shouldBe true
                replaced.sizeX shouldBe 1

                // Explicit Save: writes committed, deletes sidecar, reports clean.
                ProjectNetworkRegistry.handleSaveStructure(this, player, SaveStructureC2S("widget.nbt"))
                val saved = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                saved.hasUnsaved shouldBe false
                sidecar.exists() shouldBe false

                // Edit again + flush -> sidecar reappears; then Discard removes it and re-places committed.
                lvl.setBlock(region.offset(6, 0, 6), Blocks.IRON_BLOCK.defaultBlockState(), 2)
                ProjectNetworkRegistry.flushDirtyStructures(this)
                sidecar.exists() shouldBe true
                ProjectNetworkRegistry.handleDiscardStructure(this, player, DiscardStructureC2S("widget.nbt"))
                val discarded = drainPayloads(player).filterIsInstance<StructureResultS2C>().single()
                discarded.hasUnsaved shouldBe false
                sidecar.exists() shouldBe false
            }
            SharedSettings.structureRegionChunks = prevChunks
        }
    }
})
