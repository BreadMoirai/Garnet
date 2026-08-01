package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.data.EditorNewStructure
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.network.EditorNetworking
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.world.StructureAutoSave
import com.breadmoirai.garnet.editor.world.StructureCommit
import com.breadmoirai.garnet.editor.world.StructureEditWatcher
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import com.breadmoirai.garnet.mc.onServer
import com.breadmoirai.garnet.structure.StructurePersistence
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.level.block.Blocks
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.readBytes

/**
 * Dirty-state bookkeeping only — the commit itself is covered by the network-level tests once
 * [com.breadmoirai.garnet.editor.world.StructureCommit] exists.
 *
 * These drive [StructureEditWatcher.onBlockChanged] directly rather than calling `level.setBlock`:
 * the setBlock mixin is unreliable under the gametest harness, and what needs testing here is the
 * bookkeeping, not the mixin plumbing.
 */
class StructureAutoSaveSpec : GarnetTestSpec({

    test("an edit marks the structure dirty and the dirty box grows to enclose every edit") {
        onServer {
            val autoSave = StructureAutoSave.of(this)
            autoSave.clear("dirtybox.nbt")

            autoSave.isDirty("dirtybox.nbt") shouldBe false

            autoSave.onEdit("dirtybox.nbt", BlockPos(10, 64, 10), tick = 100L)
            autoSave.isDirty("dirtybox.nbt") shouldBe true
            autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull().size shouldBe Vec3i(1, 1, 1)

            autoSave.onEdit("dirtybox.nbt", BlockPos(12, 66, 10), tick = 101L)
            val box = autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull()
            box.origin shouldBe BlockPos(10, 64, 10)
            box.size shouldBe Vec3i(3, 3, 1)

            // An edit at lower coordinates must move the origin, not just grow the size.
            autoSave.onEdit("dirtybox.nbt", BlockPos(9, 63, 8), tick = 102L)
            val grown = autoSave.dirtyBox("dirtybox.nbt").shouldNotBeNull()
            grown.origin shouldBe BlockPos(9, 63, 8)
            grown.size shouldBe Vec3i(4, 4, 3)

            autoSave.clear("dirtybox.nbt")
        }
    }

    test("dueForCommit fires only after the debounce has elapsed") {
        onServer {
            val prev = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 100_000
            try {
                val autoSave = StructureAutoSave.of(this)
                autoSave.clear("debounce.nbt")
                autoSave.onEdit("debounce.nbt", BlockPos(0, 64, 0), tick = 1_000L)

                autoSave.dueForCommit("debounce.nbt", tick = 1_010L) shouldBe false
                autoSave.dueForCommit("debounce.nbt", tick = 1_020L) shouldBe true

                // A fresh edit restarts the quiet period.
                autoSave.onEdit("debounce.nbt", BlockPos(0, 64, 1), tick = 1_015L)
                autoSave.dueForCommit("debounce.nbt", tick = 1_020L) shouldBe false
                autoSave.dueForCommit("debounce.nbt", tick = 1_035L) shouldBe true

                autoSave.clear("debounce.nbt")
            } finally {
                SharedSettings.autoSaveDebounceTicks = prev
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
            }
        }
    }

    test("the max-dirty cap fires during continuous editing even though the debounce never elapses") {
        onServer {
            val prev = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 50
            try {
                val autoSave = StructureAutoSave.of(this)
                autoSave.clear("cap.nbt")
                // An edit every 5 ticks: the debounce alone would never elapse.
                var tick = 2_000L
                repeat(11) {
                    autoSave.onEdit("cap.nbt", BlockPos(0, 64, 0), tick = tick)
                    tick += 5
                }
                // 50 ticks after the FIRST edit, the cap is due despite the last edit being recent.
                autoSave.dueForCommit("cap.nbt", tick = 2_050L) shouldBe true
                autoSave.clear("cap.nbt")
            } finally {
                SharedSettings.autoSaveDebounceTicks = prev
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
            }
        }
    }

    test("clear forgets the structure entirely") {
        onServer {
            val autoSave = StructureAutoSave.of(this)
            autoSave.onEdit("clear.nbt", BlockPos(0, 64, 0), tick = 1L)
            autoSave.dirtySubpaths().contains("clear.nbt") shouldBe true
            autoSave.clear("clear.nbt")
            autoSave.isDirty("clear.nbt") shouldBe false
            autoSave.dirtyBox("clear.nbt") shouldBe null
            autoSave.dueForCommit("clear.nbt", tick = 100_000L) shouldBe false
            autoSave.dirtySubpaths().contains("clear.nbt") shouldBe false
        }
    }

    test("structureSubpathAt attributes a position to the placed structure whose region holds it") {
        onServer {
            val registry = EditorDimRegistry.of(this)
            val origin = registry.getOrAssignStructureRegion("attributed.nbt")
            val width = SharedSettings.structureRegionChunks * 16

            registry.structureSubpathAt(origin) shouldBe "attributed.nbt"
            registry.structureSubpathAt(origin.offset(width - 1, 100, width - 1)) shouldBe "attributed.nbt"
            // Just outside the region's X extent — no structure owns it.
            registry.structureSubpathAt(origin.offset(width, 0, 0)) shouldBe null
            // Far away in Z, outside the structure lane entirely.
            registry.structureSubpathAt(BlockPos(origin.x, 64, 0)) shouldBe null
        }
    }

    test("the edit watcher records an edit inside a placed region and ignores one outside") {
        onServer {
            val registry = EditorDimRegistry.of(this)
            val origin = registry.getOrAssignStructureRegion("watched.nbt")
            val autoSave = StructureAutoSave.of(this)
            autoSave.clear("watched.nbt")

            StructureEditWatcher.onBlockChanged(overworld(), origin.offset(3, 5, 3))
            autoSave.isDirty("watched.nbt") shouldBe true

            autoSave.clear("watched.nbt")
            StructureEditWatcher.onBlockChanged(overworld(), BlockPos(origin.x, 64, 0))
            autoSave.isDirty("watched.nbt") shouldBe false
        }
    }

    // Fix round 1: StructureEditWatcher.onBlockChanged now rejects most positions with a cheap
    // Z-band check (EditorDimRegistry.isInStructureLaneZ) before ever touching the per-server
    // registry. This pins the true region boundary — on every edge, in both dimensions the cheap
    // check and the fine-grained check jointly guard — so that optimization cannot introduce a
    // false negative (a real edit silently dropped) or a false positive (an edit outside the
    // region wrongly attributed).
    test("the edit watcher's cheap lane rejection matches the true region boundary exactly") {
        onServer {
            val registry = EditorDimRegistry.of(this)
            val origin = registry.getOrAssignStructureRegion("laneboundary.nbt")
            val width = SharedSettings.structureRegionChunks * 16
            val autoSave = StructureAutoSave.of(this)
            val lvl = overworld()

            fun editedAt(pos: BlockPos): Boolean {
                autoSave.clear("laneboundary.nbt")
                StructureEditWatcher.onBlockChanged(lvl, pos)
                return autoSave.isDirty("laneboundary.nbt")
            }

            // Z lower boundary of the shared structure lane: in-band, and with x inside the
            // region, the edit is attributed.
            editedAt(BlockPos(origin.x, 500, EditorDimRegistry.STRUCTURE_LANE_Z)) shouldBe true
            // One block below the lane: the cheap early-out must reject it exactly as the old
            // per-region check did.
            editedAt(BlockPos(origin.x, 500, EditorDimRegistry.STRUCTURE_LANE_Z - 1)) shouldBe false
            // Z upper boundary: the last in-band row still attributes.
            editedAt(BlockPos(origin.x, 500, EditorDimRegistry.STRUCTURE_LANE_Z + width - 1)) shouldBe true
            // One block past the band (every region shares this same width): rejected.
            editedAt(BlockPos(origin.x, 500, EditorDimRegistry.STRUCTURE_LANE_Z + width)) shouldBe false

            // X boundaries of this specific region, at a Z that is safely inside the lane.
            editedAt(BlockPos(origin.x, 500, origin.z)) shouldBe true
            editedAt(BlockPos(origin.x + width - 1, 500, origin.z)) shouldBe true
            editedAt(BlockPos(origin.x - 1, 500, origin.z)) shouldBe false
            editedAt(BlockPos(origin.x + width, 500, origin.z)) shouldBe false

            autoSave.clear("laneboundary.nbt")
        }
    }

    test("a commit writes the .nbt, records a revision, and clears the dirty state") {
        withTempRoot("autosave-commit") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "widget")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    drainPayloads(player)

                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
                    drainPayloads(player)

                    // Placing seeds the pre-edit baseline, so rollback is possible from the start.
                    val file = tmp.resolve("widget.nbt")
                    LocalHistoryStore.revisions(file) shouldHaveSize 1
                    LocalHistoryStore.revisions(file).single().reason shouldBe LocalHistoryStore.REASON_PLACED

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("widget.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    // The gametest world's terrain isn't part of the structure until cleared.
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )

                    val edited = region.offset(5, 0, 5)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    // Drive the watcher directly: the setBlock mixin is flaky under this harness.
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("widget.nbt") shouldBe true

                    val outcome = StructureCommit.commit(this, "widget.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    outcome.payload.subpath shouldBe "widget.nbt"
                    outcome.payload.sizeX shouldBe 1
                    outcome.payload.blockCount shouldBe 1

                    StructureAutoSave.of(this).isDirty("widget.nbt") shouldBe false
                    LocalHistoryStore.revisions(file) shouldHaveSize 2
                    LocalHistoryStore.revisions(file).last().reason shouldBe LocalHistoryStore.REASON_AUTOSAVE
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("committing an unchanged structure writes nothing and records no revision") {
        withTempRoot("autosave-noop") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-noop-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "still")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("still.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("still.nbt")
                    val before = file.readBytes().toList()
                    val revisionsBefore = LocalHistoryStore.revisions(file).size

                    // No edits at all -> the capture matches the committed file exactly.
                    StructureCommit.commit(this, "still.nbt", LocalHistoryStore.REASON_AUTOSAVE) shouldBe
                        StructureCommit.CommitOutcome.NoChange

                    file.readBytes().toList() shouldBe before
                    LocalHistoryStore.revisions(file) shouldHaveSize revisionsBefore
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("tick commits a due structure and skips one that is not due") {
        withTempRoot("autosave-tick") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevDebounce = SharedSettings.autoSaveDebounceTicks
            val prevCap = SharedSettings.autoSaveMaxDirtyTicks
            SharedSettings.structureRegionChunks = 1
            SharedSettings.autoSaveDebounceTicks = 1_000_000  // never elapses during this test
            SharedSettings.autoSaveMaxDirtyTicks = 1_000_000
            val histDir = kotlin.io.path.createTempDirectory("autosave-tick-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "ticker")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("ticker.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("ticker.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(4, 0, 4)
                    lvl.setBlock(edited, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    // Debounce is effectively infinite -> tick must NOT commit.
                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("ticker.nbt") shouldBe true

                    // Make it due, then tick again.
                    SharedSettings.autoSaveDebounceTicks = 0
                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("ticker.nbt") shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.autoSaveDebounceTicks = prevDebounce
                SharedSettings.autoSaveMaxDirtyTicks = prevCap
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("autoSaveEnabled=false stops the tick pass but not an explicit commit") {
        withTempRoot("autosave-off") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevEnabled = SharedSettings.autoSaveEnabled
            val prevDebounce = SharedSettings.autoSaveDebounceTicks
            SharedSettings.structureRegionChunks = 1
            SharedSettings.autoSaveDebounceTicks = 0
            SharedSettings.autoSaveEnabled = false
            val histDir = kotlin.io.path.createTempDirectory("autosave-off-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "manual")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("manual.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("manual.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(2, 0, 2)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    StructureCommit.tick(this)
                    StructureAutoSave.of(this).isDirty("manual.nbt") shouldBe true

                    StructureCommit.commit(this, "manual.nbt", LocalHistoryStore.REASON_MANUAL)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    StructureAutoSave.of(this).isDirty("manual.nbt") shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.autoSaveEnabled = prevEnabled
                SharedSettings.autoSaveDebounceTicks = prevDebounce
                histDir.toFile().deleteRecursively()
            }
        }
    }

    // --- Fix round 1: failure-path coverage (Critical/Important findings) -----------------------

    test("a commit whose .nbt write fails does not clear dirty state, does not bank an orphan revision, and backs off instead of retrying every tick") {
        withTempRoot("autosave-writefail") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevDebounce = SharedSettings.autoSaveDebounceTicks
            SharedSettings.structureRegionChunks = 1
            SharedSettings.autoSaveDebounceTicks = 0
            val histDir = kotlin.io.path.createTempDirectory("autosave-writefail-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "broken")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("broken.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("broken.nbt")
                    LocalHistoryStore.revisions(file) shouldHaveSize 1 // the placed baseline

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("broken.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("broken.nbt") shouldBe true

                    val editTick = lvl.gameTime
                    var attempts = 0
                    val failingWrite: (CompoundTag, Path) -> Unit = { _, _ ->
                        attempts++
                        throw IOException("simulated write failure")
                    }

                    // First attempt: the .nbt write fails. Dirty state must survive, and the
                    // revision speculatively written before the failing write must be rolled back
                    // -- not left behind as an orphan that never corresponds to the live .nbt.
                    StructureCommit.tick(this, now = editTick, writeNbt = failingWrite)
                    attempts shouldBe 1
                    StructureAutoSave.of(this).isDirty("broken.nbt") shouldBe true
                    LocalHistoryStore.revisions(file) shouldHaveSize 1

                    // One tick later, still well inside the failure backoff window: tick() must
                    // skip the retry entirely rather than hammering the write every tick.
                    StructureCommit.tick(this, now = editTick + 1, writeNbt = failingWrite)
                    attempts shouldBe 1
                    LocalHistoryStore.revisions(file) shouldHaveSize 1

                    // Comfortably past the backoff window: tick() retries, and (still failing)
                    // still leaves no orphan revision behind.
                    StructureCommit.tick(this, now = editTick + 150, writeNbt = failingWrite)
                    attempts shouldBe 2
                    StructureAutoSave.of(this).isDirty("broken.nbt") shouldBe true
                    LocalHistoryStore.revisions(file) shouldHaveSize 1

                    // Once the write actually succeeds (real writer, backoff long since irrelevant
                    // since this call is due and past any backoff window), the structure recovers
                    // normally: dirty clears and a genuine revision is banked.
                    StructureCommit.tick(this, now = editTick + 300)
                    StructureAutoSave.of(this).isDirty("broken.nbt") shouldBe false
                    LocalHistoryStore.revisions(file) shouldHaveSize 2
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.autoSaveDebounceTicks = prevDebounce
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("a commit whose history write fails leaves the .nbt untouched") {
        withTempRoot("autosave-histfail") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-histfail-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "brokenhist")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("brokenhist.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("brokenhist.nbt")
                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("brokenhist.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val edited = region.offset(1, 0, 1)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, edited)

                    // Occupy index.json's path with a directory so the next writeRevision call
                    // fails deterministically and portably (same trick LocalHistoryStoreSpec uses
                    // for "writeRevision returns null ... when the index write fails").
                    val dir = LocalHistoryStore.dirFor(file)
                    dir.resolve("index.json").deleteIfExists()
                    dir.resolve("index.json").createDirectory()

                    val before = file.readBytes().toList()

                    val outcome = StructureCommit.commit(this, "brokenhist.nbt", LocalHistoryStore.REASON_AUTOSAVE)

                    outcome.shouldBeInstanceOf<StructureCommit.CommitOutcome.Failed>()
                    // The .nbt write must never have been attempted: the history write that was
                    // supposed to back up this content failed first.
                    file.readBytes().toList() shouldBe before
                    StructureAutoSave.of(this).isDirty("brokenhist.nbt") shouldBe true

                    // This test deliberately leaves "brokenhist.nbt" dirty (that's the point being
                    // tested). StructureAutoSave and StructureCommit's backoff map are both
                    // per-server, and the gametest server keeps ticking after this block returns --
                    // production END_SERVER_TICK would otherwise keep retrying this subpath forever
                    // against whatever temp root a LATER test happens to have live at the time.
                    // Forget both so no state leaks past this test.
                    StructureAutoSave.of(this).clear("brokenhist.nbt")
                    StructureCommit.clearBackoff(this, "brokenhist.nbt")
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("a commit's capture is bounded to union(placedBox, dirtyBox), not the whole region") {
        withTempRoot("autosave-bounded") { tmp ->
            val prevHistDir = SharedSettings.localHistoryDir
            // Deliberately leave SharedSettings.structureRegionChunks at its real default (a
            // 144-wide region): if StructureCommit's capture were ever swapped back to the
            // region-wide StructurePersistence.captureAutoFit, this test would see the untracked
            // block below and fail. Every other test in this spec forces structureRegionChunks = 1,
            // which is too small to distinguish bounded from region-wide capture.
            val histDir = kotlin.io.path.createTempDirectory("autosave-bounded-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "bounded")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("bounded.nbt"))
                    drainPayloads(player)

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("bounded.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16

                    // The tracked edit: watcher-marked, near one corner of the region. The dirty
                    // box this produces is exactly this one block -- nothing else needs clearing.
                    val tracked = region.offset(2, 0, 2)
                    lvl.setBlock(tracked, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, tracked)

                    // A real, non-air block far across the SAME region, well inside its bounds, but
                    // never reported to the watcher -- standing in for an edit the setBlock mixin
                    // missed. A region-wide capture would include it; captureAutoFitIn must not,
                    // since the scan volume is union(placedBox, dirtyBox), not the whole region.
                    val untracked = region.offset(width - 3, 0, width - 3)
                    lvl.setBlock(untracked, Blocks.IRON_BLOCK.defaultBlockState(), 2)

                    val outcome = StructureCommit.commit(this, "bounded.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    outcome.payload.blockCount shouldBe 1
                    outcome.payload.sizeX shouldBe 1
                    outcome.payload.sizeY shouldBe 1
                    outcome.payload.sizeZ shouldBe 1
                }
            } finally {
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    // --- Fix round 2: pruning must never be coupled to a failed attempt (Item A) ------------------

    test("a failed commit at the local-history cap causes no net loss of genuine history") {
        withTempRoot("autosave-cap") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevMaxRev = SharedSettings.localHistoryMaxRevisions
            SharedSettings.structureRegionChunks = 1
            SharedSettings.localHistoryMaxRevisions = 3
            val histDir = kotlin.io.path.createTempDirectory("autosave-cap-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "capped")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorNetworking.handlePlaceStructure(this, player, PlaceStructureC2S("capped.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("capped.nbt")
                    LocalHistoryStore.revisions(file) shouldHaveSize 1 // the placed baseline

                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("capped.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val pos = region.offset(1, 0, 1)

                    // Fill history to the cap (3): the placed baseline plus two successful autosave
                    // commits, each changing the same block to a different type so the diff check
                    // sees real content changes.
                    lvl.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, pos)
                    StructureCommit.commit(this, "capped.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    LocalHistoryStore.revisions(file) shouldHaveSize 2

                    lvl.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, pos)
                    StructureCommit.commit(this, "capped.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    val atCap = LocalHistoryStore.revisions(file)
                    atCap shouldHaveSize 3

                    // One more edit, but every subsequent .nbt write will fail. Repeated retries AT
                    // the cap must cause NO net change to the already-recorded genuine history --
                    // not the count, not the identity of any entry.
                    lvl.setBlock(pos, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, pos)

                    val failingWrite: (CompoundTag, Path) -> Unit = { _, _ ->
                        throw IOException("simulated write failure")
                    }
                    var now = lvl.gameTime
                    repeat(3) {
                        StructureCommit.commit(this, "capped.nbt", LocalHistoryStore.REASON_AUTOSAVE, now, failingWrite)
                        LocalHistoryStore.revisions(file) shouldBe atCap
                        now += 150
                    }
                    StructureAutoSave.of(this).isDirty("capped.nbt") shouldBe true

                    // A genuine, successful write still prunes normally: the oldest survivor (the
                    // "placed" baseline) is finally dropped once a real 4th revision needs to fit
                    // in the cap of 3. This confirms the fix didn't just disable pruning outright --
                    // it only decoupled it from failed attempts.
                    StructureCommit.commit(this, "capped.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<StructureCommit.CommitOutcome.Committed>()
                    val afterSuccess = LocalHistoryStore.revisions(file)
                    afterSuccess shouldHaveSize 3
                    afterSuccess.none { it.reason == LocalHistoryStore.REASON_PLACED } shouldBe true
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.localHistoryMaxRevisions = prevMaxRev
                histDir.toFile().deleteRecursively()
            }
        }
    }
})
