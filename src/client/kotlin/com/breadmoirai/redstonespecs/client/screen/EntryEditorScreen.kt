package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import dev.isxander.yacl3.api.ConfigCategory
import dev.isxander.yacl3.api.Option
import dev.isxander.yacl3.api.OptionDescription
import dev.isxander.yacl3.api.YetAnotherConfigLib
import dev.isxander.yacl3.api.controller.CyclingListControllerBuilder
import dev.isxander.yacl3.api.controller.DropdownStringControllerBuilder
import dev.isxander.yacl3.api.controller.IntegerFieldControllerBuilder
import net.minecraft.ChatFormatting
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
        ?: return parent

    var currentTick: Int = initial?.first?.tick ?: -1
    var currentPhaseStr: String = when {
        initial != null -> initial.first.phase.name
        else -> Phase.END_OF_TICK.name
    }
    val propStates = buildPropStates(blockState, initial?.second)
    val advancedPhaseNames = Phase.entries.filter { it != Phase.USER_INTERACTION }.map { it.name }

    return YetAnotherConfigLib.createBuilder()
        .title(Component.literal(if (initial == null) "Add Entry" else "Edit Entry"))
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("Timing"))
                .option(
                    Option.createBuilder<Int>()
                        .name(Component.literal("Tick"))
                        .description(OptionDescription.of(Component.literal("-1 = INIT (before tick 0)")))
                        .binding(-1, { currentTick }, { currentTick = it })
                        .controller { opt -> IntegerFieldControllerBuilder.create(opt) }
                        .build()
                )
                .option(
                    Option.createBuilder<String>()
                        .name(Component.literal("Phase"))
                        .binding(Phase.END_OF_TICK.name, { currentPhaseStr }, { currentPhaseStr = it })
                        .controller { opt ->
                            DropdownStringControllerBuilder.create(opt).values(advancedPhaseNames)
                        }
                        .build()
                )
                .build()
        )
        .category(
            ConfigCategory.createBuilder()
                .name(Component.literal("Conditions"))
                .apply {
                    propStates.forEach { ps -> option(buildPropOption(ps)) }
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

// ── Per-property option builders — each option row combines include + value ──

private const val SKIP = "—"

private fun buildPropOption(ps: PropState): Option<*> = when (ps) {
    is PropState.Block -> buildBlockOption(ps)
    is PropState.Bool -> buildBoolOption(ps)
    is PropState.Int -> buildIntOption(ps)
    is PropState.Enum -> buildEnumOption(ps)
}

private fun buildBlockOption(ps: PropState.Block): Option<String> =
    Option.createBuilder<String>()
        .name(Component.literal(ps.name))
        .binding(
            SKIP,
            { if (ps.included) "✓" else SKIP },
            { v -> ps.included = v != SKIP }
        )
        .controller { opt ->
            CyclingListControllerBuilder.create(opt)
                .values(SKIP, "✓")
                .formatValue { v ->
                    if (v == SKIP) Component.literal(SKIP).withStyle(ChatFormatting.DARK_GRAY)
                    else Component.literal(ps.blockId.path)
                }
        }
        .build()

private fun buildBoolOption(ps: PropState.Bool): Option<String> =
    Option.createBuilder<String>()
        .name(Component.literal(ps.name))
        .binding(
            SKIP,
            { if (ps.included) ps.value.toString() else SKIP },
            { v ->
                when (v) {
                    SKIP -> ps.included = false
                    else -> { ps.included = true; ps.value = v.toBooleanStrict() }
                }
            }
        )
        .controller { opt ->
            CyclingListControllerBuilder.create(opt)
                .values(SKIP, "false", "true")
                .formatValue { v ->
                    if (v == SKIP) Component.literal(SKIP).withStyle(ChatFormatting.DARK_GRAY)
                    else Component.literal(v)
                }
        }
        .build()

private fun buildIntOption(ps: PropState.Int): Option<Int> {
    val sentinel = ps.min - 1
    return Option.createBuilder<Int>()
        .name(Component.literal(ps.name))
        .binding(
            sentinel,
            { if (ps.included) ps.value else sentinel },
            { v ->
                if (v == sentinel) ps.included = false
                else { ps.included = true; ps.value = v }
            }
        )
        .controller { opt ->
            CyclingListControllerBuilder.create(opt)
                .values((sentinel..ps.max).toList())
                .formatValue { v ->
                    if (v == sentinel) Component.literal(SKIP).withStyle(ChatFormatting.DARK_GRAY)
                    else Component.literal(v.toString())
                }
        }
        .build()
}

private fun buildEnumOption(ps: PropState.Enum): Option<String> =
    Option.createBuilder<String>()
        .name(Component.literal(ps.name))
        .binding(
            SKIP,
            { if (ps.included) ps.value else SKIP },
            { v ->
                if (v == SKIP) ps.included = false
                else { ps.included = true; ps.value = v }
            }
        )
        .controller { opt ->
            DropdownStringControllerBuilder.create(opt).values(listOf(SKIP) + ps.options)
        }
        .build()

// ── State construction helpers ────────────────────────────────────────────

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
