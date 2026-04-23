package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

class StateRecordingView(
    val initialSnapshot: Map<BlockPos, BlockState>,
    private val changes: List<BlockStateChange>,
) {
    fun stateAt(pos: BlockPos, simTime: SimTime): BlockState {
        var state = initialSnapshot[pos] ?: error("Position $pos not in recording bounds")
        for (change in changes) {
            if (change.pos != pos) continue
            if (change.simTime > simTime) break
            if (change.toBlock != null) {
                state = BuiltInRegistries.BLOCK.getValue(change.toBlock).defaultBlockState()
            }
            for (diff in change.diffs) {
                val property = state.block.stateDefinition.getProperty(diff.name) ?: continue
                @Suppress("UNCHECKED_CAST")
                state = applyPropertyFromString(state, property as Property<Comparable<Any>>, diff.to)
            }
        }
        return state
    }

    fun changesAt(pos: BlockPos): List<BlockStateChange> =
        changes.filter { it.pos == pos }

    fun changesInPhase(tick: Int, phase: Phase): List<BlockStateChange> =
        changes.filter { it.simTime.tick == tick && it.simTime.phase == phase }

    fun changesAt(pos: BlockPos, tick: Int, phase: Phase): List<BlockStateChange> =
        changes.filter { it.pos == pos && it.simTime.tick == tick && it.simTime.phase == phase }

    companion object {
        fun of(recording: StateRecording) =
            StateRecordingView(recording.initialSnapshot, recording.changes)
    }
}

private fun <T : Comparable<T>> applyPropertyFromString(
    state: BlockState,
    property: Property<T>,
    value: String,
): BlockState = property.getValue(value).map { state.setValue(property, it) }.orElse(state)
