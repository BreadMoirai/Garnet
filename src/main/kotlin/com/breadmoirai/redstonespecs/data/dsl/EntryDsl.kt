package com.breadmoirai.redstonespecs.data.dsl

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos

@SpecDslMarker
class EntryScope internal constructor(
    private val pos: BlockPos,
    private val label: String,
    private val color: Int,
    private val kind: EntryKind,
) {
    private val entries = mutableListOf<SpecEntry>()

    /** Initial-condition slot. Resolves to SimTime.START. */
    fun atStart(block: ConditionScope.() -> Unit) {
        addEntry(SimTime.START, ConditionScope().apply(block).buildSingle())
    }

    /** Anchor at `tick` with default phase per kind: inputs fire at START_OF_TICK, outputs check at END_OF_TICK. */
    fun at(tick: Int, block: ConditionScope.() -> Unit) {
        val phase = if (kind == EntryKind.INPUT) Phase.START_OF_TICK else Phase.END_OF_TICK
        addEntry(SimTime(tick, phase), ConditionScope().apply(block).buildSingle())
    }

    /** Explicit phase override for advanced cases. */
    fun at(tick: Int, phase: Phase, order: Int = 0, block: ConditionScope.() -> Unit) {
        addEntry(SimTime(tick, phase, order), ConditionScope().apply(block).buildSingle())
    }

    private fun addEntry(time: SimTime, condition: StateCondition) {
        entries += SpecEntry(pos, label, color, kind, time, condition)
    }

    internal fun build(): List<SpecEntry> = entries.toList()
}
