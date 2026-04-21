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

    private val initSpec = StateSpec(listOf(SimTime.INIT to mapOf("powered" to "false")))

    private fun roundtrip(value: RedstoneSpec): RedstoneSpec {
        val encoded = RedstoneSpec.CODEC.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return RedstoneSpec.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `empty RedstoneSpec roundtrip`() {
        val spec = RedstoneSpec(
            id = UUID.randomUUID(),
            name = "test-spec",
            bounds = BoundingBox(0, 0, 0, 10, 5, 10),
            oneShot = false,
            specCases = emptyList(),
        )
        assertEquals(spec, roundtrip(spec))
    }

    @Test
    fun `oneShot flag roundtrip`() {
        val spec = RedstoneSpec(
            id = UUID.randomUUID(),
            name = "one-shot",
            bounds = BoundingBox(0, 0, 0, 4, 4, 4),
            oneShot = true,
            specCases = emptyList(),
        )
        val decoded = roundtrip(spec)
        assertEquals(true, decoded.oneShot)
    }

    @Test
    fun `UUID preserved across roundtrip`() {
        val id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
        val spec = RedstoneSpec(id, "uuid-test", BoundingBox(0, 0, 0, 1, 1, 1), false, emptyList())
        assertEquals(id, roundtrip(spec).id)
    }

    @Test
    fun `bounds preserved across roundtrip`() {
        val bounds = BoundingBox(-3, 60, -3, 12, 65, 12)
        val spec = RedstoneSpec(UUID.randomUUID(), "bounds-test", bounds, false, emptyList())
        assertEquals(bounds, roundtrip(spec).bounds)
    }

    @Test
    fun `RedstoneSpec with multiple SpecCases roundtrip`() {
        val cases = listOf(
            SpecCase("case-1", 20,
                inputs = listOf(InputSpec(BlockPos(1, 0, 0), "A", 0xFF0000, initSpec)),
                outputs = listOf(OutputSpec(BlockPos(5, 0, 0), "Q", 0x0000FF, initSpec)),
                breakpoints = emptyList(), autoSpecs = emptyList()),
            SpecCase("case-2", 30, emptyList(), emptyList(), emptyList(), emptyList()),
        )
        val spec = RedstoneSpec(UUID.randomUUID(), "multi-case", BoundingBox(0, 0, 0, 8, 4, 8), false, cases)
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
