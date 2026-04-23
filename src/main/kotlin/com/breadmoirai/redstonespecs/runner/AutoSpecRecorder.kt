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
import org.slf4j.LoggerFactory

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

class AutoSpecRecorder(
    private val autoSpec: AutoSpec,
    private val originPos: BlockPos,
    private val level: ServerLevel,
    private val specCase: SpecCase,
) {
    private var ticksElapsed = -1

    fun start() {
        LOGGER.debug("[AutoSpecRecorder#start] recording '{}' monitoring {} positions",
            autoSpec.label, (specCase.inputs + specCase.outputs).size)
        ticksElapsed = -1
    }

    fun onPhase(phase: Phase) {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
    }

    fun commit(view: StateRecordingView?, boundsWorldMin: BlockPos?): SpecCase {
        val caseName = autoSpec.label.ifEmpty { "auto_${ticksElapsed + 1}t_${System.currentTimeMillis() % 10000}" }
        if (view == null) LOGGER.warn("[AutoSpecRecorder#commit] no recording view available for '{}', entries will be empty", caseName)
        val standardMode = SharedSettings.devLevel == DevLevel.STANDARD

        fun buildEntries(worldPos: BlockPos, isInput: Boolean): List<Pair<SimTime, StateCondition>> {
            if (view == null || boundsWorldMin == null) return emptyList()
            val localPos = BlockPos(
                worldPos.x - boundsWorldMin.x,
                worldPos.y - boundsWorldMin.y,
                worldPos.z - boundsWorldMin.z,
            )
            val initBlockState = view.initialSnapshot[localPos] ?: level.getBlockState(worldPos)
            val initProps = captureBlockStateProps(initBlockState)
            val result = mutableListOf<Pair<SimTime, StateCondition>>(
                SimTime.INIT to propsToCondition(initProps, initBlockState)
            )
            for (change in view.changesAt(localPos)) {
                val stateAtChange = view.stateAt(localPos, change.simTime)
                val effectiveTime = if (standardMode && !isInput)
                    change.simTime.copy(phase = Phase.END_OF_TICK) else change.simTime
                val diffMap = change.diffs.associate { it.name to it.to }
                result += effectiveTime to propsToCondition(diffMap, stateAtChange)
            }
            return result
        }

        LOGGER.debug("[AutoSpecRecorder#commit] committing '{}' duration={}t", caseName, ticksElapsed + 1)
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
