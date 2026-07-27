package com.breadmoirai.redstonespecs.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito

class ProjectDimRegistryTest : FunSpec({

    fun newRegistry(): ProjectDimRegistry =
        ProjectDimRegistry(Mockito.mock(MinecraftServer::class.java))

    test("getOrAssignRegion is idempotent for the same subpath") {
        val r = newRegistry()
        val a1 = r.getOrAssignRegion("set/a")
        val a2 = r.getOrAssignRegion("set/a")
        a1 shouldBe a2
    }

    test("distinct subpaths get distinct origins") {
        val r = newRegistry()
        val a = r.getOrAssignRegion("set/a")
        val b = r.getOrAssignRegion("set/b")
        (a == b) shouldBe false
    }

    test("regionOriginOf returns null for unassigned subpath") {
        newRegistry().regionOriginOf("never").shouldBeNull()
    }

    test("region width matches cellSize.x * rowMax + gap*(rowMax+1) + REGION_PAD") {
        val r = newRegistry()
        val a = r.getOrAssignRegion("a")  // index 0
        val b = r.getOrAssignRegion("b")  // index 1
        val cs = SharedSettings.projectCellSize
        val gap = SharedSettings.projectCellGap
        val rowMax = SharedSettings.projectRowMax
        val expectedWidth = cs.x * rowMax + gap * (rowMax + 1) + ProjectDimRegistry.REGION_PAD
        (b.x - a.x) shouldBe expectedWidth
        b.z shouldBe a.z
    }

    test("getOrAssignStructureRegion is idempotent and distinct per subpath") {
        val r = newRegistry()
        val a1 = r.getOrAssignStructureRegion("things/box.nbt")
        val a2 = r.getOrAssignStructureRegion("things/box.nbt")
        a1 shouldBe a2
        val b = r.getOrAssignStructureRegion("things/other.nbt")
        (a1 == b) shouldBe false
    }

    test("structure regions sit in a lane disjoint from spec-folder regions") {
        val r = newRegistry()
        val spec = r.getOrAssignRegion("set/a")           // spec lane: z == 0
        val struct = r.getOrAssignStructureRegion("s.nbt") // structure lane: z == STRUCTURE_LANE_Z
        spec.z shouldBe 0
        struct.z shouldBe ProjectDimRegistry.STRUCTURE_LANE_Z
    }

    test("placed-box round-trips per subpath") {
        val r = newRegistry()
        r.placedBoxOf("s.nbt").shouldBeNull()
        val box = PlacedBox(net.minecraft.core.BlockPos(1, 2, 3), net.minecraft.core.Vec3i(4, 5, 6))
        r.setPlacedBox("s.nbt", box)
        r.placedBoxOf("s.nbt") shouldBe box
    }
})
