package com.breadmoirai.garnet.spec

import com.breadmoirai.garnet.spec.Phase
import com.breadmoirai.garnet.spec.SimTime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap

class SimTimeTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    test("START sorts before tick 0") {
        val t0 = SimTime(0, Phase.START_OF_TICK)
        (SimTime.START < t0) shouldBe true
    }

    test("tick ordering") {
        val t1 = SimTime(0, Phase.END_OF_TICK)
        val t2 = SimTime(1, Phase.START_OF_TICK)
        (t1 < t2) shouldBe true
    }

    test("phase ordering within same tick") {
        Phase.entries.zipWithNext().forEach { (a, b) ->
            (SimTime(0, a) < SimTime(0, b)) shouldBe true
        }
    }

    test("order tiebreaker within same tick and phase") {
        val t1 = SimTime(0, Phase.START_OF_TICK, 0)
        val t2 = SimTime(0, Phase.START_OF_TICK, 1)
        (t1 < t2) shouldBe true
    }

    test("equal SimTimes compare to zero") {
        val t = SimTime(5, Phase.BLOCK_EVENTS, 3)
        t.compareTo(t) shouldBe 0
    }

    test("codec roundtrip via NBT") {
        val simTime = SimTime(5, Phase.SCHEDULED_TICKS, 3)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, simTime).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe simTime
    }

    test("START codec roundtrip") {
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, SimTime.START).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe SimTime.START
    }

    test("default order omitted from NBT") {
        val withDefaultOrder = SimTime(1, Phase.START_OF_TICK, 0)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, withDefaultOrder).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        decoded shouldBe withDefaultOrder
    }

    test("all phases roundtrip") {
        Phase.entries.forEach { phase ->
            val t = SimTime(0, phase)
            val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()
            val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
            decoded shouldBe t
        }
    }
})
