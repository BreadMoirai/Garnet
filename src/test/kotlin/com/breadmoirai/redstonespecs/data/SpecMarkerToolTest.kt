package com.breadmoirai.redstonespecs.data

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecMarkerToolTest {

    @Test
    fun `InputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(1, 0, 0)
        val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))
        val entry = InputSpec(pos, "", 0x4488FF, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(1, entry.entries.size)
        assertEquals(SimTime.INIT, entry.entries.first().first)
    }

    @Test
    fun `OutputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(2, 0, 0)
        val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("lit", false))
        val entry = OutputSpec(pos, "", 0x44FF88, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.INIT, entry.entries.first().first)
    }
}
