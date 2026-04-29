package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import java.util.UUID

class OutputVerifierTest {
    companion object {
        @JvmStatic
        @BeforeAll
        fun bootstrap() {
            net.minecraft.SharedConstants.tryDetectVersion()
            net.minecraft.server.Bootstrap.bootStrap()
        }
    }

    private val outputPos = BlockPos(0, 0, 0)
    private val redstoneLamp: BlockState =
        BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:redstone_lamp")).defaultBlockState()
    private val unlitLamp: BlockState = redstoneLamp        // lit=false default
    private val litLamp: BlockState =
        redstoneLamp.setValue(redstoneLamp.block.stateDefinition.getProperty("lit") as
            net.minecraft.world.level.block.state.properties.BooleanProperty, true)

    private fun spec(mode: SpecMode, output: OutputSpec, lifespan: Int = 5): RedstoneSpec =
        RedstoneSpec.new("test").copy(mode = mode, lifespan = lifespan, outputs = listOf(output))

    private fun recording(
        initial: BlockState = unlitLamp,
        changes: List<BlockStateChange> = emptyList(),
    ): StateRecording = StateRecording(
        specId = UUID.randomUUID(),
        timestamp = 0L,
        initialSnapshot = mapOf(outputPos to initial),
        changes = changes,
    )

    private fun litChangeAt(simTime: SimTime, lit: Boolean = true): BlockStateChange =
        BlockStateChange(outputPos, simTime, toBlock = null, diffs = listOf(PropertyDiff("lit", lit.toString())))

    private val litTrue = StateCondition.BoolProperty("lit", true)
    private val litFalse = StateCondition.BoolProperty("lit", false)

