package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
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

    fun start(level: ServerLevel, originPos: BlockPos, bounds: Vec3i) {
        initialSnapshot = buildMap {
            for (x in 0 until bounds.x) {
                for (y in 0 until bounds.y) {
                    for (z in 0 until bounds.z) {
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
        // Multiple recorders may be active at once (e.g. concurrent gametests, or a
        // user-triggered recording on one BE while another BE's spec is replaying).
        // The mixin dispatches each setBlock to every recorder whose bounds contain
        // the position; SpecRunnerCoordinator advances tick state on every recorder
        // each phase. ConcurrentHashMap-backed set is safe for the rare case of
        // activate/deactivate happening from a different thread than iteration.
        private val activeRecorders: MutableSet<StateRecorder> =
            java.util.concurrent.ConcurrentHashMap.newKeySet()

        @JvmStatic
        fun activeRecorders(): Set<StateRecorder> = activeRecorders

        @JvmStatic
        fun activate(recorder: StateRecorder) {
            activeRecorders.add(recorder)
        }

        @JvmStatic
        fun deactivate(recorder: StateRecorder) {
            activeRecorders.remove(recorder)
        }

        @JvmStatic
        fun forSpec(specId: UUID, originPos: BlockPos, bounds: Vec3i): StateRecorder {
            val min = BlockPos(originPos.x, originPos.y, originPos.z)
            val max = BlockPos(
                originPos.x + bounds.x - 1,
                originPos.y + bounds.y - 1,
                originPos.z + bounds.z - 1,
            )
            return StateRecorder(specId, originPos, min, max)
        }
    }
}
