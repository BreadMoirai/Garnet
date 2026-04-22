package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateSpec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Watches InputSpec and OutputSpec positions for an ongoing circuit activation.
 * Created on the rising edge of [autoSpec]'s condition; [commit] is called on the falling edge.
 */
class AutoSpecRecorder(
    private val autoSpec: AutoSpec,
    private val originPos: BlockPos,
    private val level: ServerLevel,
    private val specCase: SpecCase,
) {
    private var ticksElapsed = -1

    // pos → initial property snapshot (used as INIT entry)
    private val initStates = HashMap<BlockPos, Map<String, String>>()

    // ordered list of (SimTime → per-pos property diffs)
    private val recordedChanges = mutableListOf<Pair<SimTime, Map<BlockPos, Map<String, String>>>>()

    private var lastStates: Map<BlockPos, Map<String, String>> = emptyMap()

    private val monitoredPositions: List<BlockPos> by lazy {
        (specCase.inputs + specCase.outputs).map { worldPos(it.pos) }
    }

    fun start() {
        ticksElapsed = -1
        val states = monitoredPositions.associateWith { pos -> captureBlockStateProps(level.getBlockState(pos)) }
        initStates.clear()
        initStates.putAll(states)
        lastStates = states
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        val simTime = SimTime(ticksElapsed.coerceAtLeast(0), phase)

        val currentStates = monitoredPositions.associateWith { pos ->
            captureBlockStateProps(level.getBlockState(pos))
        }

        val changes = currentStates.mapNotNull { (pos, current) ->
            val last = lastStates[pos] ?: emptyMap()
            val diff = current.filter { (k, v) -> last[k] != v }
            if (diff.isNotEmpty()) pos to diff else null
        }.toMap()

        if (changes.isNotEmpty()) {
            recordedChanges += simTime to changes
        }

        lastStates = currentStates
    }

    /**
     * Builds a new [SpecCase] from the recorded state timeline and returns it.
     * The resulting case reuses the same input/output positions from [specCase] but with
     * populated [StateSpec] entries derived from the recording.
     */
    fun commit(): SpecCase {
        fun buildStateSpec(worldPos: BlockPos): StateSpec {
            val entries = mutableListOf(SimTime.INIT to (initStates[worldPos] ?: emptyMap()))
            for ((simTime, changes) in recordedChanges) {
                changes[worldPos]?.let { entries += simTime to it }
            }
            return StateSpec(entries)
        }

        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        return SpecCase(
            name = caseName,
            lifespan = (ticksElapsed + 1).coerceAtLeast(1),
            inputs = specCase.inputs.map { it.copy(stateSpec = buildStateSpec(worldPos(it.pos))) },
            outputs = specCase.outputs.map { it.copy(stateSpec = buildStateSpec(worldPos(it.pos))) },
            breakpoints = specCase.breakpoints,
            autoSpecs = specCase.autoSpecs,
        )
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
