package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.dsl.StateCondition
import com.breadmoirai.redstonespecs.data.allEntries
import net.minecraft.core.BlockPos

object RecordingFinalizer {

    /**
     * Header derived from the marker entries placed by the user before recording:
     * preserves the (pos, kind, label, color) per I/O position. The first entry seen
     * for a given (pos, kind) wins for label/color.
     */
    private data class IoHeader(val pos: BlockPos, val kind: EntryKind, val label: String, val color: Int)

    /**
     * Produces a finalized [RedstoneSpec] from a recording.
     *
     * @param baseSpec spec on the BE before finalize: provides id, bounds, structure, marker positions/labels.
     *                 Marker times/conditions are ignored — they are re-derived from the recording.
     * @param recording full state recording captured by [StateRecorder] over the bounds.
     * @return new spec with derived entries and lifespan (count of ticks spanned by I/O activity, inclusive);
     *         or null if the recording contains no I/O activity.
     */
    fun finalize(baseSpec: RedstoneSpec, recording: StateRecording): RedstoneSpec? {
        val headers: List<IoHeader> = baseSpec.allEntries
            .map { IoHeader(it.pos, it.kind, it.label, it.color) }
            .distinct()
        if (headers.isEmpty()) return null

        val ioPositions: Set<BlockPos> = headers.map { it.pos }.toSet()
        val (firstTick, lastTick) = ioActivitySpan(recording, ioPositions) ?: return null
        val lifespan = (lastTick - firstTick + 1).coerceAtLeast(1)
        val view = StateRecordingView.of(recording)

        val derivedEntries = buildList {
            for (header in headers) {
                addAll(when (header.kind) {
                    EntryKind.INPUT -> deriveInputEntries(header, recording, view, firstTick, lastTick)
                    EntryKind.OUTPUT -> deriveOutputEntries(header, recording, view, firstTick, lastTick)
                })
            }
        }
        return baseSpec.copy(lifespan = lifespan, entries = derivedEntries)
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
        header: IoHeader,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
    ): List<SpecEntry> {
        val initState = view.stateAt(header.pos, SimTime(firstTick, Phase.END_OF_TICK, Int.MAX_VALUE))
        val initEntry = SpecEntry(
            header.pos, header.label, header.color, EntryKind.INPUT,
            SimTime.START,
            propsToCondition(captureBlockStateProps(initState), initState),
        )
        val laterTicks = changedTicks(recording, header.pos).filter { it in (firstTick + 1)..lastTick }
        val later = laterTicks.map { t ->
            val state = view.stateAt(header.pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            SpecEntry(
                header.pos, header.label, header.color, EntryKind.INPUT,
                SimTime(t - firstTick, Phase.END_OF_TICK),
                propsToCondition(captureBlockStateProps(state), state),
            )
        }
        return listOf(initEntry) + later
    }

    private fun deriveOutputEntries(
        header: IoHeader,
        recording: StateRecording,
        view: StateRecordingView,
        firstTick: Int,
        lastTick: Int,
    ): List<SpecEntry> {
        val ticks = changedTicks(recording, header.pos).filter { it in firstTick..lastTick }
        return ticks.map { t ->
            val state = view.stateAt(header.pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            SpecEntry(
                header.pos, header.label, header.color, EntryKind.OUTPUT,
                SimTime(t - firstTick, Phase.END_OF_TICK),
                propsToCondition(captureBlockStateProps(state), state),
            )
        }
    }

    private fun changedTicks(rec: StateRecording, pos: BlockPos): List<Int> =
        rec.changes.asSequence()
            .filter { it.pos == pos }
            .map { it.simTime.tick }
            .distinct()
            .sorted()
            .toList()
}
