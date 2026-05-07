package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

fun evaluateCondition(condition: StateCondition, level: Level, worldPos: BlockPos): Boolean {
    val state = level.getBlockState(worldPos)
    return evaluateConditionOnState(condition, state, worldPos)
}

fun evaluateCondition(
    condition: StateCondition,
    view: StateRecordingView,
    localPos: BlockPos,
    simTime: SimTime,
): Boolean {
    val state = view.stateAt(localPos, simTime)
    return evaluateConditionOnState(condition, state, localPos)
}

private fun evaluateConditionOnState(condition: StateCondition, state: BlockState, worldPos: BlockPos): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state, worldPos) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state, worldPos) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state, worldPos)
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
    is StateCondition.ContainerContents -> false
    is StateCondition.IntRange -> false
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
