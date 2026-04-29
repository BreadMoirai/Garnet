package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.item.nextLabel
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecMarkerToolTest {

    @Test
    fun `InputSpec created with START entry from captured props`() {
        val pos = BlockPos(1, 0, 0)
        val initEntries = listOf(SimTime.START to StateCondition.BoolProperty("powered", false))
        val entry = InputSpec(pos, "", 0x4488FF, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(1, entry.entries.size)
        assertEquals(SimTime.START, entry.entries.first().first)
    }

    @Test
    fun `OutputSpec created with START entry from captured props`() {
        val pos = BlockPos(2, 0, 0)
        val initEntries = listOf(SimTime.START to StateCondition.BoolProperty("lit", false))
        val entry = OutputSpec(pos, "", 0x44FF88, initEntries)
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.START, entry.entries.first().first)
    }

    @Test
    fun `nextLabel returns a when no existing labels`() {
        assertEquals("lever_a", nextLabel("lever", emptySet()))
    }

    @Test
    fun `nextLabel skips taken suffixes`() {
        assertEquals("lever_c", nextLabel("lever", setOf("lever_a", "lever_b")))
    }

    @Test
    fun `nextLabel ignores labels from other blocks`() {
        assertEquals("lever_a", nextLabel("lever", setOf("button_a", "stone_button_a")))
    }

    @Test
    fun `nextLabel wraps to double letter after z`() {
        val existing = ('a'..'z').map { "lever_$it" }.toSet()
        assertEquals("lever_aa", nextLabel("lever", existing))
    }
}
