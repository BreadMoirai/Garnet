package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.network.nudgeBounds
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BoundsNudgeTest {

    private fun box() = BoundingBox(-4, -1, -4, 4, 3, 4)

    @Test fun `nudge max-X forward expands east`() {
        val result = nudgeBounds(box(), axis = 0, isMax = true, delta = 1)
        assertEquals(5, result.maxX())
        assertEquals(-4, result.minX())
    }

    @Test fun `nudge min-X forward shrinks west side`() {
        val result = nudgeBounds(box(), axis = 0, isMax = false, delta = 1)
        assertEquals(-3, result.minX())
        assertEquals(4, result.maxX())
    }

    @Test fun `nudge max-X cannot go below minX`() {
        val b = BoundingBox(0, 0, 0, 0, 0, 0)
        val result = nudgeBounds(b, axis = 0, isMax = true, delta = -5)
        assertEquals(0, result.maxX())
    }

    @Test fun `nudge min-X cannot exceed maxX`() {
        val b = BoundingBox(0, 0, 0, 0, 0, 0)
        val result = nudgeBounds(b, axis = 0, isMax = false, delta = 5)
        assertEquals(0, result.minX())
    }

    @Test fun `nudge Y axis affects only Y coords`() {
        val result = nudgeBounds(box(), axis = 1, isMax = true, delta = 2)
        assertEquals(5, result.maxY())
        assertEquals(-1, result.minY())
        assertEquals(-4, result.minX()); assertEquals(4, result.maxX())
        assertEquals(-4, result.minZ()); assertEquals(4, result.maxZ())
    }

    @Test fun `nudge Z axis affects only Z coords`() {
        val result = nudgeBounds(box(), axis = 2, isMax = false, delta = -1)
        assertEquals(-5, result.minZ())
        assertEquals(4, result.maxZ())
    }

    @Test fun `unknown axis returns unchanged bounds`() {
        val b = box()
        val result = nudgeBounds(b, axis = 99, isMax = true, delta = 1)
        assertEquals(b, result)
    }
}
