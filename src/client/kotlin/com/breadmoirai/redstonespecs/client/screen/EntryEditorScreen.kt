package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionGroup
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

// ── PropState — mirrors PropertyRow without widget-building code ──────────

private sealed class PropState {
    abstract val name: String
    abstract var included: Boolean
    abstract fun toCondition(): StateCondition?

    class Block(val blockId: Identifier, override var included: Boolean = false) : PropState() {
        override val name = "block"
        override fun toCondition() = if (included) StateCondition.BlockType(blockId) else null
    }

    class Bool(
        override val name: String,
        override var included: Boolean,
        var value: Boolean,
    ) : PropState() {
        override fun toCondition() = if (included) StateCondition.BoolProperty(name, value) else null
    }

    class Int(
        override val name: String,
        override var included: Boolean,
        var value: kotlin.Int,
        val min: kotlin.Int,
        val max: kotlin.Int,
    ) : PropState() {
        override fun toCondition() = if (included) StateCondition.IntProperty(name, value) else null
    }

    class Enum(
        override val name: String,
        override var included: Boolean,
        var value: String,
        val options: List<String>,
    ) : PropState() {
        override fun toCondition() = if (included) StateCondition.EnumProperty(name, value) else null
    }
}

// ── Entry editor YACL screen ──────────────────────────────────────────────

fun buildEntryEditorYacl(
    originPos: BlockPos,
    entryRelPos: BlockPos,
    initial: Pair<SimTime, StateCondition>?,
    onConfirm: (SimTime, StateCondition) -> Unit,
    parent: Screen,
): Screen {
    val mc = Minecraft.getInstance()
    val worldPos = originPos.offset(entryRelPos)
    val blockState = mc.level?.getBlockState(worldPos)
        ?: return parent  // level not loaded; fall back to parent

    var currentTick: Int = initial?.first?.tick ?: -1
    var currentPhaseStr: String = (initial?.first?.phase ?: Phase.END_OF_TICK).name
    val propStates = buildPropStates(blockState, initial?.second)
    val phaseNames = Phase.entries.map { it.name }

    return YetAnotherConfigLib.createBuilder()
        .title(Component.literal(if (initial == null) "Add Entry" else "Edit Entry"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("Timing"))
                .option(
                    Option.createBuilder<Int>()
                        .name(Component.literal("Tick"))
                        .description(dev.isxander.yacl3.api.OptionDescription.of(Component.literal("-1 = INIT (before tick 0)")))
                        .binding(-1, { currentTick }, { currentTick = it })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt) }
                        .build()
                )
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Phase"))
                        .binding(Phase.END_OF_TICK.name, { currentPhaseStr }, { currentPhaseStr = it })
                        .controller { opt ->
                            DropdownStringControllerBuilder.create(opt).values(phaseNames)
                        }
                        .build()
                )
                .build()
        )
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("Conditions"))
                .apply {
                    propStates.forEach { ps -> group(buildPropGroup(ps)) }
                }
                .build()
        )
        .save {
            val phase = runCatching { Phase.valueOf(currentPhaseStr) }.getOrElse { Phase.END_OF_TICK }
            val simTime = if (currentTick < 0) SimTime.INIT else SimTime(currentTick, phase)
            val conditions = propStates.mapNotNull { it.toCondition() }
            if (conditions.isEmpty()) return@save
            val condition = if (conditions.size == 1) conditions[0] else StateCondition.All(conditions)
            onConfirm(simTime, condition)
        }
        .build()
        .generateScreen(parent)
}

private fun buildPropGroup(ps: PropState): OptionGroup {
    val includeOption = Option.createBuilder<Boolean>()
        .name(Component.literal("Include"))
        .binding(ps.included, { ps.included }, { ps.included = it })
        .controller(TickBoxControllerBuilder::create)
        .build()

    return OptionGroup.createBuilder()
        .name(Component.literal(ps.name))
        .collapsed(!ps.included)
        .option(includeOption)
        .apply {
            when (ps) {
                is PropState.Block -> {} // block type fixed — no value option
                is PropState.Bool -> option(
                    Option.createBuilder<Boolean>()
                        .name(Component.literal("Value"))
                        .binding(ps.value, { ps.value }, { ps.value = it })
                        .controller(BooleanControllerBuilder::create)
                        .build()
                )
                is PropState.Int -> option(
                    Option.createBuilder<Int>()
                        .name(Component.literal("Value"))
                        .binding(ps.value, { ps.value }, { ps.value = it })
                        .controller { opt ->
                            IntegerSliderControllerBuilder.create(opt).range(ps.min, ps.max).step(1)
                        }
                        .build()
                )
                is PropState.Enum -> option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Value"))
                        .binding(ps.value, { ps.value }, { ps.value = it })
                        .controller { opt ->
                            DropdownStringControllerBuilder.create(opt).values(ps.options)
                        }
                        .build()
                )
            }
        }
        .build()
}

private fun buildPropStates(state: BlockState, condition: StateCondition?): List<PropState> {
    val blockId = BuiltInRegistries.BLOCK.getKey(state.block)
    val props = mutableListOf<PropState>(PropState.Block(blockId))

    for (prop in state.block.stateDefinition.properties) {
        props += when (prop) {
            is BooleanProperty -> PropState.Bool(prop.name, false, state.getValue(prop))
            is IntegerProperty -> {
                val min = prop.possibleValues.minOrNull() ?: 0
                val max = prop.possibleValues.maxOrNull() ?: 15
                PropState.Int(prop.name, false, state.getValue(prop), min, max)
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val cast = prop as Property<Comparable<Any>>
                PropState.Enum(
                    name = prop.name,
                    included = false,
                    value = cast.getName(state.getValue(prop)),
                    options = prop.possibleValues.map { cast.getName(it) },
                )
            }
        }
    }

    if (condition != null) prePopulate(props, condition)
    return props
}

private fun prePopulate(props: List<PropState>, condition: StateCondition) {
    when (condition) {
        is StateCondition.All -> condition.conditions.forEach { prePopulate(props, it) }
        is StateCondition.BlockType ->
            props.filterIsInstance<PropState.Block>().firstOrNull()?.included = true
        is StateCondition.BoolProperty ->
            props.filterIsInstance<PropState.Bool>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        is StateCondition.IntProperty ->
            props.filterIsInstance<PropState.Int>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        is StateCondition.EnumProperty ->
            props.filterIsInstance<PropState.Enum>().firstOrNull { it.name == condition.name }
                ?.also { it.included = true; it.value = condition.value }
        else -> {}
    }
}
