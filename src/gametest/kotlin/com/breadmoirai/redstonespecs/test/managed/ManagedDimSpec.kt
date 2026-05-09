package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedNewSpec
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.world.level.block.Blocks
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText

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

    test("placeFolder for unknown subpath throws") {
        withTempRoot("managed-place-unknown") { tmp ->
            onServer {
                makeMockServerPlayer(this)
                try {
                    ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "does/not/exist")
                    error("expected throw")
                } catch (e: IllegalStateException) {
                    e.message?.let { it.contains("subpath outside root") } shouldBe true
                }
                ManagedWorld.clear(this)
            }
        }
    }

    test("placeFolder on empty leaf folder reports zero loaded specs and no errors") {
        withTempRoot("managed-place-empty") { tmp ->
            val folder = tmp.resolve("empty").also { it.createDirectories() }
            val report = onServer {
                makeMockServerPlayer(this)
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                val r = ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "empty")
                ManagedWorld.clear(this)
                r
            }
            report.loaded.isEmpty() shouldBe true
            report.errors.isEmpty() shouldBe true
            report.parseErrors.isEmpty() shouldBe true
        }
    }

    test("placeAll handles nested leaf folders with distinct region origins") {
        withTempRoot("managed-place-nested") { tmp ->
            tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
            tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

            val (origins, reports) = onServer {
                makeMockServerPlayer(this)
                val rs = ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                val reg = ManagedDimRegistry.of(this)
                val map = mapOf(
                    "alpha" to reg.regionOriginOf("alpha"),
                    "beta" to reg.regionOriginOf("beta"),
                )
                ManagedWorld.clear(this)
                map to rs
            }
            reports.map { it.subpath }.toSet() shouldBe setOf("alpha", "beta")
            origins["alpha"].shouldNotBeNull()
            origins["beta"].shouldNotBeNull()
            (origins["alpha"] == origins["beta"]) shouldBe false
        }
    }

    test("re-place after adding a new spec keeps region origin and includes new spec") {
        withTempRoot("managed-replace") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val (firstOrigin, secondOrigin, secondReport) = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeFolder(this, root, "set")
                val reg = ManagedDimRegistry.of(this)
                val o1 = reg.regionOriginOf("set")

                writeStub(folder, "b")
                val r2 = ManagedDimLifecycle.placeFolder(this, root, "set")
                val o2 = reg.regionOriginOf("set")
                ManagedWorld.clear(this)
                Triple(o1, o2, r2)
            }
            firstOrigin shouldBe secondOrigin
            secondReport.loaded.toSet() shouldBe setOf("a", "b")
        }
    }

    test("parse-error file is reported but does not block other specs in the folder") {
        withTempRoot("managed-place-parse-err") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            folder.resolve("bad.spec.kts").writeText("this is not valid kotlin <<<<")

            val report = onServer {
                makeMockServerPlayer(this)
                val r = ManagedDimLifecycle.placeFolder(this, ManagedRoot(tmp), "set")
                ManagedWorld.clear(this)
                r
            }
            report.loaded shouldContain "a"
            report.parseErrors.map { it.filename } shouldContain "bad.spec.kts"
        }
    }

    test("saveAll aggregates dirty cells across folders") {
        withTempRoot("managed-saveall") { tmp ->
            tmp.resolve("set-a").createDirectories().also { writeStub(it, "a") }
            tmp.resolve("set-b").createDirectories().also { writeStub(it, "b") }

            val results = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                for (sub in listOf("set-a", "set-b")) {
                    val id = world.perFolder[sub]!!.keys.single()
                    val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                    val bounds = world.perFolder[sub]!![id]!!.spec.bounds
                    clearCellVolume(level, abs, bounds)
                }
                ManagedDimLifecycle.placeFolder(this, root, "set-a")
                ManagedDimLifecycle.placeFolder(this, root, "set-b")
                // Mutate one block in each cell.
                for (sub in listOf("set-a", "set-b")) {
                    val id = world.perFolder[sub]!!.keys.single()
                    val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                    level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                }
                val r = ManagedDimLifecycle.saveAll(this)
                ManagedWorld.clear(this)
                r
            }
            results.size shouldBe 2
            results.all { it.saved } shouldBe true
        }
    }

    test("region origins are stable for prior subpaths when a new region is added") {
        withTempRoot("managed-region-stable") { tmp ->
            tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
            tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

            val (firstAlpha, firstBeta, secondAlpha, secondBeta) = onServer {
                makeMockServerPlayer(this)
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeFolder(this, root, "alpha")
                ManagedDimLifecycle.placeFolder(this, root, "beta")
                val reg = ManagedDimRegistry.of(this)
                val a1 = reg.regionOriginOf("alpha")
                val b1 = reg.regionOriginOf("beta")
                // Add a third folder.
                tmp.resolve("gamma").createDirectories().also { writeStub(it, "z") }
                ManagedDimLifecycle.placeFolder(this, root, "gamma")
                val a2 = reg.regionOriginOf("alpha")
                val b2 = reg.regionOriginOf("beta")
                ManagedWorld.clear(this)
                arrayOf(a1, b1, a2, b2)
            }
            firstAlpha shouldBe secondAlpha
            firstBeta shouldBe secondBeta
        }
    }
})
