package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimTimeTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    @Test
    fun `INIT sorts before tick 0`() {
        val t0 = SimTime(0, Phase.START_OF_TICK)
        assertTrue(SimTime.INIT < t0)
    }

    @Test
    fun `tick ordering`() {
        val t1 = SimTime(0, Phase.END_OF_TICK)
        val t2 = SimTime(1, Phase.START_OF_TICK)
        assertTrue(t1 < t2)
    }

    @Test
    fun `phase ordering within same tick`() {
        Phase.entries.zipWithNext().forEach { (a, b) ->
            assertTrue(SimTime(0, a) < SimTime(0, b), "$a should sort before $b")
        }
    }

    @Test
    fun `order tiebreaker within same tick and phase`() {
        val t1 = SimTime(0, Phase.START_OF_TICK, 0)
        val t2 = SimTime(0, Phase.START_OF_TICK, 1)
        assertTrue(t1 < t2)
    }

    @Test
    fun `equal SimTimes compare to zero`() {
        val t = SimTime(5, Phase.BLOCK_EVENTS, 3)
        assertEquals(0, t.compareTo(t))
    }

    @Test
    fun `codec roundtrip via NBT`() {
        val simTime = SimTime(5, Phase.SCHEDULED_TICKS, 3)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, simTime).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(simTime, decoded)
    }

    @Test
    fun `INIT codec roundtrip`() {
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, SimTime.INIT).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(SimTime.INIT, decoded)
    }

    @Test
    fun `default order omitted from NBT`() {
        val withDefaultOrder = SimTime(1, Phase.START_OF_TICK, 0)
        val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, withDefaultOrder).getOrThrow()
        val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(withDefaultOrder, decoded)
    }

    @Test
    fun `all phases roundtrip`() {
        Phase.entries.forEach { phase ->
            val t = SimTime(0, phase)
            val encoded = SimTime.CODEC.encodeStart(NbtOps.INSTANCE, t).getOrThrow()
            val decoded = SimTime.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
            assertEquals(t, decoded, "Failed roundtrip for phase $phase")
        }
    }
}
