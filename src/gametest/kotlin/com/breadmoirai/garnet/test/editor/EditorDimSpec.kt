package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.editor.world.EditorDimLifecycle
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.data.EditorNewSpec
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorWorld
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
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
class EditorDimSpec : GarnetTestSpec({

    test("load places cells in the managed dim and registers a session") {
        withTempRoot("project-load") { tmp ->
            val folder = tmp.resolve("set-a").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")

            val report = onServer {
                EditorDimRegistry.of(this).projectLevel()
                    .shouldNotBeNull() // managed dim must be registered (data-pack JSON)
                val player = makeMockServerPlayer(this)
                val reports = EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                reports.single { it.subpath == "set-a" }
            }

            report.loaded.shouldContainAll(listOf("a", "b"))

            onServer {
                val world = EditorWorld.get(this).shouldNotBeNull()
                val perFolder = world.perFolder["set-a"].shouldNotBeNull()
                perFolder.size shouldBe 2
                val cellA = perFolder.getValue("a").cell
                val cellB = perFolder.getValue("b").cell
                (cellA.origin != cellB.origin) shouldBe true
                EditorWorld.clear(this)
            }
        }
    }

    test("saveNow writes only specs whose cell volume changed") {
        withTempRoot("project-save") { tmp ->
            val folder = tmp.resolve("set-b").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")

            val results = onServer {
                makeMockServerPlayer(this)
                val root = EditorRoot(tmp)
                EditorDimLifecycle.placeAll(this, root)

                // The gametest world persists across runs, so the cell volume may contain
                // leftover blocks from a prior test run. Clear both cells to air and
                // re-place the folder so the snapshot baseline is deterministic.
                val world = EditorWorld.get(this).shouldNotBeNull()
                val absA = world.absoluteCellOrigin(this, "set-b", "a").shouldNotBeNull()
                val absB = world.absoluteCellOrigin(this, "set-b", "b").shouldNotBeNull()
                val level = EditorDimRegistry.of(this).projectLevel().shouldNotBeNull()
                val air = Blocks.AIR.defaultBlockState()
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absA, absA.offset(2, 2, 2))) {
                    level.setBlock(pos, air, 2)
                }
                for (pos in net.minecraft.core.BlockPos.betweenClosed(absB, absB.offset(1, 1, 1))) {
                    level.setBlock(pos, air, 2)
                }
                EditorDimLifecycle.placeFolder(this, root, "set-b")

                // Now mutate spec a's cell volume by setting a block inside its bounds (offset
                // by (1,1,1) so we're not on the placement floor).
                level.setBlock(absA.offset(1, 1, 1), Blocks.GOLD_BLOCK.defaultBlockState(), 2)

                val r = EditorDimLifecycle.saveFolder(this, "set-b")
                EditorWorld.clear(this)
                r
            }

            val byId = results.associateBy { it.specId }
            byId.getValue("a").saved shouldBe true
            byId.getValue("b").saved shouldBe false
        }
    }

    test("EditorNewSpec.create writes a stub spec.kts to the leaf folder") {
        withTempRoot("project-new") { tmp ->
            val folder = tmp.resolve("empty-set").also { it.createDirectories() }

            onServer {
                makeMockServerPlayer(this)
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                EditorWorld.clear(this)
            }

            EditorNewSpec.create(folder, "fresh")
            folder.resolve("fresh.spec.kts").exists() shouldBe true
        }
    }

    test("placeFolder for unknown subpath throws") {
        withTempRoot("project-place-unknown") { tmp ->
            onServer {
                makeMockServerPlayer(this)
                try {
                    EditorDimLifecycle.placeFolder(this, EditorRoot(tmp), "does/not/exist")
                    error("expected throw")
                } catch (e: IllegalStateException) {
                    e.message?.let { it.contains("subpath outside root") } shouldBe true
                }
                EditorWorld.clear(this)
            }
        }
    }

    test("placeFolder on empty leaf folder reports zero loaded specs and no errors") {
        withTempRoot("project-place-empty") { tmp ->
            val folder = tmp.resolve("empty").also { it.createDirectories() }
            val report = onServer {
                makeMockServerPlayer(this)
                EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                val r = EditorDimLifecycle.placeFolder(this, EditorRoot(tmp), "empty")
                EditorWorld.clear(this)
                r
            }
            report.loaded.isEmpty() shouldBe true
            report.errors.isEmpty() shouldBe true
            report.parseErrors.isEmpty() shouldBe true
        }
    }

    test("placeAll handles nested leaf folders with distinct region origins") {
        withTempRoot("project-place-nested") { tmp ->
            tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
            tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

            val (origins, reports) = onServer {
                makeMockServerPlayer(this)
                val rs = EditorDimLifecycle.placeAll(this, EditorRoot(tmp))
                val reg = EditorDimRegistry.of(this)
                val map = mapOf(
                    "alpha" to reg.regionOriginOf("alpha"),
                    "beta" to reg.regionOriginOf("beta"),
                )
                EditorWorld.clear(this)
                map to rs
            }
            reports.map { it.subpath }.toSet() shouldBe setOf("alpha", "beta")
            origins["alpha"].shouldNotBeNull()
            origins["beta"].shouldNotBeNull()
            (origins["alpha"] == origins["beta"]) shouldBe false
        }
    }

    test("re-place after adding a new spec keeps region origin and includes new spec") {
        withTempRoot("project-replace") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")

            val (firstOrigin, secondOrigin, secondReport) = onServer {
                makeMockServerPlayer(this)
                val root = EditorRoot(tmp)
                EditorDimLifecycle.placeFolder(this, root, "set")
                val reg = EditorDimRegistry.of(this)
                val o1 = reg.regionOriginOf("set")

                writeStub(folder, "b")
                val r2 = EditorDimLifecycle.placeFolder(this, root, "set")
                val o2 = reg.regionOriginOf("set")
                EditorWorld.clear(this)
                Triple(o1, o2, r2)
            }
            firstOrigin shouldBe secondOrigin
            secondReport.loaded.toSet() shouldBe setOf("a", "b")
        }
    }

    test("parse-error file is reported but does not block other specs in the folder") {
        withTempRoot("project-place-parse-err") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            folder.resolve("bad.spec.kts").writeText("this is not valid kotlin <<<<")

            val report = onServer {
                makeMockServerPlayer(this)
                val r = EditorDimLifecycle.placeFolder(this, EditorRoot(tmp), "set")
                EditorWorld.clear(this)
                r
            }
            report.loaded shouldContain "a"
            report.parseErrors.map { it.filename } shouldContain "bad.spec.kts"
        }
    }

    test("saveAll aggregates dirty cells across folders") {
        withTempRoot("project-saveall") { tmp ->
            tmp.resolve("set-a").createDirectories().also { writeStub(it, "a") }
            tmp.resolve("set-b").createDirectories().also { writeStub(it, "b") }

            val results = onServer {
                makeMockServerPlayer(this)
                val root = EditorRoot(tmp)
                EditorDimLifecycle.placeAll(this, root)
                val world = EditorWorld.get(this).shouldNotBeNull()
                val level = EditorDimRegistry.of(this).projectLevel()
                for (sub in listOf("set-a", "set-b")) {
                    val id = world.perFolder[sub]!!.keys.single()
                    val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                    val bounds = world.perFolder[sub]!![id]!!.spec.bounds
                    clearCellVolume(level, abs, bounds)
                }
                EditorDimLifecycle.placeFolder(this, root, "set-a")
                EditorDimLifecycle.placeFolder(this, root, "set-b")
                // Mutate one block in each cell.
                for (sub in listOf("set-a", "set-b")) {
                    val id = world.perFolder[sub]!!.keys.single()
                    val abs = world.absoluteCellOrigin(this, sub, id).shouldNotBeNull()
                    level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                }
                val r = EditorDimLifecycle.saveAll(this)
                EditorWorld.clear(this)
                r
            }
            results.size shouldBe 2
            results.all { it.saved } shouldBe true
        }
    }

    test("region origins are stable for prior subpaths when a new region is added") {
        withTempRoot("project-region-stable") { tmp ->
            tmp.resolve("alpha").createDirectories().also { writeStub(it, "x") }
            tmp.resolve("beta").createDirectories().also { writeStub(it, "y") }

            val (firstAlpha, firstBeta, secondAlpha, secondBeta) = onServer {
                makeMockServerPlayer(this)
                val root = EditorRoot(tmp)
                EditorDimLifecycle.placeFolder(this, root, "alpha")
                EditorDimLifecycle.placeFolder(this, root, "beta")
                val reg = EditorDimRegistry.of(this)
                val a1 = reg.regionOriginOf("alpha")
                val b1 = reg.regionOriginOf("beta")
                // Add a third folder.
                tmp.resolve("gamma").createDirectories().also { writeStub(it, "z") }
                EditorDimLifecycle.placeFolder(this, root, "gamma")
                val a2 = reg.regionOriginOf("alpha")
                val b2 = reg.regionOriginOf("beta")
                EditorWorld.clear(this)
                arrayOf(a1, b1, a2, b2)
            }
            firstAlpha shouldBe secondAlpha
            firstBeta shouldBe secondBeta
        }
    }
})
