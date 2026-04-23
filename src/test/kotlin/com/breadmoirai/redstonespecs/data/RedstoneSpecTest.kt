package com.breadmoirai.redstonespecs.data

import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.nbt.NbtOps
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedstoneSpecTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))

    private fun roundtrip(value: RedstoneSpec): RedstoneSpec {
        val encoded = RedstoneSpec.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return RedstoneSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `empty RedstoneSpec roundtrip`() {
        val spec = RedstoneSpec(
            id = "test-spec",
            mode = SpecMode.SIMPLE,
            bounds = BoundingBox(0, 0, 0, 10, 5, 10),
            lifespan = 20,
            structure = null,
            inputs = emptyList(),
            outputs = emptyList(),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `mode preserved across roundtrip`() {
        val spec = RedstoneSpec(
            id = "mode-test",
            mode = SpecMode.TICK_AWARE,
            bounds = BoundingBox(0, 0, 0, 4, 4, 4),
            lifespan = 40,
            structure = null,
            inputs = emptyList(),
            outputs = emptyList(),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        val decoded = roundtrip(spec)
        assertEquals(SpecMode.TICK_AWARE, decoded.mode)
    }

    @Test
    fun `id preserved across roundtrip`() {
        val id = "my-spec-id"
        val spec = RedstoneSpec(
            id = id,
            mode = SpecMode.SIMPLE,
            bounds = BoundingBox(0, 0, 0, 1, 1, 1),
            lifespan = 20,
            structure = null,
            inputs = emptyList(),
            outputs = emptyList(),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        assertEquals(id, roundtrip(spec).id)
    }

    @Test
    fun `bounds preserved across roundtrip`() {
        val bounds = BoundingBox(-3, 60, -3, 12, 65, 12)
        val spec = RedstoneSpec(
            id = "bounds-test",
            mode = SpecMode.SIMPLE,
            bounds = bounds,
            lifespan = 20,
            structure = null,
            inputs = emptyList(),
            outputs = emptyList(),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        assertEquals(bounds, roundtrip(spec).bounds)
    }

    @Test
    fun `RedstoneSpec with entries roundtrip`() {
        val spec = RedstoneSpec(
            id = "with-entries",
            mode = SpecMode.SIMPLE,
            bounds = BoundingBox(0, 0, 0, 8, 4, 8),
            lifespan = 20,
            structure = null,
            inputs = listOf(InputSpec(BlockPos(1, 0, 0), "A", 0xFF0000, initEntries)),
            outputs = listOf(OutputSpec(BlockPos(5, 0, 0), "Q", 0x0000FF, initEntries)),
            breakpoints = emptyList(),
            autoSpecs = emptyList(),
        )
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `TestResult codec roundtrip`() {
        val id = UUID.randomUUID()
        val result = TestResult(
            specId = id,
            timestamp = System.currentTimeMillis(),
            results = listOf(
                SpecCaseResult("case-1", listOf(
                    TickCheck(SimTime(0, Phase.START_OF_TICK), "Q", "true", "true", pass = true),
                    TickCheck(SimTime(1, Phase.BLOCK_EVENTS), "Q", "false", "true", pass = false),
                )),
                SpecCaseResult("case-2", emptyList()),
            ),
        )
        val encoded = TestResult.CODEC.encodeStart(NbtOps.INSTANCE, result).getOrThrow()
        val decoded = TestResult.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(result, decoded)
    }
}
