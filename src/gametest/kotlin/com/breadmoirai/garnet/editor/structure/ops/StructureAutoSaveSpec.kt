package com.breadmoirai.garnet.editor.structure.ops

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.editor.explorer.ops.EditorNewStructure
import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import com.breadmoirai.garnet.editor.structure.network.EditorStructureHandlers
import com.breadmoirai.garnet.editor.structure.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.workspace.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.workspace.world.EditorServerContext
import com.breadmoirai.garnet.editor.structure.data.CommitOutcome
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import com.breadmoirai.garnet.core.async.onServer
import com.breadmoirai.garnet.editor.structure.data.structuresDiffer
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
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Dirty-state bookkeeping only — the commit itself is covered by the network-level tests once
 * [com.breadmoirai.garnet.editor.structure.ops.StructureCommit] exists.
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

                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("widget.nbt"))
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
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
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
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("still.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("still.nbt")
                    val before = file.readBytes().toList()
                    val revisionsBefore = LocalHistoryStore.revisions(file).size

                    // No edits at all -> the capture matches the committed file exactly.
                    StructureCommit.commit(this, "still.nbt", LocalHistoryStore.REASON_AUTOSAVE) shouldBe
                        CommitOutcome.NoChange

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
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("ticker.nbt"))
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
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("manual.nbt"))
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
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
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
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("broken.nbt"))
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
            var server: MinecraftServer? = null
            try {
                onServer {
                    server = this
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("brokenhist.nbt"))
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

                    outcome.shouldBeInstanceOf<CommitOutcome.Failed>()
                    // The .nbt write must never have been attempted: the history write that was
                    // supposed to back up this content failed first.
                    file.readBytes().toList() shouldBe before
                    StructureAutoSave.of(this).isDirty("brokenhist.nbt") shouldBe true
                }
            } finally {
                // This test deliberately leaves "brokenhist.nbt" dirty (that's the point being
                // tested). StructureAutoSave and StructureCommit's backoff map are both per-server,
                // and the gametest server keeps ticking after this block returns -- production
                // END_SERVER_TICK would otherwise keep retrying this subpath forever against
                // whatever temp root a LATER test happens to have live at the time. Forget both so
                // no state leaks past this test, in `finally` so it still runs if the body above
                // throws before reaching this point (same pattern as the fix-round-3 test below).
                server?.let {
                    StructureAutoSave.of(it).clear("brokenhist.nbt")
                    StructureCommit.clearBackoff(it, "brokenhist.nbt")
                }
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
            // 144-wide region): if StructureCommit's capture were ever widened to the whole region
            // again (the old StructurePersistence.captureAutoFit, since deleted), this test would
            // see the untracked block below and fail. Every other test in this spec forces
            // structureRegionChunks = 1,
            // which is too small to distinguish bounded from region-wide capture.
            val histDir = kotlin.io.path.createTempDirectory("autosave-bounded-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            val prevPlatformWidth = SharedSettings.newStructurePlatformWidth
            // This test proves the capture is BOUNDED to union(placedBox, dirtyBox) -- it needs a
            // known-empty starting structure so blockCount/sizeX/Y/Z reflect only the one tracked
            // edit, not the default platform's blocks too.
            SharedSettings.newStructurePlatformWidth = 0
            EditorNewStructure.create(tmp, "bounded")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("bounded.nbt"))
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
                    // Write straight through the chunk, bypassing Level.setBlock entirely.
                    // ServerLevelSetBlockMixin now hooks the 4-arg setBlock -- which the 3-arg form
                    // delegates to -- so BOTH Level.setBlock overloads reach
                    // StructureEditWatcher.onBlockChanged and neither can stage an untracked write
                    // any more. LevelChunk.setBlockState is below that mixin and is genuinely
                    // invisible to the watcher, which is exactly what this test needs: a real,
                    // non-air block the auto-save path never heard about.
                    // (The setBlock mixin is known to be flaky under the gametest harness; do NOT
                    // "simplify" this back to a Level.setBlock call, or this test will see the
                    // untracked block get watcher-marked and start failing with blockCount == 2.)
                    lvl.getChunk(untracked).setBlockState(untracked, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    // This test is only meaningful if that write actually landed -- a bypass that
                    // silently placed nothing would make the assertions below pass for the wrong
                    // reason. There must be a REAL non-air block here that the capture excludes.
                    lvl.getBlockState(untracked).`is`(Blocks.IRON_BLOCK) shouldBe true
                    StructureAutoSave.of(this).dirtyBox("bounded.nbt")!!.let { box ->
                        // ...and the watcher must genuinely not have heard about it: the dirty box
                        // still encloses only the tracked edit.
                        (untracked.x >= box.origin.x && untracked.x < box.origin.x + box.size.x) shouldBe false
                    }

                    val outcome = StructureCommit.commit(this, "bounded.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    outcome.payload.blockCount shouldBe 1
                    outcome.payload.sizeX shouldBe 1
                    outcome.payload.sizeY shouldBe 1
                    outcome.payload.sizeZ shouldBe 1
                }
            } finally {
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.newStructurePlatformWidth = prevPlatformWidth
                histDir.toFile().deleteRecursively()
            }
        }
    }

    // --- Fix round 3: NotApplicable must not discard recoverable, still-placed edits ---------------

    test("a still-placed structure whose file becomes unresolvable keeps its dirty flag, and commits once the file is restored") {
        withTempRoot("autosave-unresolvable") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-unresolvable-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "recoverable")
            val displaced = kotlin.io.path.createTempDirectory("autosave-unresolvable-displaced")
                .resolve("recoverable.nbt")
            var server: MinecraftServer? = null
            try {
                onServer {
                    server = this
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("recoverable.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("recoverable.nbt")
                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("recoverable.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )

                    val edited = region.offset(5, 0, 5)
                    lvl.setBlock(edited, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    // Drive the watcher directly: the setBlock mixin is flaky under this harness.
                    StructureEditWatcher.onBlockChanged(lvl, edited)
                    StructureAutoSave.of(this).isDirty("recoverable.nbt") shouldBe true

                    // Simulate the file becoming unresolvable while the structure is still placed --
                    // an external delete-then-restore, a git checkout, cloud sync, a root swap, etc.
                    // The world blocks (the actual unsaved edit) are untouched; only resolveSubpath
                    // now fails because the candidate file doesn't exist.
                    Files.move(file, displaced)

                    StructureCommit.commit(this, "recoverable.nbt", LocalHistoryStore.REASON_AUTOSAVE) shouldBe
                        CommitOutcome.NotApplicable

                    // The dirty flag MUST survive: the structure is still placed, its edited blocks
                    // are still live in the world, and nothing else will ever re-mark it dirty if the
                    // flag is discarded here. Losing it now means losing the edit permanently once the
                    // file becomes resolvable again.
                    StructureAutoSave.of(this).isDirty("recoverable.nbt") shouldBe true

                    // Restore the file (the root/file condition that was blocking the commit clears)
                    // and confirm the surviving dirty flag lets it actually commit.
                    Files.move(displaced, file)

                    val outcome = StructureCommit.commit(this, "recoverable.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    outcome.payload.blockCount shouldBe 1
                    StructureAutoSave.of(this).isDirty("recoverable.nbt") shouldBe false
                }
            } finally {
                // If the body threw between the two Files.move calls (or anywhere after the edit),
                // "recoverable.nbt" could be left placed + dirty on the shared gametest server, whose
                // root (tmp) is about to be deleted. Under this round's semantics a still-placed
                // dirty entry with an unresolvable root now SURVIVES (that's the point of the fix),
                // so without this it would stat the filesystem every backoff window for the rest of
                // the suite. Forget it explicitly, same as the round-2 tests do.
                server?.let {
                    StructureAutoSave.of(it).clear("recoverable.nbt")
                    StructureCommit.clearBackoff(it, "recoverable.nbt")
                }
                displaced.parent.toFile().deleteRecursively()
                SharedSettings.structureRegionChunks = prevChunks
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
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("capped.nbt"))
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
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    LocalHistoryStore.revisions(file) shouldHaveSize 2

                    lvl.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, pos)
                    StructureCommit.commit(this, "capped.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
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
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
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

    // --- Final review, Finding B3: history must be untouched when deliberately disabled ------------

    test("a successful commit with localHistoryEnabled = false leaves an existing history directory untouched") {
        withTempRoot("autosave-histdisabled") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            val prevHistEnabled = SharedSettings.localHistoryEnabled
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-histdisabled-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "frozen")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    // Placed WHILE history is still enabled, so the placed baseline + one genuine
                    // autosave revision exist beforehand -- this is what a "disable history to
                    // freeze the archive" user actually has: a pre-existing directory they want left
                    // alone, not an empty one.
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("frozen.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("frozen.nbt")
                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("frozen.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    // The gametest world persists between suite runs, and the default platform
                    // means the capture is no longer just the edited cell -- clear the region so a
                    // leftover block from a previous run can't make an edit look like a no-op (this
                    // test only cares about history-directory bookkeeping, not capture size).
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )
                    val firstEdit = region.offset(1, 0, 1)
                    lvl.setBlock(firstEdit, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, firstEdit)
                    StructureCommit.commit(this, "frozen.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()

                    val beforeDisabling = LocalHistoryStore.revisions(file)
                    beforeDisabling shouldHaveSize 2 // placed baseline + the autosave above
                    val dir = LocalHistoryStore.dirFor(file)
                    val blobsBefore = dir.toFile().listFiles()?.map { it.name }?.sorted().orEmpty()

                    // Now disable history and commit again -- SharedSettings.localHistoryDays
                    // defaults to 5, but even an age cutoff of 0 (or any cutoff) must not apply while
                    // history is deliberately disabled: disabling it means "hands off the archive",
                    // not "prune it on the next write."
                    SharedSettings.localHistoryEnabled = false
                    val secondEdit = region.offset(2, 0, 2)
                    lvl.setBlock(secondEdit, Blocks.IRON_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, secondEdit)
                    StructureCommit.commit(this, "frozen.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()

                    // writeRevision itself is a no-op when disabled, so the count is unchanged --
                    // but the real point of this test is that prune() was never called on the
                    // pre-existing directory either: every blob that existed before still exists.
                    LocalHistoryStore.revisions(file) shouldBe beforeDisabling
                    val blobsAfter = dir.toFile().listFiles()?.map { it.name }?.sorted().orEmpty()
                    blobsAfter shouldBe blobsBefore
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                SharedSettings.localHistoryEnabled = prevHistEnabled
                histDir.toFile().deleteRecursively()
            }
        }
    }

    // --- Final review, Finding F4: out-of-band .nbt content must not be destroyed with no recovery -

    test("repeated commits with no out-of-band edit never bank a spurious REASON_EXTERNAL revision") {
        // The out-of-band check is short-circuited by a per-subpath fingerprint of what the last
        // successful commit left on disk. This asserts the fast path is actually correct: several
        // ordinary commits in a row must produce ONLY autosave revisions.
        withTempRoot("autosave-nofingerprint-churn") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-churn-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "churn")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("churn.nbt"))
                    drainPayloads(player)

                    val file = tmp.resolve("churn.nbt")
                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("churn.nbt")!!
                    val lvl = overworld()
                    val width = SharedSettings.structureRegionChunks * 16
                    // The gametest world persists between suite runs. The default platform makes
                    // the capture a tall box that already spans all three loop positions on the
                    // first iteration, so a leftover gold block from a previous run at pos i=1/2
                    // would make that iteration's setBlock a no-op and its commit wrongly report
                    // NoChange. Clear the region so every iteration starts from a deterministic,
                    // known-empty world regardless of what an earlier run left behind.
                    StructurePersistence.clearBounds(
                        lvl, BlockPos(region.x, lvl.minY, region.z),
                        Vec3i(width, lvl.maxY - lvl.minY + 1, width),
                    )

                    repeat(3) { i ->
                        val pos = region.offset(1 + i, 0, 1)
                        lvl.setBlock(pos, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                        StructureEditWatcher.onBlockChanged(lvl, pos)
                        StructureCommit.commit(this, "churn.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                            .shouldBeInstanceOf<CommitOutcome.Committed>()
                    }

                    val revisions = LocalHistoryStore.revisions(file)
                    revisions.count { it.reason == LocalHistoryStore.REASON_EXTERNAL } shouldBe 0
                    revisions.first().reason shouldBe LocalHistoryStore.REASON_PLACED
                    revisions.drop(1).all { it.reason == LocalHistoryStore.REASON_AUTOSAVE } shouldBe true
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("an out-of-band edit AFTER a successful commit is still banked, despite the fingerprint fast path") {
        // The regression the fingerprint could plausibly introduce: once a commit has recorded what
        // it left on disk, a later external rewrite must still invalidate that record. The
        // pre-existing external-edit test below never commits before its out-of-band write, so it
        // exercises the no-fingerprint path instead of this one.
        withTempRoot("autosave-external-after-commit") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-extafter-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "extafter")
            EditorNewStructure.create(tmp, "extdonor")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)
                    val lvl = overworld()
                    val registry = EditorDimRegistry.of(this)

                    // Donor content: genuinely different, valid structure bytes.
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("extdonor.nbt"))
                    drainPayloads(player)
                    val donorRegion = registry.structureRegionOriginOf("extdonor.nbt")!!
                    val donorEdit = donorRegion.offset(1, 0, 1)
                    lvl.setBlock(donorEdit, Blocks.EMERALD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, donorEdit)
                    StructureCommit.commit(this, "extdonor.nbt", LocalHistoryStore.REASON_MANUAL)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    val donorBytes = tmp.resolve("extdonor.nbt").readBytes()

                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("extafter.nbt"))
                    drainPayloads(player)
                    val file = tmp.resolve("extafter.nbt")
                    val region = registry.structureRegionOriginOf("extafter.nbt")!!

                    // FIRST commit -- this is what records the fingerprint.
                    val first = region.offset(1, 0, 1)
                    lvl.setBlock(first, Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, first)
                    StructureCommit.commit(this, "extafter.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    LocalHistoryStore.revisions(file).count { it.reason == LocalHistoryStore.REASON_EXTERNAL } shouldBe 0

                    // NOW clobber the file out of band, with the fingerprint already in place.
                    file.writeBytes(donorBytes)

                    val second = region.offset(2, 0, 1)
                    lvl.setBlock(second, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, second)
                    StructureCommit.commit(this, "extafter.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()

                    // The out-of-band content was banked rather than silently overwritten.
                    val external = LocalHistoryStore.revisions(file)
                        .filter { it.reason == LocalHistoryStore.REASON_EXTERNAL }
                    external shouldHaveSize 1
                    val bankedTag = LocalHistoryStore.readTag(file, external.single()).shouldNotBeNull()
                    val donorTag = LocalHistoryStore.readTag(
                        tmp.resolve("extdonor.nbt"),
                        LocalHistoryStore.revisions(tmp.resolve("extdonor.nbt")).last(),
                    ).shouldNotBeNull()
                    structuresDiffer(bankedTag, donorTag) shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }

    test("an out-of-band .nbt edit is banked as a REASON_EXTERNAL revision before the next commit overwrites it") {
        withTempRoot("autosave-external") { tmp ->
            val prevChunks = SharedSettings.structureRegionChunks
            val prevHistDir = SharedSettings.localHistoryDir
            SharedSettings.structureRegionChunks = 1
            val histDir = kotlin.io.path.createTempDirectory("autosave-external-hist")
            SharedSettings.localHistoryDir = histDir.toAbsolutePath().toString()
            EditorNewStructure.create(tmp, "external")
            EditorNewStructure.create(tmp, "donor")
            try {
                onServer {
                    EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                    val player = makeMockServerPlayer(this)

                    // Produce genuinely different, valid structure content by committing an edit to
                    // an unrelated "donor" structure through the real path.
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("donor.nbt"))
                    drainPayloads(player)
                    val donorFile = tmp.resolve("donor.nbt")
                    val donorRegistry = EditorDimRegistry.of(this)
                    val donorRegion = donorRegistry.structureRegionOriginOf("donor.nbt")!!
                    val lvl = overworld()
                    val donorEdit = donorRegion.offset(1, 0, 1)
                    lvl.setBlock(donorEdit, Blocks.EMERALD_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, donorEdit)
                    StructureCommit.commit(this, "donor.nbt", LocalHistoryStore.REASON_MANUAL)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()
                    val donorBytes = donorFile.readBytes()

                    // Place "external.nbt" -- this seeds its own placed baseline revision, matching
                    // its (empty) on-disk content at this point.
                    EditorStructureHandlers.handlePlaceStructure(this, player, PlaceStructureC2S("external.nbt"))
                    drainPayloads(player)
                    val externalFile = tmp.resolve("external.nbt")
                    LocalHistoryStore.revisions(externalFile) shouldHaveSize 1
                    LocalHistoryStore.revisions(externalFile).single().reason shouldBe LocalHistoryStore.REASON_PLACED

                    // Simulate content changed OUTSIDE the editor between sessions (an external NBT
                    // tool, a git checkout, a restore-from-backup): overwrite external.nbt's bytes
                    // directly, bypassing StructureCommit entirely. No revision anywhere describes
                    // this content yet.
                    externalFile.writeBytes(donorBytes)

                    // A real, tracked edit to "external.nbt" that will trigger the next commit.
                    val registry = EditorDimRegistry.of(this)
                    val region = registry.structureRegionOriginOf("external.nbt")!!
                    val trackedEdit = region.offset(2, 0, 2)
                    lvl.setBlock(trackedEdit, Blocks.DIAMOND_BLOCK.defaultBlockState(), 2)
                    StructureEditWatcher.onBlockChanged(lvl, trackedEdit)

                    StructureCommit.commit(this, "external.nbt", LocalHistoryStore.REASON_AUTOSAVE)
                        .shouldBeInstanceOf<CommitOutcome.Committed>()

                    // Three revisions now: the original placed baseline, the out-of-band content
                    // banked before it was overwritten, and the new tracked-edit commit.
                    val revisions = LocalHistoryStore.revisions(externalFile)
                    revisions shouldHaveSize 3
                    revisions[0].reason shouldBe LocalHistoryStore.REASON_PLACED
                    revisions[1].reason shouldBe LocalHistoryStore.REASON_EXTERNAL
                    revisions[2].reason shouldBe LocalHistoryStore.REASON_AUTOSAVE

                    // The out-of-band content is genuinely recoverable, byte-for-byte identical to
                    // what was on disk right before the commit overwrote it.
                    val bankedTag = LocalHistoryStore.readTag(externalFile, revisions[1]).shouldNotBeNull()
                    val donorTag = LocalHistoryStore.readTag(donorFile, LocalHistoryStore.revisions(donorFile).last())
                        .shouldNotBeNull()
                    structuresDiffer(bankedTag, donorTag) shouldBe false
                }
            } finally {
                SharedSettings.structureRegionChunks = prevChunks
                SharedSettings.localHistoryDir = prevHistDir
                histDir.toFile().deleteRecursively()
            }
        }
    }
})
