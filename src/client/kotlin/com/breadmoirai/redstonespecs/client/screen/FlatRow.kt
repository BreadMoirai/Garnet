package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

sealed class RowProp {
    abstract val name: String
    abstract fun toCondition(): StateCondition

    data class Block(val blockId: Identifier) : RowProp() {
        override val name = "block"
        override fun toCondition() = StateCondition.BlockType(blockId)
    }

    data class Bool(override val name: String, var value: Boolean) : RowProp() {
        override fun toCondition() = StateCondition.BoolProperty(name, value)
    }

    data class ExactInt(override val name: String, var value: Int, val min: Int, val max: Int) : RowProp() {
        override fun toCondition() = StateCondition.IntProperty(name, value)
    }

    data class RangeInt(override val name: String, var lo: Int, var hi: Int, val absMin: Int, val absMax: Int) : RowProp() {
        override fun toCondition() = StateCondition.IntRange(name, lo, hi)
    }

    data class Enum(override val name: String, var value: String, val options: List<String>) : RowProp() {
        override fun toCondition() = StateCondition.EnumProperty(name, value)
    }
}

data class FlatRow(var simTime: SimTime, var prop: RowProp)

fun flattenEntries(
    entries: List<Pair<SimTime, StateCondition>>,
    blockState: BlockState?,
): Pair<MutableList<FlatRow>, MutableList<Pair<SimTime, StateCondition>>> {
    val rows = mutableListOf<FlatRow>()
    val passthrough = mutableListOf<Pair<SimTime, StateCondition>>()
    for ((simTime, condition) in entries) {
        val leafProps = flattenCondition(condition, blockState)
        if (leafProps.isEmpty()) {
            passthrough.add(simTime to condition)
        } else {
            leafProps.forEach { rows.add(FlatRow(simTime, it)) }
        }
    }
    return rows to passthrough
}

fun flattenCondition(condition: StateCondition, blockState: BlockState?): List<RowProp> = when (condition) {
    is StateCondition.All -> condition.conditions.flatMap { flattenCondition(it, blockState) }
    is StateCondition.BlockType -> listOf(RowProp.Block(condition.blockId))
    is StateCondition.BoolProperty -> listOf(RowProp.Bool(condition.name, condition.value))
    is StateCondition.IntProperty -> {
        val prop = blockState?.block?.stateDefinition?.getProperty(condition.name) as? IntegerProperty
        val lo = prop?.possibleValues?.min() ?: 0
        val hi = prop?.possibleValues?.max() ?: 15
        listOf(RowProp.ExactInt(condition.name, condition.value, lo, hi))
    }
    is StateCondition.IntRange -> {
        val prop = blockState?.block?.stateDefinition?.getProperty(condition.name) as? IntegerProperty
        val lo = prop?.possibleValues?.min() ?: 0
        val hi = prop?.possibleValues?.max() ?: 15
        listOf(RowProp.RangeInt(condition.name, condition.min, condition.max, lo, hi))
    }
    is StateCondition.EnumProperty -> {
        @Suppress("UNCHECKED_CAST")
        val cast = blockState?.block?.stateDefinition?.getProperty(condition.name) as? Property<Comparable<Any>>
        val options = cast?.possibleValues?.map { cast.getName(it) } ?: listOf(condition.value)
        listOf(RowProp.Enum(condition.name, condition.value, options))
    }
    else -> emptyList()
}

fun reconstitute(
    rows: List<FlatRow>,
    passthrough: List<Pair<SimTime, StateCondition>>,
): List<Pair<SimTime, StateCondition>> {
    val grouped = linkedMapOf<SimTime, MutableList<StateCondition>>()
    for (row in rows) grouped.getOrPut(row.simTime) { mutableListOf() }.add(row.prop.toCondition())
    val result = grouped.map { (simTime, conditions) ->
        simTime to if (conditions.size == 1) conditions[0] else StateCondition.All(conditions)
    }.toMutableList()
    result.addAll(passthrough)
    return result
}
