package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
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

internal sealed class PropState {
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

// ── EntryEditorScreen ─────────────────────────────────────────────────────

class EntryEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
    private val specMode: SpecMode,
    private val initial: Pair<SimTime, StateCondition>?,
    private val onConfirm: (SimTime, StateCondition) -> Unit,
) : Screen(Component.literal(if (initial == null) "Add Entry" else "Edit Entry")) {

    private var tickBox: IntEditBox? = null
    private var phaseButton: CycleButton<Phase>? = null
    private lateinit var propStates: List<PropState>
    private var currentTick: Int = -1
    private var currentPhase: Phase = Phase.END_OF_TICK
    private var initialized = false

    override fun isPauseScreen(): Boolean = false
    override fun isInGameUi(): Boolean = true


    override fun init() {
        super.init()

        if (!initialized) {
            // Build prop states from current world block state (only once)
            val worldPos = originPos.offset(entryRelPos)
            val blockState: BlockState? = minecraft.level?.getBlockState(worldPos)
            propStates = if (blockState != null) {
                buildPropStates(blockState, initial?.second)
            } else {
                emptyList()
            }
            currentTick = initial?.first?.tick ?: -1
            currentPhase = (initial?.first?.phase ?: Phase.END_OF_TICK)
                .takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK
            initialized = true
        } else {
            // Preserve current tick and phase from widgets before they are destroyed by resize
            currentTick = tickBox?.getIntValue() ?: currentTick
            currentPhase = phaseButton?.value ?: currentPhase
        }

        val content = LinearLayout.vertical().spacing(6)

        // Title
        content.addChild(StringWidget(title, font))
        content.addChild(SpacerElement(0, 2))

        // Tick row — only for TICK_AWARE or UPDATE_AWARE
        if (specMode == SpecMode.TICK_AWARE || specMode == SpecMode.UPDATE_AWARE) {
            val tickRow = LinearLayout.horizontal().spacing(4)
            tickRow.addChild(StringWidget(40, 20, Component.literal("Tick:"), font))

            val box = IntEditBox(font, 70, 20, -1, Int.MAX_VALUE, currentTick, onChange = {})
            tickBox = box
            tickRow.addChild(box)

            val decBtn = Button.builder(Component.literal("-")) {
                tickBox!!.setIntValue(tickBox!!.getIntValue() - 1)
            }.size(20, 20).build()
            val incBtn = Button.builder(Component.literal("+")) {
                tickBox!!.setIntValue(tickBox!!.getIntValue() + 1)
            }.size(20, 20).build()

            tickRow.addChild(decBtn)
            tickRow.addChild(incBtn)
            content.addChild(tickRow)
        }

        // Phase row — only for UPDATE_AWARE
        if (specMode == SpecMode.UPDATE_AWARE) {
            val advancedPhases = Phase.entries.filter { it != Phase.USER_INTERACTION }
            val phaseRow = LinearLayout.horizontal().spacing(8)
            phaseRow.addChild(StringWidget(50, 20, Component.literal("Phase:"), font))

            val btn = CycleButton.builder<Phase>(
                { phase -> Component.literal(phase.name) },
                currentPhase,
            ).withValues(*advancedPhases.toTypedArray())
                .displayOnlyValue()
                .create(0, 0, 140, 20, Component.empty()) { _, _ -> }
            phaseButton = btn
            phaseRow.addChild(btn)
            content.addChild(phaseRow)
        }

        // Conditions label
        content.addChild(StringWidget(Component.literal("Conditions:"), font))

        // Scrollable conditions list
        val condLayout = LinearLayout.vertical().spacing(2)
        if (propStates.isEmpty()) {
            condLayout.addChild(
                StringWidget(200, 18, Component.literal("(no block state available)"), font)
            )
        } else {
            propStates.forEach { ps ->
                condLayout.addChild(buildPropRow(ps))
            }
        }

        content.addChild(ScrollableLayout(minecraft, condLayout, 150))

        content.addChild(SpacerElement(0, 4))

        // Bottom buttons
        val bottomRow = LinearLayout.horizontal().spacing(8)
        bottomRow.addChild(
            LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Confirm")) { confirm() }
        )
        bottomRow.addChild(
            LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Cancel")) { onClose() }
        )
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildPropRow(ps: PropState): LinearLayout {
        val row = LinearLayout.horizontal().spacing(4)
        row.addChild(StringWidget(80, 20, Component.literal(ps.name), font))

        val skipLabel = "—"
        when (ps) {
            is PropState.Block -> {
                val values = listOf(skipLabel, "✓")
                val initial = if (ps.included) "✓" else skipLabel
                val btn = CycleButton.builder<String>(
                    { v ->
                        if (v == skipLabel) Component.literal(skipLabel)
                        else Component.literal(ps.blockId.path)
                    },
                    initial,
                ).withValues(*values.toTypedArray())
                    .displayOnlyValue()
                    .create(0, 0, 120, 20, Component.empty()) { _, v ->
                        ps.included = v != skipLabel
                    }
                row.addChild(btn)
            }
            is PropState.Bool -> {
                val values = listOf(skipLabel, "false", "true")
                val initial = if (ps.included) ps.value.toString() else skipLabel
                val btn = CycleButton.builder<String>(
                    { v -> Component.literal(v) },
                    initial,
                ).withValues(*values.toTypedArray())
                    .displayOnlyValue()
                    .create(0, 0, 120, 20, Component.empty()) { _, v ->
                        when (v) {
                            skipLabel -> ps.included = false
                            else -> {
                                ps.included = true
                                ps.value = v.toBooleanStrict()
                            }
                        }
                    }
                row.addChild(btn)
            }
            is PropState.Int -> {
                val allValues = (ps.min..ps.max).map { it.toString() }
                val cycleValues = listOf(skipLabel) + allValues
                val initial = if (ps.included) ps.value.toString() else skipLabel
                val btn = CycleButton.builder<String>(
                    { v -> Component.literal(v) },
                    initial,
                ).withValues(*cycleValues.toTypedArray())
                    .displayOnlyValue()
                    .create(0, 0, 120, 20, Component.empty()) { _, v ->
                        if (v == skipLabel) {
                            ps.included = false
                        } else {
                            ps.included = true
                            ps.value = v.toIntOrNull() ?: ps.value
                        }
                    }
                row.addChild(btn)
            }
            is PropState.Enum -> {
                val cycleValues = listOf(skipLabel) + ps.options
                val initial = if (ps.included) ps.value else skipLabel
                val btn = CycleButton.builder<String>(
                    { v -> Component.literal(v) },
                    initial,
                ).withValues(*cycleValues.toTypedArray())
                    .displayOnlyValue()
                    .create(0, 0, 120, 20, Component.empty()) { _, v ->
                        if (v == skipLabel) {
                            ps.included = false
                        } else {
                            ps.included = true
                            ps.value = v
                        }
                    }
                row.addChild(btn)
            }
        }

        return row
    }

    private fun confirm() {
        val rawTick = tickBox?.getIntValue() ?: -1
        val phase = phaseButton?.value ?: Phase.END_OF_TICK
        val simTime = if (rawTick < 0) SimTime.INIT else SimTime(rawTick, phase)
        val conditions = propStates.mapNotNull { it.toCondition() }
        if (conditions.isEmpty()) {
            minecraft.player?.sendSystemMessage(Component.literal("Select at least one condition"))
            return
        }
        val condition = if (conditions.size == 1) conditions[0] else StateCondition.All(conditions)
        onConfirm(simTime, condition)
        onClose()
    }
}

// ── State construction helpers ────────────────────────────────────────────

internal fun buildPropStates(state: BlockState, condition: StateCondition?): List<PropState> {
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

internal fun prePopulate(props: List<PropState>, condition: StateCondition) {
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

// ── Preview helpers (package-level, reusable by SpecEditorScreen) ─────────

internal fun previewEntry(simTime: SimTime, condition: StateCondition): String {
    val timeStr = if (simTime == SimTime.INIT) "INIT" else "t${simTime.tick} ${simTime.phase.name.take(5)}"
    val condStr = previewCondition(condition).let { if (it.length > 24) it.take(23) + "…" else it }
    return "$timeStr: $condStr"
}

internal fun previewCondition(condition: StateCondition): String = when (condition) {
    is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
    is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
    is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
    is StateCondition.BlockType -> "block=${condition.blockId.path}"
    is StateCondition.All -> condition.conditions.joinToString(",") { previewCondition(it) }
    is StateCondition.Any -> condition.conditions.joinToString("|") { previewCondition(it) }
    is StateCondition.Not -> "!${previewCondition(condition.condition)}"
    is StateCondition.ContainerContents -> "container(...)"
    is StateCondition.IntRange -> "${condition.name}∈[${condition.min},${condition.max}]"
}

internal fun flattenConditionToMap(condition: StateCondition): Map<String, String> {
    val out = mutableMapOf<String, String>()
    fun walk(c: StateCondition) {
        when (c) {
            is StateCondition.All -> c.conditions.forEach(::walk)
            is StateCondition.BoolProperty -> out[c.name] = c.value.toString()
            is StateCondition.IntProperty -> out[c.name] = c.value.toString()
            is StateCondition.EnumProperty -> out[c.name] = c.value
            else -> {}
        }
    }
    walk(condition)
    return out
}
