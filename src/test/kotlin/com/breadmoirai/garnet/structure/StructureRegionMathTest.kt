package com.breadmoirai.garnet.structure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class StructureRegionMathTest : FunSpec({

    test("autoFit returns null when the volume has no non-air") {
        autoFit(4, 4, 4) { _, _, _ -> false }.shouldBeNull()
    }

    test("autoFit tightly boxes a single non-air cell") {
        val box = autoFit(8, 8, 8) { x, y, z -> x == 2 && y == 3 && z == 4 }
        box shouldBe FitBox(2, 3, 4, 1, 1, 1)
    }

    test("autoFit tightly boxes scattered non-air cells") {
        // cells at (1,1,1) and (5,2,6) -> min (1,1,1), max (5,2,6) -> size (5,2,6)
        val hits = setOf(Triple(1, 1, 1), Triple(5, 2, 6))
        val box = autoFit(8, 8, 8) { x, y, z -> Triple(x, y, z) in hits }
        box shouldBe FitBox(1, 1, 1, 5, 2, 6)
    }

    test("centeredStart centers a box in a region (floor-divides odd slack)") {
        centeredStart(100, 16, 4) shouldBe 106   // 100 + (16-4)/2
        centeredStart(0, 15, 4) shouldBe 5        // 0 + (15-4)/2 = 5
    }

    test("anchorY floors short structures at yBase") {
        anchorY(structHeight = 10, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 64
        anchorY(structHeight = 255, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 64
    }

    test("anchorY vertically centers structures at or above the tall threshold") {
        // regionMinY -64, regionHeight 384, structHeight 256 -> -64 + (384-256)/2 = 0
        anchorY(structHeight = 256, yBase = 64, regionMinY = -64, regionHeight = 384) shouldBe 0
    }
})
