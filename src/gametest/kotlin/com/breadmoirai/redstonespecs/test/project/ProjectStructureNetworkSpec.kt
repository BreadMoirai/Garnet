package com.breadmoirai.redstonespecs.test.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.network.project.NewStructureC2S
import com.breadmoirai.redstonespecs.network.project.PlaceStructureC2S
import com.breadmoirai.redstonespecs.network.project.SaveStructureC2S
import com.breadmoirai.redstonespecs.network.project.StructureResultS2C
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectNetworkRegistry
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.persistence.StructurePersistence
import com.breadmoirai.redstonespecs.project.ProjectDimRegistry
import com.breadmoirai.redstonespecs.project.ProjectNewStructure
import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectServerContext
import com.breadmoirai.redstonespecs.project.ProjectSession
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.exists

class ProjectStructureNetworkSpec : RedstoneTestSpec({

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
})
