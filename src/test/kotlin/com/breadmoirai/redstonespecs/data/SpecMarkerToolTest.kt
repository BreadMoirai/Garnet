package com.breadmoirai.redstonespecs.data

import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecMarkerToolTest {

    @Test
    fun `InputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(1, 0, 0)
        val initProps = mapOf("powered" to "false", "facing" to "north")
        val entry = InputSpec(pos, "", 0x4488FF, StateSpec(listOf(SimTime.INIT to initProps)))
        assertEquals(pos, entry.pos)
        assertEquals(1, entry.stateSpec.entries.size)
        assertEquals(SimTime.INIT to initProps, entry.stateSpec.entries.first())
    }

    @Test
    fun `OutputSpec created with INIT entry from captured props`() {
        val pos = BlockPos(2, 0, 0)
        val initProps = mapOf("lit" to "false")
        val entry = OutputSpec(pos, "", 0x44FF88, StateSpec(listOf(SimTime.INIT to initProps)))
        assertEquals(pos, entry.pos)
        assertEquals(SimTime.INIT to initProps, entry.stateSpec.entries.first())
    }
}
