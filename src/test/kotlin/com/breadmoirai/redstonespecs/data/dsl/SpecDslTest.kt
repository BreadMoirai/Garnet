package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class SpecDslTest : FunSpec({

    test("redstoneSpec builds a flat entry list") {
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

        spec.id shouldBe "door_latch"
        spec.lifespan shouldBe 40
        spec.entries.size shouldBe 3

        val sorted = spec.entries.sortedBy { it.time }
        val e0 = sorted[0]
        val e1 = sorted[1]
        val e2 = sorted[2]

        e0.kind shouldBe EntryKind.INPUT
        e0.time shouldBe SimTime.START
        e0.condition shouldBe StateCondition.BoolProperty("powered", true)

        e1.kind shouldBe EntryKind.INPUT
        e1.time.tick shouldBe 10
        e1.condition shouldBe StateCondition.Not(StateCondition.BoolProperty("powered", true))

        e2.kind shouldBe EntryKind.OUTPUT
        e2.time.tick shouldBe 11
        e2.time.phase shouldBe Phase.END_OF_TICK
        e2.condition shouldBe StateCondition.BoolProperty("lit", true)
    }
})
