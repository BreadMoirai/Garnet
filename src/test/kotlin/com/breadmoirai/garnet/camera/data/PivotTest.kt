package com.breadmoirai.garnet.camera.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Which point a pivot raycast turns into an orbit centre.
 *
 * The distinction this pins is invisible in a magnitude check: the raw hit location and the block
 * centre are at most a block apart, so every assertion here names the exact coordinate rather than
 * asserting the pivot is "near" the block.
 */
class PivotTest : FunSpec({

    val block = BlockPos(4, -60, 12)

    test("a block hit orbits the centre of the cell, not the point on its face") {
        // A ray arriving from -Z lands on the block's north face, at z = 12.0 exactly — half a
        // block short of the centre, and on a different axis than the hit's x/y offsets.
        val face = BlockHitResult(Vec3(4.3, -59.2, 12.0), Direction.NORTH, block, false)

        val pivot = pivotFor(face, missPivot = Vec3(999.0, 999.0, 999.0))

        pivot.x shouldBe (4.5 plusOrMinus 1e-9)
        pivot.y shouldBe (-59.5 plusOrMinus 1e-9)
        pivot.z shouldBe (12.5 plusOrMinus 1e-9)
    }

    test("the centre is the cell's, so every face of one block gives the same pivot") {
        // The property that makes the pivot predictable: clicking the top of a block and clicking
        // its side orbit the same point. Orbiting `hit.location` gives two different pivots here.
        val top = BlockHitResult(Vec3(4.9, -59.0, 12.1), Direction.UP, block, false)
        val west = BlockHitResult(Vec3(4.0, -59.9, 12.7), Direction.WEST, block, false)

        pivotFor(top, missPivot = Vec3.ZERO) shouldBe pivotFor(west, missPivot = Vec3.ZERO)
    }

    test("a miss keeps the fallback point — there is no block whose centre to take") {
        val miss = BlockHitResult.miss(Vec3(4.3, -59.2, 12.0), Direction.NORTH, block)
        val fallback = Vec3(1.0, 2.0, 3.0)

        pivotFor(miss, missPivot = fallback) shouldBe fallback
    }

    test("negative coordinates round toward the cell, not toward zero") {
        // BlockPos(-1, ..) spans -1.0..0.0, so its centre is -0.5. An implementation that added
        // 0.5 to an int truncated the wrong way would put the pivot in the neighbouring block.
        val hit = BlockHitResult(Vec3(0.0, -0.4, -0.7), Direction.EAST, BlockPos(-1, -1, -1), false)

        pivotFor(hit, missPivot = Vec3.ZERO) shouldBe Vec3(-0.5, -0.5, -0.5)
    }
})
