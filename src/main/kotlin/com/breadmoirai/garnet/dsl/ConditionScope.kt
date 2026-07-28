package com.breadmoirai.garnet.dsl

import net.minecraft.resources.Identifier

@DslMarker
annotation class SpecDslMarker

@SpecDslMarker
class ConditionScope {
    private val conditions = mutableListOf<StateCondition>()

    fun powered(value: Boolean = true) { conditions += StateCondition.BoolProperty("powered", value) }
    fun lit(value: Boolean = true)     { conditions += StateCondition.BoolProperty("lit", value) }
    fun prop(name: String, value: Boolean) { conditions += StateCondition.BoolProperty(name, value) }
    fun prop(name: String, value: String)  { conditions += StateCondition.EnumProperty(name, value) }
    fun intProp(name: String, value: Int)  { conditions += StateCondition.IntProperty(name, value) }
    fun range(name: String, range: IntRange) {
        conditions += StateCondition.IntRange(name, range.first, range.last)
    }
    fun block(id: String) { conditions += StateCondition.BlockType(Identifier.parse(id)) }
    fun containerHas(item: String? = null, slot: Int? = null, min: Int = 1) {
        conditions += StateCondition.ContainerContents(
            slot = slot,
            item = item?.let { Identifier.parse(it) },
            minCount = min,
        )
    }
    fun all(block: ConditionScope.() -> Unit) {
        conditions += StateCondition.All(ConditionScope().apply(block).build())
    }
    fun any(block: ConditionScope.() -> Unit) {
        conditions += StateCondition.Any(ConditionScope().apply(block).build())
    }
    fun not(block: ConditionScope.() -> Unit) {
        val inner = ConditionScope().apply(block).build()
        require(inner.size == 1) { "not { } must contain exactly one condition, got ${inner.size}" }
        conditions += StateCondition.Not(inner.single())
    }

    internal fun build(): List<StateCondition> = conditions.toList()

    /** Returns the single condition for `at { ... }` blocks. Wraps multiple conditions in `All`. */
    internal fun buildSingle(): StateCondition = when (conditions.size) {
        0 -> error("at { } block produced no conditions")
        1 -> conditions.single()
        else -> StateCondition.All(conditions.toList())
    }
}