    @Test
    fun `SIMPLE end passes when final state matches`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(SimTime.END to litTrue))
        val rec = recording(changes = listOf(litChangeAt(SimTime(2, Phase.END_OF_TICK))))
        val result = OutputVerifier.verify(spec(SpecMode.SIMPLE, output), rec)
        assertTrue(result.pass, "expected pass, got $result")
    }

    @Test
    fun `SIMPLE end fails when final state mismatches`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(SimTime.END to litTrue))
        val rec = recording()       // never changes
        val result = OutputVerifier.verify(spec(SpecMode.SIMPLE, output), rec)
        assertFalse(result.pass)
    }

    @Test
    fun `SIMPLE ignores intermediate changes`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(SimTime.END to litFalse))
        val rec = recording(changes = listOf(
            litChangeAt(SimTime(1, Phase.END_OF_TICK), lit = true),
            litChangeAt(SimTime(3, Phase.END_OF_TICK), lit = false),
        ))
        val result = OutputVerifier.verify(spec(SpecMode.SIMPLE, output), rec)
        assertTrue(result.pass, "intermediate changes should not affect SIMPLE: $result")
    }

    @Test
    fun `SIMPLE with no entries produces no checks`() {
        val output = OutputSpec(outputPos, "lamp", 0, entries = emptyList())
        val rec = recording()
        val result = OutputVerifier.verify(spec(SpecMode.SIMPLE, output), rec)
        assertTrue(result.checks.isEmpty(), "expected no checks, got ${result.checks}")
    }

    @Test
    fun `TICK_AWARE passes when entries match per-tick post-state and no extra changes`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(
            SimTime.START to litFalse,
            SimTime(2, Phase.START_OF_TICK, 0) to litTrue,
            SimTime(4, Phase.START_OF_TICK, 0) to litFalse,
        ))
        val rec = recording(changes = listOf(
            litChangeAt(SimTime(2, Phase.END_OF_TICK), lit = true),
            litChangeAt(SimTime(4, Phase.END_OF_TICK), lit = false),
        ))
        val result = OutputVerifier.verify(spec(SpecMode.TICK_AWARE, output), rec)
        assertTrue(result.pass, "expected pass, got $result")
    }

    @Test
    fun `TICK_AWARE fails on unexpected change tick`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(
            SimTime(2, Phase.START_OF_TICK, 0) to litTrue,
        ))
        val rec = recording(changes = listOf(
            litChangeAt(SimTime(2, Phase.END_OF_TICK), lit = true),
            litChangeAt(SimTime(3, Phase.END_OF_TICK), lit = false),    // unexpected
        ))
        val result = OutputVerifier.verify(spec(SpecMode.TICK_AWARE, output), rec)
        assertFalse(result.pass)
        assertTrue(result.checks.any { !it.pass && it.label.contains("unexpected") },
            "expected an 'unexpected' diagnostic, got ${result.checks}")
    }

    @Test
    fun `TICK_AWARE fails when entry tick has wrong post-state`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(
            SimTime(2, Phase.START_OF_TICK, 0) to litTrue,
        ))
        val rec = recording()       // no changes; lit stays false
        val result = OutputVerifier.verify(spec(SpecMode.TICK_AWARE, output), rec)
        assertFalse(result.pass)
    }

    @Test
    fun `TICK_AWARE collapses multiple in-tick changes to end-of-tick value`() {
        val output = OutputSpec(outputPos, "lamp", 0, listOf(
            SimTime(1, Phase.START_OF_TICK, 0) to litTrue,
        ))
        val rec = recording(changes = listOf(
            litChangeAt(SimTime(1, Phase.SCHEDULED_TICKS), lit = true),
            litChangeAt(SimTime(1, Phase.END_OF_TICK), lit = true),
        ))
        val result = OutputVerifier.verify(spec(SpecMode.TICK_AWARE, output), rec)
        assertTrue(result.pass, "multiple changes within the same tick are one change tick: $result")
    }

    @Test
    fun `UPDATE_AWARE passes on exact SimTime match`() {
        val t1 = SimTime(2, Phase.END_OF_TICK, 0)
        val t2 = SimTime(4, Phase.END_OF_TICK, 0)
        val output = OutputSpec(outputPos, "lamp", 0, listOf(
            t1 to litTrue,
            t2 to litFalse,
        ))
        val rec = recording(changes = listOf(
            litChangeAt(t1, lit = true),
            litChangeAt(t2, lit = false),
        ))
        val result = OutputVerifier.verify(spec(SpecMode.UPDATE_AWARE, output), rec)
        assertTrue(result.pass, "expected pass, got $result")
    }

    @Test
    fun `UPDATE_AWARE fails on unexpected change`() {
        val t1 = SimTime(2, Phase.END_OF_TICK, 0)
        val tExtra = SimTime(3, Phase.END_OF_TICK, 0)
        val output = OutputSpec(outputPos, "lamp", 0, listOf(t1 to litTrue))
        val rec = recording(changes = listOf(
            litChangeAt(t1, lit = true),
            litChangeAt(tExtra, lit = false),
        ))
        val result = OutputVerifier.verify(spec(SpecMode.UPDATE_AWARE, output), rec)
        assertFalse(result.pass)
        assertTrue(result.checks.any { !it.pass && it.label.contains("unexpected") },
            "expected unexpected-change diagnostic, got ${result.checks}")
    }

    @Test
    fun `UPDATE_AWARE fails on missing change`() {
        val t1 = SimTime(2, Phase.END_OF_TICK, 0)
        val output = OutputSpec(outputPos, "lamp", 0, listOf(t1 to litTrue))
        val rec = recording()       // no changes
        val result = OutputVerifier.verify(spec(SpecMode.UPDATE_AWARE, output), rec)
        assertFalse(result.pass)
        assertTrue(result.checks.any { !it.pass && it.label.contains("missing") },
            "expected missing-change diagnostic, got ${result.checks}")
    }

    @Test
    fun `UPDATE_AWARE fails when SimTimes differ by phase`() {
        val expected = SimTime(2, Phase.END_OF_TICK, 0)
        val actual = SimTime(2, Phase.SCHEDULED_TICKS, 0)
        val output = OutputSpec(outputPos, "lamp", 0, listOf(expected to litTrue))
        val rec = recording(changes = listOf(litChangeAt(actual, lit = true)))
        val result = OutputVerifier.verify(spec(SpecMode.UPDATE_AWARE, output), rec)
        assertFalse(result.pass)
    }
}
