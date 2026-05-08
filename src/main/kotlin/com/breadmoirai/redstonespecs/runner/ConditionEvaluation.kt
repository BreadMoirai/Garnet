package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty

/**
 * Evaluates a [StateCondition] purely against a [BlockState] snapshot (no world access).
 * Used by [OutputVerifier] and [com.breadmoirai.redstonespecs.testing.runner.assertOutputsMatch].
 */
fun evaluateConditionOnState(condition: StateCondition, state: BlockState): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state)
    is StateCondition.BlockType -> {
        val actualId = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block)
        actualId == condition.blockId
    }
    is StateCondition.BoolProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name)
            as? BooleanProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.IntProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name)
            as? IntegerProperty ?: return false
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
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
    else -> "(complex)"
}
