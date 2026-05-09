package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.dsl.StateCondition
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

/**
 * Flattens a single condition into editable [RowProp] leaves and a passthrough list
 * of compound subexpressions (Not / Any / nested All) that the editor preserves
 * unchanged. The top-level [StateCondition.All] is unwrapped; everything else
 * becomes a single leaf or a passthrough entry.
 */
fun flattenSingleCondition(
    condition: StateCondition,
    blockState: BlockState?,
): Pair<MutableList<RowProp>, List<StateCondition>> {
    val rows = mutableListOf<RowProp>()
    val passthrough = mutableListOf<StateCondition>()
    fun visit(c: StateCondition) {
        when (c) {
            is StateCondition.All -> c.conditions.forEach(::visit)
            is StateCondition.BlockType -> rows += RowProp.Block(c.blockId)
            is StateCondition.BoolProperty -> rows += RowProp.Bool(c.name, c.value)
            is StateCondition.IntProperty -> {
                val prop = blockState?.block?.stateDefinition?.getProperty(c.name) as? IntegerProperty
                val lo = prop?.possibleValues?.min() ?: 0
                val hi = prop?.possibleValues?.max() ?: 15
                rows += RowProp.ExactInt(c.name, c.value, lo, hi)
            }
            is StateCondition.IntRange -> {
                val prop = blockState?.block?.stateDefinition?.getProperty(c.name) as? IntegerProperty
                val lo = prop?.possibleValues?.min() ?: 0
                val hi = prop?.possibleValues?.max() ?: 15
                rows += RowProp.RangeInt(c.name, c.min, c.max, lo, hi)
            }
            is StateCondition.EnumProperty -> {
                @Suppress("UNCHECKED_CAST")
                val cast = blockState?.block?.stateDefinition?.getProperty(c.name) as? Property<Comparable<Any>>
                val options = cast?.possibleValues?.map { cast.getName(it) } ?: listOf(c.value)
                rows += RowProp.Enum(c.name, c.value, options)
            }
            else -> passthrough += c
        }
    }
    visit(condition)
    return rows to passthrough
}
