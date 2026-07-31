package com.breadmoirai.garnet.spec

import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * DSL scope for a single input position. `at(tick) { … }` schedules a state
 * application at `START_OF_TICK` of `tick`. Inputs are *direct setters*: each
 * verb produces a [BlockState] (or transformer) without going through the
 * condition AST.
 */
@SpecDslMarker
class InputScope internal constructor(
    private val run: SpecRun,
    private val pos: BlockPos,
    private val label: String,
    @Suppress("unused") private val color: Int,
) {
    /** Initial-condition slot. Applies before tick 0. */
    fun atStart(block: InputAction.() -> Unit) {
        scheduleAt(SimTime.START, block)
    }

    /** Apply at `START_OF_TICK` of [tick]. */
    fun at(tick: Int, block: InputAction.() -> Unit) {
        scheduleAt(SimTime(tick, Phase.START_OF_TICK), block)
    }

    /** Explicit phase override. */
    fun at(tick: Int, phase: Phase, order: Int = 0, block: InputAction.() -> Unit) {
        scheduleAt(SimTime(tick, phase, order), block)
    }

    private fun scheduleAt(time: SimTime, block: InputAction.() -> Unit) {
        val absPos = run.origin.offset(pos)
        run.scheduleInput(time) {
            val level = checkNotNull(run.level) {
                "SpecRun.level is null — did you use specRunForTest() outside a scheduler-only unit test?"
            }
            val current = level.getBlockState(absPos)
            val target = InputAction(current).apply(block).resolve()
            tryApplyAsPlayerInteraction(level, absPos, current, target)
        }
    }
}

/**
 * Builder for one tick's input action; verbs progressively transform a
 * [BlockState] starting from `current`. Final state is applied via the
 * existing player-interaction dispatch in [tryApplyAsPlayerInteraction].
 */
@SpecDslMarker
class InputAction internal constructor(private var state: BlockState) {

    fun setBlock(replacement: BlockState) { state = replacement }

    fun setPowered(value: Boolean) {
        val prop = state.block.stateDefinition.getProperty("powered")
            ?: error("Block ${state.block} has no `powered` property")
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Boolean>, value)
    }

    fun setLit(value: Boolean) {
        val prop = state.block.stateDefinition.getProperty("lit")
            ?: error("Block ${state.block} has no `lit` property")
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Boolean>, value)
    }

    fun <T : Comparable<T>> setProp(name: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        val prop = state.block.stateDefinition.getProperty(name) as? Property<T>
            ?: error("Block ${state.block} has no property `$name` (or wrong value type)")
        state = state.setValue(prop, value)
    }

    fun setProp(name: String, value: String) {
        val prop = state.block.stateDefinition.getProperty(name)
            ?: error("Block ${state.block} has no property `$name`")
        val parsed = prop.getValue(value).orElseThrow {
            IllegalArgumentException("Invalid value `$value` for property `$name` on ${state.block}")
        }
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Comparable<Any>>, parsed as Comparable<Any>)
    }

    internal fun resolve(): BlockState = state
}
