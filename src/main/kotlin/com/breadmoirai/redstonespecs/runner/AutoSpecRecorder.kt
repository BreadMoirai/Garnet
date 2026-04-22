package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.config.DevLevel
import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecCase
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
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
    private val initBlockStates = HashMap<BlockPos, BlockState>()
    private val recordedChanges = mutableListOf<Pair<SimTime, Map<BlockPos, Pair<BlockState, Map<String, String>>>>>()
    private var lastStates: Map<BlockPos, Map<String, String>> = emptyMap()

    private val monitoredPositions: List<BlockPos> by lazy {
        (specCase.inputs + specCase.outputs).map { worldPos(it.pos) }
    }

    fun start() {
        LOGGER.debug("[AutoSpecRecorder#start] recording '{}' monitoring {} positions", autoSpec.label, monitoredPositions.size)
        ticksElapsed = -1
        val blockStates = monitoredPositions.associateWith { pos -> level.getBlockState(pos) }
        initBlockStates.clear()
        initBlockStates.putAll(blockStates)
        val states = blockStates.mapValues { (_, state) -> captureBlockStateProps(state) }
        initStates.clear()
        initStates.putAll(states)
        lastStates = states
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        val simTime = SimTime(ticksElapsed.coerceAtLeast(0), phase)
        val currentBlockStates = monitoredPositions.associateWith { pos -> level.getBlockState(pos) }
        val currentStates = currentBlockStates.mapValues { (_, state) -> captureBlockStateProps(state) }
        val changes = currentStates.mapNotNull { (pos, current) ->
            val last = lastStates[pos] ?: emptyMap()
            val diff = current.filter { (k, v) -> last[k] != v }
            if (diff.isNotEmpty()) pos to (currentBlockStates.getValue(pos) to diff) else null
        }.toMap()
        if (changes.isNotEmpty()) {
            LOGGER.debug("[AutoSpecRecorder#onPhase] {} detected changes at {} positions", simTime, changes.size)
            recordedChanges += simTime to changes
        }
        lastStates = currentStates
    }

    fun commit(): SpecCase {
        val standardMode = SharedSettings.devLevel == DevLevel.STANDARD

        fun buildEntries(worldPos: BlockPos, isInput: Boolean): List<Pair<SimTime, StateCondition>> {
            val initBlockState = initBlockStates[worldPos] ?: level.getBlockState(worldPos)
            val initProps = initStates[worldPos] ?: emptyMap()
            val result = mutableListOf<Pair<SimTime, StateCondition>>(
                SimTime.INIT to propsToCondition(initProps, initBlockState)
            )
            for ((simTime, changes) in recordedChanges) {
                changes[worldPos]?.let { (stateAtChange, diff) ->
                    val effectiveTime = if (standardMode && !isInput)
                        simTime.copy(phase = Phase.END_OF_TICK) else simTime
                    result += effectiveTime to propsToCondition(diff, stateAtChange)
                }
            }
            return result
        }

        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        LOGGER.debug("[AutoSpecRecorder#commit] committing '{}' duration={}t changes={}", caseName, ticksElapsed + 1, recordedChanges.size)
        return SpecCase(
            name = caseName,
            lifespan = (ticksElapsed + 1).coerceAtLeast(1),
            inputs = specCase.inputs.map { it.copy(entries = buildEntries(worldPos(it.pos), isInput = true)) },
            outputs = specCase.outputs.map { it.copy(entries = buildEntries(worldPos(it.pos), isInput = false)) },
            breakpoints = specCase.breakpoints,
            autoSpecs = specCase.autoSpecs,
        )
    }

    private fun worldPos(relPos: BlockPos) =
        BlockPos(originPos.x + relPos.x, originPos.y + relPos.y, originPos.z + relPos.z)
}
