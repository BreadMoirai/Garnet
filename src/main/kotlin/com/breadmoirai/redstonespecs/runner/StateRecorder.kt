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
    private val originPos: BlockPos,
    private val boundsWorldMin: BlockPos,
    private val boundsWorldMax: BlockPos,
) {
    private var currentTick: Int = -1
    private var tickOrder: Int = 0
    var currentPhase: Phase = Phase.START_OF_TICK
        private set

    lateinit var initialSnapshot: Map<BlockPos, BlockState>
        private set

    private val _changes: MutableList<BlockStateChange> = mutableListOf()
    val changes: List<BlockStateChange> get() = _changes

    fun start(level: ServerLevel, originPos: BlockPos, bounds: BoundingBox) {
        initialSnapshot = buildMap {
            for (x in bounds.minX()..bounds.maxX()) {
                for (y in bounds.minY()..bounds.maxY()) {
                    for (z in bounds.minZ()..bounds.maxZ()) {
                        val worldPos = BlockPos(originPos.x + x, originPos.y + y, originPos.z + z)
                        put(worldToOriginRelative(worldPos), level.getBlockState(worldPos))
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

    /**
     * Converts a world position to a position relative to the recorder's origin block —
     * the same coordinate space used by [com.breadmoirai.redstonespecs.data.SpecEntry.pos].
     */
    fun worldToOriginRelative(worldPos: BlockPos): BlockPos = BlockPos(
        worldPos.x - originPos.x,
        worldPos.y - originPos.y,
        worldPos.z - originPos.z,
    )

    fun record(worldPos: BlockPos, from: BlockState, to: BlockState) {
        val localPos = worldToOriginRelative(worldPos)
        val toBlock: Identifier? = if (from.block != to.block) {
            BuiltInRegistries.BLOCK.getKey(to.block) ?: return  // unregistered block — skip silently
        } else null
        val fromProps = captureBlockStateProps(from)
        val toProps = captureBlockStateProps(to)
        val diffs = toProps.mapNotNull { (name, value) ->
            if (fromProps[name] != value) PropertyDiff(name, value) else null
        }
        if (diffs.isEmpty() && toBlock == null) return
        val simTime = SimTime(currentTick.coerceAtLeast(0), currentPhase, tickOrder++)
        _changes += BlockStateChange(localPos, simTime, toBlock, diffs)
    }

    fun toRecording(): StateRecording =
        StateRecording(specId, System.currentTimeMillis(), initialSnapshot, _changes.toList())

    companion object {
        @JvmStatic
        @field:Volatile
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

        @JvmStatic
        fun forSpec(specId: UUID, originPos: BlockPos, bounds: BoundingBox): StateRecorder {
            val minX = originPos.x + bounds.minX()
            val minY = originPos.y + bounds.minY()
            val minZ = originPos.z + bounds.minZ()
            val maxX = originPos.x + bounds.maxX()
            val maxY = originPos.y + bounds.maxY()
            val maxZ = originPos.z + bounds.maxZ()
            return StateRecorder(specId, originPos, BlockPos(minX, minY, minZ), BlockPos(maxX, maxY, maxZ))
        }
    }
}
