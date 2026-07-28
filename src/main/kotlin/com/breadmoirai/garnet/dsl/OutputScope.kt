package com.breadmoirai.garnet.dsl

import net.minecraft.core.BlockPos

/**
 * DSL scope for a single output position. `at(tick) { … }` builds a
 * [StateCondition] AST (via the same condition primitives the runner
 * uses) and schedules an assertion at `END_OF_TICK` of `tick`.
 */
@SpecDslMarker
class OutputScope internal constructor(
    private val run: SpecRun,
    private val pos: BlockPos,
    private val label: String,
    @Suppress("unused") private val color: Int,
) {
    /** Pre-run sentinel; SimTime.START. */
    fun atStart(block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime.START, block, declaredTick = null)
    }

    /** Assert at `END_OF_TICK` of [tick]. */
    fun at(tick: Int, block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime(tick, Phase.END_OF_TICK), block, declaredTick = tick)
    }

    fun at(tick: Int, phase: Phase, order: Int = 0, block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime(tick, phase, order), block, declaredTick = tick)
    }

    private fun scheduleAt(
        time: SimTime,
        block: ConditionScope.() -> Unit,
        declaredTick: Int?,
    ) {
        val condition = ConditionScope().apply(block).buildSingle()
        val absPos = run.origin.offset(pos)
        val labelOrPos = label.ifEmpty { pos.toString() }
        if (declaredTick != null) run.declareOutputTick(pos, declaredTick)

        run.scheduleAssertion(time) {
            val recordingView = checkNotNull(run.recordingView) {
                "SpecRun.recordingView is null — did you use specRunForTest() outside a scheduler-only unit test?"
            }
            val view = recordingView()
            val state = view.stateAt(absPos, anchorTime(time))
            if (!evaluateConditionOnState(condition, state)) {
                val expected = describeCondition(condition)
                val actual = describeStateForCondition(condition, state)
                run.reportFailure(
                    SpecFailure(labelOrPos, time, "expected $expected but got $actual")
                )
            }
        }
    }
}
