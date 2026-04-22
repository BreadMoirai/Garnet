package com.breadmoirai.redstonespecs.data

import com.breadmoirai.redstonespecs.item.InputSpecMarkerItem
import com.breadmoirai.redstonespecs.item.OutputSpecMarkerItem
import net.minecraft.core.BlockPos
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class SpecMarkerToolTest {

    @Test
    fun `InputSpecMarkerItem creates entry with captured init props`() {
        val marker = InputSpecMarkerItem()
        val pos = BlockPos(1, 0, 0)
        val initProps = mapOf("powered" to "false", "facing" to "north")
        val entry = marker.createEntry(pos, initProps)
        assertInstanceOf(InputSpec::class.java, entry)
        val inputEntry = entry as InputSpec
        assertEquals(pos, inputEntry.pos)
        assertEquals(SimTime.INIT to initProps, inputEntry.stateSpec.entries.first())
    }

    @Test
    fun `OutputSpecMarkerItem creates entry with captured init props`() {
        val marker = OutputSpecMarkerItem()
        val pos = BlockPos(2, 0, 0)
        val initProps = mapOf("lit" to "false")
        val entry = marker.createEntry(pos, initProps)
        assertInstanceOf(OutputSpec::class.java, entry)
        val outputEntry = entry as OutputSpec
        assertEquals(pos, outputEntry.pos)
        assertEquals(SimTime.INIT to initProps, outputEntry.stateSpec.entries.first())
    }
}
