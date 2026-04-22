package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class AutoSpecRecorder(
    private val autoSpec: AutoSpec,
    private val originPos: BlockPos,
    private val level: ServerLevel,
    private val specCase: SpecCase,
) {
    private var ticksElapsed = -1
    private val initStates = HashMap<BlockPos, Map<String, String>>()
    private val recordedChanges = mutableListOf<Pair<SimTime, Map<BlockPos, Map<String, String>>>>()
    private var lastStates: Map<BlockPos, Map<String, String>> = emptyMap()

    private val monitoredPositions: List<BlockPos> by lazy {
        (specCase.inputs + specCase.outputs).map { worldPos(it.pos) }
    }

    fun start() {
        LOGGER.debug("[AutoSpecRecorder#start] recording '{}' monitoring {} positions", autoSpec.label, monitoredPositions.size)
        ticksElapsed = -1
        val states = monitoredPositions.associateWith { pos -> captureBlockStateProps(level.getBlockState(pos)) }
        initStates.clear()
        initStates.putAll(states)
        lastStates = states
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        val simTime = SimTime(ticksElapsed.coerceAtLeast(0), phase)
        val currentStates = monitoredPositions.associateWith { pos -> captureBlockStateProps(level.getBlockState(pos)) }
        val changes = currentStates.mapNotNull { (pos, current) ->
            val last = lastStates[pos] ?: emptyMap()
            val diff = current.filter { (k, v) -> last[k] != v }
            if (diff.isNotEmpty()) pos to diff else null
        }.toMap()
        if (changes.isNotEmpty()) {
            LOGGER.debug("[AutoSpecRecorder#onPhase] {} detected changes at {} positions", simTime, changes.size)
            recordedChanges += simTime to changes
        }
        lastStates = currentStates
    }

    fun commit(): SpecCase {
        fun buildEntries(worldPos: BlockPos): List<Pair<SimTime, StateCondition>> {
            val blockState = level.getBlockState(worldPos)
            val initProps = initStates[worldPos] ?: emptyMap()
            val result = mutableListOf<Pair<SimTime, StateCondition>>(
                SimTime.INIT to propsToCondition(initProps, blockState)
            )
            for ((simTime, changes) in recordedChanges) {
                changes[worldPos]?.let { diff ->
                    result += simTime to propsToCondition(diff, blockState)
                }
            }
            return result
        }

        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        LOGGER.debug("[AutoSpecRecorder#commit] committing '{}' duration={}t changes={}", caseName, ticksElapsed + 1, recordedChanges.size)
        return SpecCase(
            name = caseName,
            lifespan = (ticksElapsed + 1).coerceAtLeast(1),
            inputs = specCase.inputs.map { it.copy(entries = buildEntries(worldPos(it.pos))) },
            outputs = specCase.outputs.map { it.copy(entries = buildEntries(worldPos(it.pos))) },
            breakpoints = specCase.breakpoints,
            autoSpecs = specCase.autoSpecs,
        )
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
