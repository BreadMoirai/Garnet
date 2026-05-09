package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.Vec3i
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManagedCellSaverSpec : RedstoneTestSpec({

    test("no mutation -> not saved") {
        withTempRoot("managed-saver-clean") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                // Re-place to baseline the snapshot deterministically.
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("mutation inside cell -> saved and .nbt written") {
        withTempRoot("managed-saver-dirty") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            val r = results.single { it.specId == "a" }
            r.saved shouldBe true
            // Stub spec id == structure id "a" → folder/a.nbt should exist.
            tmp.resolve("set/a.nbt").exists() shouldBe true
        }
    }

    test("mutation outside cell bounds -> not saved") {
        withTempRoot("managed-saver-outside") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                // 5 blocks beyond the +X face of the AABB — outside the cell volume.
                level.setBlock(abs.offset(bounds.x + 5, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r
            }

            results.single { it.specId == "a" }.saved shouldBe false
        }
    }

    test("snapshot refresh: second saveFolder after a save returns saved=false") {
        withTempRoot("managed-saver-refresh") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val (first, second) = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")

                level.setBlock(abs.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                val r1 = ManagedDimLifecycle.saveFolder(this, "set")
                val r2 = ManagedDimLifecycle.saveFolder(this, "set")
                ManagedWorld.clear(this)
                r1 to r2
            }

            first.single { it.specId == "a" }.saved shouldBe true
            second.single { it.specId == "a" }.saved shouldBe false
        }
    }
})
