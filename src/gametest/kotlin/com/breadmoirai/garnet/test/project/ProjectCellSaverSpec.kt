package com.breadmoirai.garnet.test.project

import com.breadmoirai.garnet.project.ProjectDimLifecycle
import com.breadmoirai.garnet.project.ProjectDimRegistry
import com.breadmoirai.garnet.project.ProjectRoot
import com.breadmoirai.garnet.project.ProjectWorld
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ProjectCellSaverSpec : GarnetTestSpec({

    test("no mutation -> not saved") {
        withTempRoot("project-saver-clean") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ProjectRoot(tmp)
                ProjectDimLifecycle.placeAll(this, root)
                // Re-place to baseline the snapshot deterministically.
                val world = ProjectWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ProjectDimRegistry.of(this).projectLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ProjectDimLifecycle.placeFolder(this, root, "set")

                val r = ProjectDimLifecycle.saveFolder(this, "set")
                ProjectWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("mutation inside cell -> saved and .nbt written") {
        withTempRoot("project-saver-dirty") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ProjectRoot(tmp)
                ProjectDimLifecycle.placeAll(this, root)
                val world = ProjectWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ProjectDimRegistry.of(this).projectLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ProjectDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ProjectDimLifecycle.saveFolder(this, "set")
                ProjectWorld.clear(this)
                r
            }

            val r = results.single { it.specId == "a" }
            r.saved shouldBe true
            // Stub spec id == structure id "a" → folder/a.nbt should exist.
            tmp.resolve("set/a.nbt").exists() shouldBe true
        }
    }

    test("mutation outside cell bounds -> not saved") {
        withTempRoot("project-saver-outside") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ProjectRoot(tmp)
                ProjectDimLifecycle.placeAll(this, root)
                val world = ProjectWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ProjectDimRegistry.of(this).projectLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ProjectDimLifecycle.placeFolder(this, root, "set")

                // 5 blocks beyond the +X face of the AABB — outside the cell volume.
                level.setBlock(abs.offset(bounds.x + 5, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ProjectDimLifecycle.saveFolder(this, "set")
                ProjectWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("snapshot refresh: second saveFolder after a save returns saved=false") {
        withTempRoot("project-saver-refresh") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val (first, second) = onServer {
                makeMockServerPlayer(this)
                val root = ProjectRoot(tmp)
                ProjectDimLifecycle.placeAll(this, root)
                val world = ProjectWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ProjectDimRegistry.of(this).projectLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ProjectDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r1 = ProjectDimLifecycle.saveFolder(this, "set")
                val r2 = ProjectDimLifecycle.saveFolder(this, "set")
                ProjectWorld.clear(this)
                r1 to r2
            }

            first.single { it.specId == "a" }.saved shouldBe true
            second.single { it.specId == "a" }.saved shouldBe false
        }
    }
})
