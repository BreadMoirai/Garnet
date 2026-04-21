package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateSpecTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun simpleSpec(vararg extra: Pair<SimTime, Map<String, String>>): StateSpec {
        val entries = mutableListOf(SimTime.INIT to mapOf("powered" to "false"))
        entries.addAll(extra)
        return StateSpec(entries)
    }

    @Test
    fun `requires INIT entry`() {
        assertThrows<IllegalArgumentException> {
            StateSpec(listOf(SimTime(0, Phase.START_OF_TICK) to mapOf("powered" to "true")))
        }
    }

    @Test
    fun `single INIT entry is valid`() {
        val spec = StateSpec(listOf(SimTime.INIT to mapOf("powered" to "false")))
        assertEquals(1, spec.entries.size)
    }

    @Test
    fun `codec roundtrip single entry`() {
        val spec = StateSpec(listOf(SimTime.INIT to mapOf("powered" to "false")))
        val encoded = StateSpec.CODEC.encodeStart(NbtOps.INSTANCE, spec).getOrThrow()
        val decoded = StateSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(spec, decoded)
    }

    @Test
    fun `codec roundtrip multiple entries`() {
        val spec = simpleSpec(
            SimTime(0, Phase.START_OF_TICK) to mapOf("powered" to "true"),
            SimTime(1, Phase.BLOCK_EVENTS) to mapOf("powered" to "false", "facing" to "north"),
        )
        val encoded = StateSpec.CODEC.encodeStart(NbtOps.INSTANCE, spec).getOrThrow()
        val decoded = StateSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(spec, decoded)
    }

    @Test
    fun `codec roundtrip empty properties map`() {
        val spec = StateSpec(listOf(SimTime.INIT to emptyMap()))
        val encoded = StateSpec.CODEC.encodeStart(NbtOps.INSTANCE, spec).getOrThrow()
        val decoded = StateSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(spec, decoded)
    }
}
