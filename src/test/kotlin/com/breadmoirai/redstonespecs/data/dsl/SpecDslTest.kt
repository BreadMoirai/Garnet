package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SpecDslTest {
    @Test
    fun `redstoneSpec builds a flat entry list`() {
        val spec = redstoneSpec("door_latch") {
            bounds(5, 4, 5)
            lifespan = 40
            structure = "redstonespecs:door_latch"

            input(2, 0, 2, label = "lever", color = 0xFFFF4444.toInt()) {
                atStart { powered() }
                at(tick = 10) { not { powered() } }
            }
            output(4, 0, 4, label = "lamp", color = -1) {
                at(tick = 11) { lit() }
            }
        }

        assertEquals("door_latch", spec.id)
        assertEquals(40, spec.lifespan)
        assertEquals(3, spec.entries.size)

        val sorted = spec.entries.sortedBy { it.time }
        val e0 = sorted[0]
        val e1 = sorted[1]
        val e2 = sorted[2]

        assertEquals(EntryKind.INPUT, e0.kind)
        assertEquals(SimTime.START, e0.time)
        assertEquals(StateCondition.BoolProperty("powered", true), e0.condition)

        assertEquals(EntryKind.INPUT, e1.kind)
        assertEquals(10, e1.time.tick)
        assertEquals(StateCondition.Not(StateCondition.BoolProperty("powered", true)), e1.condition)

        assertEquals(EntryKind.OUTPUT, e2.kind)
        assertEquals(11, e2.time.tick)
        assertEquals(Phase.END_OF_TICK, e2.time.phase)
        assertEquals(StateCondition.BoolProperty("lit", true), e2.condition)
    }
}
