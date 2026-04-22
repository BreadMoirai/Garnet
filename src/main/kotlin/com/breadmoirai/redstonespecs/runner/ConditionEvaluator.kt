package com.breadmoirai.redstonespecs.runner

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
    return evaluateConditionOnState(condition, state, level, worldPos)
}

private fun evaluateConditionOnState(condition: StateCondition, state: BlockState, level: Level, worldPos: BlockPos): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state, level, worldPos) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state, level, worldPos) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state, level, worldPos)
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

/** Converts a Map<String,String> property diff (from captureBlockStateProps) to a typed StateCondition. */
fun propsToCondition(props: Map<String, String>, state: BlockState): StateCondition {
    val conditions = props.map { (name, value) ->
        when (state.block.stateDefinition.getProperty(name)) {
            is BooleanProperty -> StateCondition.BoolProperty(name, value.toBoolean())
            is IntegerProperty -> StateCondition.IntProperty(name, value.toInt())
            else -> StateCondition.EnumProperty(name, value)
        }
    }
    return when (conditions.size) {
        0 -> StateCondition.All(emptyList())
        1 -> conditions[0]
        else -> StateCondition.All(conditions)
    }
}
