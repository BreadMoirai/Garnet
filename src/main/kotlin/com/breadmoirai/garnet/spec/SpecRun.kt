package com.breadmoirai.garnet.spec

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.TreeMap

/** Failure record collected by output assertions during execution. */
data class SpecFailure(val label: String, val time: SimTime, val message: String) {
    fun render(): String = "FAIL $label at tick ${time.tick}: $message"
}

/**
 * Execution context for a single [GarnetSpec.block] invocation.
 *
 * The user's lambda runs **once** before the tick loop; calls to [input] and
 * [output] register tick-keyed callbacks into [inputActions] / [assertions].
 * The runner then dispatches those callbacks at the matching `SimTime`.
 *
 * `level` and `origin` are intentionally `internal` — the DSL methods only
 * need to register callbacks; per-tick application happens through the
 * runner, which has the same references.
 *
 * ### Nullability of `level` / `recordingView`
 * Both fields are nullable so that [specRunForTest] can construct a
 * scheduler-only instance without a real [ServerLevel]. The runner always
 * passes non-null values; callbacks that access these fields will
 * `checkNotNull` at the call site (in [InputScope] / [OutputScope]) if ever
 * invoked without a level — making accidental level access loudly obvious
 * rather than silently incorrect.  This is a minimal internal change: all
 * public API and runner call-sites are unaffected.
 */
@SpecDslMarker
class SpecRun internal constructor(
    internal val level: ServerLevel?,
    internal val origin: BlockPos,
    internal val recordingView: (() -> StateRecordingViewLike)?,
) {
    internal val inputActions: TreeMap<SimTime, MutableList<() -> Unit>> = TreeMap()
    internal val assertions: TreeMap<SimTime, MutableList<() -> Unit>> = TreeMap()
    internal val outputDeclaredTicks: MutableMap<BlockPos, MutableSet<Int>> = mutableMapOf()
    internal val failures: MutableList<SpecFailure> = mutableListOf()

    fun input(
        x: Int, y: Int, z: Int,
        label: String = "",
        color: Int = -1,
        block: InputScope.() -> Unit,
    ) {
        InputScope(this, BlockPos(x, y, z), label, color).block()
    }

    fun output(
        x: Int, y: Int, z: Int,
        label: String = "",
        color: Int = -1,
        block: OutputScope.() -> Unit,
    ) {
        OutputScope(this, BlockPos(x, y, z), label, color).block()
    }

    internal fun scheduleInput(time: SimTime, action: () -> Unit) {
        inputActions.getOrPut(time) { mutableListOf() }.add(action)
    }

    internal fun scheduleAssertion(time: SimTime, action: () -> Unit) {
        assertions.getOrPut(time) { mutableListOf() }.add(action)
    }

    internal fun declareOutputTick(pos: BlockPos, tick: Int) {
        outputDeclaredTicks.getOrPut(pos) { mutableSetOf() }.add(tick)
    }

    internal fun reportFailure(failure: SpecFailure) {
        failures.add(failure)
    }
}

/**
 * Test-only factory that constructs a [SpecRun] without a real [ServerLevel].
 *
 * The returned instance is suitable for unit tests that exercise only the
 * scheduler bookkeeping (populating [SpecRun.inputActions] /
 * [SpecRun.assertions]). Any attempt to actually *invoke* a registered
 * callback — which would touch [level] or [recordingView] — will hit a
 * `checkNotNull` in [InputScope] / [OutputScope] and throw immediately,
 * making accidental level access obvious.
 */
internal fun specRunForTest(): SpecRun = SpecRun(
    level = null,
    origin = BlockPos.ZERO,
    recordingView = null,
)

/**
 * Trim adapter so [SpecRun] doesn't take a hard dependency on the runner
 * package. The runner provides a real implementation (over `StateRecorder`'s
 * live buffer) when invoking the spec.
 */
interface StateRecordingViewLike {
    fun stateAt(pos: BlockPos, time: SimTime): net.minecraft.world.level.block.state.BlockState
    fun initialAt(pos: BlockPos): net.minecraft.world.level.block.state.BlockState
}
