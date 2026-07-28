package com.breadmoirai.garnet.dsl

import com.breadmoirai.garnet.runner.StateRecordingView
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

fun evaluateCondition(condition: StateCondition, level: Level, worldPos: BlockPos): Boolean {
    val state = level.getBlockState(worldPos)
    return evaluateConditionOnState(condition, state)
}

fun evaluateCondition(
    condition: StateCondition,
    view: StateRecordingView,
    localPos: BlockPos,
    simTime: SimTime,
): Boolean {
    val state = view.stateAt(localPos, simTime)
    return evaluateConditionOnState(condition, state)
}

/**
 * Evaluates a [StateCondition] purely against a [BlockState] snapshot (no world access).
 * Used by [com.breadmoirai.garnet.testing.runner.assertOutputsMatch].
 */
fun evaluateConditionOnState(condition: StateCondition, state: BlockState): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state)
    is StateCondition.BlockType -> {
        val actualId = BuiltInRegistries.BLOCK.getKey(state.block) ?: return false
        actualId == condition.blockId
    }
    is StateCondition.BoolProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.IntProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) == condition.value
    is StateCondition.ContainerContents,
    is StateCondition.IntRange -> false
}

/**
 * Returns a human-readable description of what a [StateCondition] expects.
 * Used in [TickCheck] "expected" field.
 */
fun describeCondition(condition: StateCondition): String = when (condition) {
    is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
    is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
    is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
    is StateCondition.BlockType -> "block=${condition.blockId}"
    is StateCondition.All -> condition.conditions.joinToString(",") { describeCondition(it) }
    is StateCondition.Any -> condition.conditions.joinToString("|") { describeCondition(it) }
    is StateCondition.Not -> "!${describeCondition(condition.condition)}"
    is StateCondition.ContainerContents -> "container"
    is StateCondition.IntRange -> "${condition.name}=${condition.min}..${condition.max}"
}

/**
 * Returns a human-readable description of the relevant portion of [state] for the given [condition].
 * Used in [TickCheck] "actual" field.
 */
fun describeStateForCondition(condition: StateCondition, state: BlockState): String = when (condition) {
    is StateCondition.BoolProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.IntProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.BlockType ->
        BuiltInRegistries.BLOCK.getKey(state.block).toString()
    else -> "(complex)"
}

fun blockStatePropertyStr(state: BlockState, propName: String): String? {
    val property = state.block.stateDefinition.getProperty(propName) ?: return null
    if (!state.hasProperty(property)) return null
    @Suppress("UNCHECKED_CAST")
    return readPropertyStr(state, property as Property<Comparable<Any>>)
}

fun <T : Comparable<T>> readPropertyStr(state: BlockState, property: Property<T>): String =
    property.getName(state.getValue(property))

fun captureBlockStateProps(state: BlockState): Map<String, String> =
    state.block.stateDefinition.properties.associate { prop ->
        @Suppress("UNCHECKED_CAST")
        prop.name to readPropertyStr(state, prop as Property<Comparable<Any>>)
    }

/**
 * Converts a captured block state into a typed [StateCondition]: the block's identifier
 * plus any of its properties listed in [props]. Block type is always included so that
 * blocks without properties (e.g. air, blue_concrete) still produce a meaningful check.
 */
fun propsToCondition(props: Map<String, String>, state: BlockState): StateCondition {
    val blockType = StateCondition.BlockType(BuiltInRegistries.BLOCK.getKey(state.block))
    val propConditions = props.map { (name, value) ->
        when (state.block.stateDefinition.getProperty(name)) {
            is BooleanProperty -> StateCondition.BoolProperty(name, value.toBoolean())
            is IntegerProperty -> StateCondition.IntProperty(name, value.toInt())
            else -> StateCondition.EnumProperty(name, value)
        }
    }
    val all = listOf<StateCondition>(blockType) + propConditions
    return if (all.size == 1) all[0] else StateCondition.All(all)
}

internal fun <T : Comparable<T>> applyPropertyFromString(
    state: BlockState,
    property: Property<T>,
    value: String,
): BlockState = property.getValue(value).map { state.setValue(property, it) }.orElse(state)

/** Resolves a SpecEntry time to the SimTime used for stateAt lookups (tail of the tick by default). */
internal fun anchorTime(time: SimTime): SimTime =
    if (time.order == 0 && time.phase == Phase.END_OF_TICK)
        SimTime(time.tick, Phase.END_OF_TICK, Int.MAX_VALUE)
    else time
