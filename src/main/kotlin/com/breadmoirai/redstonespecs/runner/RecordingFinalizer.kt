package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

object RecordingFinalizer {

    /**
     * Produces a finalized [RedstoneSpec] from a recording.
     *
     * @param baseSpec spec on the BE before finalize: provides id, mode, bounds, structure, marker positions/labels.
     *                 Marker conditions are ignored — they are re-derived from the recording.
     * @param recording full state recording captured by [StateRecorder] over the bounds.
     * @return new spec with derived entries and trimmed lifespan; or null if the recording contains no I/O activity.
     */
    fun finalize(baseSpec: RedstoneSpec, recording: StateRecording): RedstoneSpec? {
        val ioPositions: Set<BlockPos> =
            (baseSpec.inputs.map { it.pos } + baseSpec.outputs.map { it.pos }).toSet()
        if (ioPositions.isEmpty()) return null

        val (firstTick, lastTick) = ioActivitySpan(recording, ioPositions) ?: return null
        val lifespan = (lastTick - firstTick).coerceAtLeast(0)
        val view = StateRecordingView.of(recording)

        val derivedInputs = baseSpec.inputs.map { input ->
            input.copy(entries = deriveInputEntries(input.pos, recording, view, firstTick, lastTick))
        }
        val derivedOutputs = baseSpec.outputs.map { output ->
            output.copy(entries = deriveOutputEntries(output.pos, recording, view, firstTick, lastTick, lifespan, baseSpec.mode))
        }
        return baseSpec.copy(
            lifespan = lifespan,
            inputs = derivedInputs,
            outputs = derivedOutputs,
        )
    }

    /** Returns inclusive [first, last] tick indices where any I/O block changed state, or null if none did. */
    internal fun ioActivitySpan(rec: StateRecording, io: Set<BlockPos>): Pair<Int, Int>? {
        var first = Int.MAX_VALUE
        var last = Int.MIN_VALUE
        for (change in rec.changes) {
            if (change.pos !in io) continue
            val t = change.simTime.tick
            if (t < first) first = t
            if (t > last) last = t
        }
        return if (first == Int.MAX_VALUE) null else first to last
    }

    private fun deriveInputEntries(
        pos: BlockPos,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
    ): List<Pair<SimTime, StateCondition>> {
        // INIT state == the input's settled state at the boundary of firstTick (after any changes that happen on firstTick).
        val initState = view.stateAt(pos, SimTime(firstTick, Phase.END_OF_TICK, Int.MAX_VALUE))
        val initEntry = SimTime.INIT to propsToCondition(captureBlockStateProps(initState), initState)

        val laterTicks = changedTicks(recording, pos).filter { it in (firstTick + 1)..lastTick }
        val laterEntries = laterTicks.map { t ->
            val state = view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            SimTime(t - firstTick, Phase.END_OF_TICK) to propsToCondition(captureBlockStateProps(state), state)
        }
        return listOf(initEntry) + laterEntries
    }

    private fun deriveOutputEntries(
        pos: BlockPos,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
        lifespan: Int,
        mode: SpecMode,
    ): List<Pair<SimTime, StateCondition>> {
        if (mode == SpecMode.SIMPLE) {
            val finalState = view.stateAt(pos, SimTime(lastTick, Phase.END_OF_TICK, Int.MAX_VALUE))
            return listOf(
                SimTime(lifespan, Phase.END_OF_TICK) to propsToCondition(captureBlockStateProps(finalState), finalState)
            )
        }
        val ticks = changedTicks(recording, pos).filter { it in firstTick..lastTick }
        return ticks.map { t ->
            val state = view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            SimTime(t - firstTick, Phase.END_OF_TICK) to propsToCondition(captureBlockStateProps(state), state)
        }
    }

    /** Sorted unique tick indices at which [pos] has at least one recorded change. */
    private fun changedTicks(rec: StateRecording, pos: BlockPos): List<Int> =
        rec.changes.asSequence()
            .filter { it.pos == pos }
            .map { it.simTime.tick }
            .distinct()
            .sorted()
            .toList()
}
