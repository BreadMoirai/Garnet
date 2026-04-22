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
class SpecEntryTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private val initEntries = listOf(SimTime.INIT to StateCondition.BoolProperty("powered", false))
    private val pos = BlockPos(1, 2, 3)
    private val color = 0xFF0000

    private fun <T> roundtrip(value: T, codec: com.mojang.serialization.Codec<T>): T {
        val encoded = codec.encodeStart(NbtOps.INSTANCE, value).getOrThrow()
        return codec.parse(NbtOps.INSTANCE, encoded).getOrThrow()
    }

    @Test
    fun `InputSpec roundtrip via SpecEntry codec`() {
        val entry: SpecEntry = InputSpec(pos, "A", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `OutputSpec roundtrip via SpecEntry codec`() {
        val entry: SpecEntry = OutputSpec(pos, "B", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `InputSpec with multiple entries roundtrip`() {
        val entries = listOf(
            SimTime.INIT to StateCondition.BoolProperty("powered", false),
            SimTime(0, Phase.END_OF_TICK) to StateCondition.All(listOf(
                StateCondition.BoolProperty("powered", true),
                StateCondition.IntProperty("power", 15),
            )),
        )
        val entry: SpecEntry = InputSpec(pos, "multi", color, entries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `BreakpointSpec roundtrip with defaults`() {
        val entry: SpecEntry = BreakpointSpec(pos, "BP", color)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `BreakpointSpec roundtrip with typed condition and disabled`() {
        val entry: SpecEntry = BreakpointSpec(
            pos, "BP", color,
            condition = StateCondition.BoolProperty("lit", true),
            enabled = false,
        )
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `AutoSpec roundtrip with defaults`() {
        val entry: SpecEntry = AutoSpec(pos, "Auto", color)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `AutoSpec roundtrip with custom condition`() {
        val entry: SpecEntry = AutoSpec(
            pos, "Auto", color,
            condition = StateCondition.ContainerContents(slot = 0, minCount = 3),
        )
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `negative relative positions roundtrip`() {
        val entry: SpecEntry = InputSpec(BlockPos(-5, -1, 10), "neg", color, initEntries)
        assertEquals(entry, roundtrip(entry, SpecEntry.CODEC))
    }

    @Test
    fun `InputSpec MAP_CODEC roundtrip directly`() {
        val entry = InputSpec(pos, "direct", color, initEntries)
        val encoded = InputSpec.MAP_CODEC.codec().encodeStart(NbtOps.INSTANCE, entry).getOrThrow()
        val decoded = InputSpec.MAP_CODEC.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow()
        assertEquals(entry, decoded)
    }
}
