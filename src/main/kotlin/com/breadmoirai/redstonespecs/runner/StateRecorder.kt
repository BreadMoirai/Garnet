package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.BoundingBox
import java.util.UUID

class StateRecorder(
    val specId: UUID,
    private val boundsWorldMin: BlockPos,
    private val boundsWorldMax: BlockPos,
) {
    private var currentTick: Int = -1
    private var tickOrder: Int = 0
    var currentPhase: Phase = Phase.START_OF_TICK
        private set

    lateinit var initialSnapshot: Map<BlockPos, BlockState>
        private set

    val changes: MutableList<BlockStateChange> = mutableListOf()

    fun start(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        initialSnapshot = buildMap {
            for (x in bounds.minX()..bounds.maxX()) {
                for (y in bounds.minY()..bounds.maxY()) {
                    for (z in bounds.minZ()..bounds.maxZ()) {
                        val worldPos = BlockPos(originPos.x + x, originPos.y + y, originPos.z + z)
                        val localPos = worldToLocal(worldPos)
                        put(localPos, level.getBlockState(worldPos))
                    }
                }
            }
        }
    }

    fun onTickStart() {
        currentTick++
        tickOrder = 0
    }

    fun onPhaseStart(phase: Phase) {
        currentPhase = phase
    }

    fun isInBounds(worldPos: BlockPos): Boolean =
        worldPos.x in boundsWorldMin.x..boundsWorldMax.x &&
        worldPos.y in boundsWorldMin.y..boundsWorldMax.y &&
        worldPos.z in boundsWorldMin.z..boundsWorldMax.z

    fun worldToLocal(worldPos: BlockPos): BlockPos = BlockPos(
        worldPos.x - boundsWorldMin.x,
        worldPos.y - boundsWorldMin.y,
        worldPos.z - boundsWorldMin.z,
    )

    fun record(worldPos: BlockPos, from: BlockState, to: BlockState) {
        val localPos = worldToLocal(worldPos)
        val toBlock: Identifier? = if (from.block != to.block)
            BuiltInRegistries.BLOCK.getKey(to.block) else null
        val fromProps = captureBlockStateProps(from)
        val toProps = captureBlockStateProps(to)
        val diffs = toProps.mapNotNull { (name, value) ->
            if (fromProps[name] != value) PropertyDiff(name, value) else null
        }
        if (diffs.isEmpty() && toBlock == null) return
        val simTime = SimTime(currentTick.coerceAtLeast(0), currentPhase, tickOrder++)
        changes += BlockStateChange(localPos, simTime, toBlock, diffs)
    }

    fun toRecording(): StateRecording =
        StateRecording(specId, System.currentTimeMillis(), initialSnapshot, changes.toList())

    companion object {
        @JvmStatic
        var active: StateRecorder? = null
            private set

        @JvmStatic
        fun activate(recorder: StateRecorder) {
            active = recorder
        }

        @JvmStatic
        fun deactivate() {
            active = null
        }

        fun forSpec(specId: UUID, originPos: BlockPos, bounds: BoundingBox): StateRecorder {
            val minX = originPos.x + bounds.minX()
            val minY = originPos.y + bounds.minY()
            val minZ = originPos.z + bounds.minZ()
            val maxX = originPos.x + bounds.maxX()
            val maxY = originPos.y + bounds.maxY()
            val maxZ = originPos.z + bounds.maxZ()
            return StateRecorder(specId, BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
        }
    }
}
