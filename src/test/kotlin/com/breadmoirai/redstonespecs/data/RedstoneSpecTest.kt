package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedstoneSpecTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.START to StateCondition.BoolProperty("powered", false))

    private fun roundtrip(value: RedstoneSpec): RedstoneSpec {
        val encoded = RedstoneSpec.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return RedstoneSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `empty RedstoneSpec roundtrip`() {
        val spec = RedstoneSpec.new("test-spec")
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `mode roundtrip - all modes`() {
        for (mode in SpecMode.entries) {
            val spec = RedstoneSpec.new("spec").copy(mode = mode)
            assertEquals(mode, roundtrip(spec).mode)
        }
    }

    @Test
    fun `id preserved across roundtrip`() {
        val spec = RedstoneSpec.new("my-circuit")
        assertEquals("my-circuit", roundtrip(spec).id)
    }

    @Test
    fun `lifespan preserved`() {
        val spec = RedstoneSpec.new("spec").copy(lifespan = 42)
        assertEquals(42, roundtrip(spec).lifespan)
    }

    @Test
    fun `structure nullable roundtrip`() {
        val withStructure = RedstoneSpec.new("spec").copy(structure = "shared_counter")
        assertEquals("shared_counter", roundtrip(withStructure).structure)
        val noStructure = RedstoneSpec.new("spec")
        assertNull(roundtrip(noStructure).structure)
    }

    @Test
    fun `bounds preserved`() {
        val bounds = BoundingBox(-3, 60, -3, 12, 65, 12)
        val spec = RedstoneSpec.new("spec").copy(bounds = bounds)
        assertEquals(bounds, roundtrip(spec).bounds)
    }

    @Test
    fun `spec with inputs and outputs roundtrip`() {
        val endEntries = listOf(SimTime(8, Phase.END_OF_TICK) to StateCondition.BoolProperty("lit", true))
        val spec = RedstoneSpec.new("lever-lamp").copy(
            lifespan = 8,
            inputs = listOf(InputSpec(BlockPos(1, 0, 0), "lever", 0x4488FF, initEntries)),
            outputs = listOf(OutputSpec(BlockPos(3, 0, 0), "lamp", 0x44FF88, endEntries)),
        )
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `TestResult codec roundtrip`() {
        val result = TestResult(
            specId = "my-spec",
            timestamp = 1000L,
            checks = listOf(
                TickCheck(SimTime(0, Phase.END_OF_TICK), "lamp.lit", "true", "true", pass = true),
                TickCheck(SimTime(1, Phase.END_OF_TICK), "lamp.lit", "true", "false", pass = false),
            ),
        )
        val encoded = TestResult.CODEC.encodeStart(NbtOps.INSTANCE, result).getOrThrow()
        val decoded = TestResult.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(result, decoded)
    }
}
