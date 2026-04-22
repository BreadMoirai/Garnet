package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpecCaseTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))

    private fun roundtrip(value: SpecCase): SpecCase {
        val encoded = SpecCase.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return SpecCase.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `empty SpecCase roundtrip`() {
        val case = SpecCase("empty", lifespan = 20, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(case, roundtrip(case))
    }

    @Test
    fun `SpecCase with all entry types roundtrip`() {
        val case = SpecCase(
            name = "full",
            lifespan = 40,
            inputs = listOf(
                InputSpec(BlockPos(1, 0, 0), "A", 0xFF0000, initEntries),
                InputSpec(BlockPos(2, 0, 0), "B", 0x00FF00, initEntries),
            ),
            outputs = listOf(
                OutputSpec(BlockPos(5, 0, 0), "Q", 0x0000FF, initEntries),
            ),
            breakpoints = listOf(
                BreakpointSpec(BlockPos(3, 0, 0), "BP", 0xFF00FF),
            ),
            autoSpecs = listOf(
                AutoSpec(BlockPos(4, 0, 0), "auto", 0xFFFF00),
            ),
        )
        assertEquals(case, roundtrip(case))
    }

    @Test
    fun `SpecCase name preserved`() {
        val case = SpecCase("my-test-case", 10, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals("my-test-case", roundtrip(case).name)
    }

    @Test
    fun `SpecCase lifespan preserved`() {
        val case = SpecCase("t", 100, emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(100, roundtrip(case).lifespan)
    }
}
