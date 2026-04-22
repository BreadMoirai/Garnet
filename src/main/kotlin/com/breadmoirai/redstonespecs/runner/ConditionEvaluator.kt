package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.core.BlockPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

fun evaluateCondition(condition: StateCondition, level: Level, worldPos: BlockPos): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateCondition(it, level, worldPos) }
    is StateCondition.Any -> condition.conditions.any { evaluateCondition(it, level, worldPos) }
    is StateCondition.Not -> !evaluateCondition(condition.condition, level, worldPos)
    is StateCondition.BlockState -> {
        val state = level.getBlockState(worldPos)
        condition.properties.all { (name, expected) -> blockStatePropertyStr(state, name) == expected }
    }
    is StateCondition.ContainerContents -> false // TODO milestone later
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
