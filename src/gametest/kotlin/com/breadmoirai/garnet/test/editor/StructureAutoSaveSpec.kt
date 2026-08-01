package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.world.EditorDimRegistry
import com.breadmoirai.garnet.editor.world.StructureAutoSave
import com.breadmoirai.garnet.editor.world.StructureEditWatcher
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

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
})
