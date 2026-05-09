package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedNewSpec
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

/**
 * Server-side gametests for the managed-worlds load/save/new-spec flow. Since T27 the canvas
 * is `server.overworld()` (no custom datapack dimension), so these tests run unmodified under
 * Fabric's `GameTestServer` harness.
 *
 * Each test creates a fresh temp root + a synthetic mock `ServerPlayer` so the lifecycle's
 * teleport/session-tracking code paths execute end-to-end without sharing player state across
 * tests.
 */
class ManagedDimSpec : RedstoneTestSpec({

    test("load places cells in the managed dim and registers a session") {
        withTempRoot("managed-load") { tmp ->
            val folder = tmp.resolve("set-a").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")

            val report = onServer {
                ManagedDimRegistry.of(this).managedLevel()
                    .shouldNotBeNull() // managed dim must be registered (data-pack JSON)
                val player = makeMockServerPlayer(this)
                val reports = ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                reports.single { it.subpath == "set-a" }
            }

            report.loaded.shouldContainAll(listOf("a", "b"))

            onServer {
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val perFolder = world.perFolder["set-a"].shouldNotBeNull()
                perFolder.size shouldBe 2
                val cellA = perFolder.getValue("a").cell
                val cellB = perFolder.getValue("b").cell
                (cellA.origin != cellB.origin) shouldBe true
                ManagedWorld.clear(this)
            }
        }
    }

    test("saveNow writes only specs whose cell volume changed") {
        withTempRoot("managed-save") { tmp ->
            val folder = tmp.resolve("set-b").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)

                // The gametest world persists across runs, so the cell volume may contain
                // leftover blocks from a prior test run. Clear both cells to air and
                // re-place the folder so the snapshot baseline is deterministic.
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val absA = world.absoluteCellOrigin(this, "set-b", "a").shouldNotBeNull()
                val absB = world.absoluteCellOrigin(this, "set-b", "b").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel().shouldNotBeNull()
                val air = Blocks.AIR.defaultBlockState()
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absA, absA.offset(2, 2, 2))) {
                    level.setBlock(pos, air, 2)
                }
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absB, absB.offset(1, 1, 1))) {
                    level.setBlock(pos, air, 2)
                }
                ManagedDimLifecycle.placeFolder(this, root, "set-b")

                // Now mutate spec a's cell volume by setting a block inside its bounds (offset
                // by (1,1,1) so we're not on the placement floor).
                level.setBlock(absA.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

                val r = ManagedDimLifecycle.saveFolder(this, "set-b")
                ManagedWorld.clear(this)
                r
            }

            val byId = results.associateBy { it.specId }
            byId.getValue("a").saved shouldBe true
            byId.getValue("b").saved shouldBe false
        }
    }

    test("ManagedNewSpec.create writes a stub spec.kts to the leaf folder") {
        withTempRoot("managed-new") { tmp ->
            val folder = tmp.resolve("empty-set").also { it.createDirectories() }

            onServer {
                makeMockServerPlayer(this)
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                ManagedWorld.clear(this)
            }

            ManagedNewSpec.create(folder, "fresh")
            folder.resolve("fresh.spec.kts").exists() shouldBe true
        }
    }
})
