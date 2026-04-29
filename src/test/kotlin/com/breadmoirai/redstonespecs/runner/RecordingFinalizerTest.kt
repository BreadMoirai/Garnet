package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.state.BlockState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecordingFinalizerTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun leverBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    private fun leverState(powered: Boolean): BlockState =
        leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, powered)

    /**
     * Build a StateRecording where each entry in [togglesByTick] specifies which positions
     * had their `powered` property toggled at that tick (END_OF_TICK phase).
     * All positions start unpowered.
     */
    private fun recording(
        positions: Set<BlockPos>,
        togglesByTick: Map<Int, Set<BlockPos>>,
    ): StateRecording {
        val initial = positions.associateWith { leverState(false) }
        // Track current state to alternate
        val current = positions.associateWith { false }.toMutableMap()
        val changes = togglesByTick.entries.sortedBy { it.key }.flatMap { (tick, posSet) ->
            posSet.sortedWith(compareBy({ it.x }, { it.y }, { it.z })).mapIndexed { i, pos ->
                val newVal = !(current[pos] ?: false)
                current[pos] = newVal
                BlockStateChange(
                    pos = pos,
                    simTime = SimTime(tick, Phase.END_OF_TICK, i),
                    toBlock = null,
                    diffs = listOf(PropertyDiff("powered", newVal.toString())),
                )
            }
        }
        return StateRecording(UUID.randomUUID(), 0L, initial, changes)
    }

    private fun baseSpec(
        mode: SpecMode = SpecMode.SIMPLE,
        inputs: List<InputSpec> = emptyList(),
        outputs: List<OutputSpec> = emptyList(),
    ): RedstoneSpec = RedstoneSpec(
        id = "test",
        mode = mode,
        bounds = RedstoneSpec.DEFAULT_BOUNDS,
        lifespan = 20,
        structure = null,
        inputs = inputs,
        outputs = outputs,
        breakpoints = emptyList(),
        autoSpecs = emptyList(),
    )

    private fun input(pos: BlockPos, label: String = "in") = InputSpec(
        pos, label, 0x4488FF,
        listOf(SimTime.START to StateCondition.BoolProperty("powered", false)),
    )

    private fun output(pos: BlockPos, label: String = "out") = OutputSpec(
        pos, label, 0xFF8800,
        listOf(SimTime.START to StateCondition.BoolProperty("powered", false)),
    )

    // --- ioActivitySpan tests ---

    @Test fun `ioActivitySpan finds first and last tick with IO change`() {
        val a = BlockPos(1, 0, 0)
        val b = BlockPos(2, 0, 0)
        val rec = recording(setOf(a, b), mapOf(
            2 to setOf(a),
            5 to setOf(b),
            7 to setOf(a),
        ))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a, b))
        assertEquals(2 to 7, span)
    }

    @Test fun `ioActivitySpan returns null when no IO blocks change`() {
        val a = BlockPos(1, 0, 0)
        val other = BlockPos(9, 0, 0)
        val rec = recording(setOf(a, other), mapOf(1 to setOf(other), 3 to setOf(other)))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a))
        assertNull(span)
    }

    @Test fun `ioActivitySpan trims trailing internal-only changes from lifespan`() {
        val a = BlockPos(1, 0, 0)
        val internal = BlockPos(9, 0, 0)
        val rec = recording(setOf(a, internal), mapOf(
            3 to setOf(a),
            5 to setOf(a),
            10 to setOf(internal),
            15 to setOf(internal),
        ))
        val span = RecordingFinalizer.ioActivitySpan(rec, setOf(a))
        assertEquals(3 to 5, span)
    }

    // --- finalize tests ---

    @Test fun `finalize returns null when there are no IO markers`() {
        val rec = recording(setOf(BlockPos(0, 0, 0)), emptyMap())
        val spec = baseSpec()
        assertNull(RecordingFinalizer.finalize(spec, rec))
    }

    @Test fun `finalize returns null when no IO block ever changes`() {
        val a = BlockPos(1, 0, 0)
        val other = BlockPos(9, 0, 0)
        val rec = recording(setOf(a, other), mapOf(2 to setOf(other)))
        val spec = baseSpec(inputs = listOf(input(a)))
        assertNull(RecordingFinalizer.finalize(spec, rec))
    }

    @Test fun `finalize sets lifespan to lastTick minus firstTick`() {
        val a = BlockPos(1, 0, 0)
        val rec = recording(setOf(a), mapOf(
            3 to setOf(a),
            8 to setOf(a),
        ))
        val spec = baseSpec(inputs = listOf(input(a)))
        val finalized = RecordingFinalizer.finalize(spec, rec) ?: error("expected non-null finalized spec")
        assertEquals(5, finalized.lifespan)
    }

    @Test fun `SIMPLE mode emits START and END output entries`() {
        val a = BlockPos(1, 0, 0)
        val o = BlockPos(2, 0, 0)
        val rec = recording(setOf(a, o), mapOf(
            2 to setOf(a),       // input toggles on -> first I/O activity
            4 to setOf(o),       // output toggles on
            6 to setOf(o),       // output toggles off
            8 to setOf(o),       // output toggles on -> last I/O activity (final state: powered=true)
        ))
        val spec = baseSpec(
            mode = SpecMode.SIMPLE,
            inputs = listOf(input(a)),
            outputs = listOf(output(o)),
        )
        val finalized = RecordingFinalizer.finalize(spec, rec) ?: error("expected non-null finalized spec")
        val lifespan = finalized.lifespan // 8 - 2 = 6
        assertEquals(6, lifespan)
        val out = finalized.outputs.single()
        // SIMPLE now emits exactly two entries: START (initial state) and END (final state)
        assertEquals(2, out.entries.size)
        val (startTime, startCond) = out.entries[0]
        val (endTime, endCond) = out.entries[1]
        assertEquals(SimTime.START, startTime)
        assertEquals(SimTime.END, endTime)
        // Initial state of output before firstTick=2: output hasn't changed yet, so powered=false
        assertTrue(startCond is StateCondition.All || startCond is StateCondition.BoolProperty,
            "expected propsToCondition output for START, got $startCond")
        val startPowered = when (startCond) {
            is StateCondition.BoolProperty -> startCond.value
            is StateCondition.All -> (startCond.conditions.filterIsInstance<StateCondition.BoolProperty>()
                .firstOrNull { it.name == "powered" })?.value ?: error("no powered property in $startCond")
            else -> error("unexpected condition type $startCond")
        }
        assertEquals(false, startPowered, "START entry should reflect initial state powered=false")
        // Final state of output at last activity tick (tick 8) was powered=true
        assertTrue(endCond is StateCondition.All || endCond is StateCondition.BoolProperty,
            "expected propsToCondition output for END, got $endCond")
        val endPowered = when (endCond) {
            is StateCondition.BoolProperty -> endCond.value
            is StateCondition.All -> (endCond.conditions.filterIsInstance<StateCondition.BoolProperty>()
                .firstOrNull { it.name == "powered" })?.value ?: error("no powered property in $endCond")
            else -> error("unexpected condition type $endCond")
        }
        assertEquals(true, endPowered, "END entry should reflect final state powered=true")
    }

    @Test fun `non-SIMPLE mode derives input conditions at relative SimTimes`() {
        val a = BlockPos(1, 0, 0)
        // Input toggles at ticks 2, 5, 7. Span is (2, 7), lifespan = 5.
        // Relative ticks: 0 (was tick 2), 3 (was tick 5), 5 (was tick 7).
        val rec = recording(setOf(a), mapOf(
            2 to setOf(a),
            5 to setOf(a),
            7 to setOf(a),
        ))
        val spec = baseSpec(
            mode = SpecMode.TICK_AWARE,
            inputs = listOf(input(a)),
        )
        val finalized = RecordingFinalizer.finalize(spec, rec) ?: error("expected non-null finalized spec")
        assertEquals(5, finalized.lifespan)
        val derived = finalized.inputs.single()
        // Must contain START plus per-tick changes.
        // Tick 0 of trimmed window has a change (was tick 2): derived as START (first change becomes the init state).
        // Subsequent changes at relative ticks 3 and 5.
        val times = derived.entries.map { it.first }
        assertTrue(SimTime.START in times, "expected START entry; got $times")
        assertTrue(times.contains(SimTime(3, Phase.END_OF_TICK))
            || times.contains(SimTime(3, Phase.START_OF_TICK)),
            "expected an entry at relative tick 3; got $times")
        assertTrue(times.contains(SimTime(5, Phase.END_OF_TICK))
            || times.contains(SimTime(5, Phase.START_OF_TICK)),
            "expected an entry at relative tick 5; got $times")
    }
}
