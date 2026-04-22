package com.breadmoirai.redstonespecs.client.widget

import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

// ---------------------------------------------------------------------------
// Layout constants
// ---------------------------------------------------------------------------
const val ROW_H = 18
const val CHECK_W = 18
const val LABEL_W = 80
const val VALUE_X_OFFSET = CHECK_W + LABEL_W + 4

// ---------------------------------------------------------------------------
// Sealed PropertyRow hierarchy
// ---------------------------------------------------------------------------

/**
 * One row in the block-state form. [addWidgetsTo] creates the interactive widgets
 * for this row and passes them to [addWidget] — the caller (a Screen subclass) is
 * responsible for registering each widget via `addRenderableWidget`.
 */
sealed class PropertyRow {
    abstract val name: String
    abstract var included: Boolean

    abstract fun currentCondition(): StateCondition

    /**
     * Creates all interactive widgets for this row and hands each one to [addWidget].
     * The caller must forward them to `Screen.addRenderableWidget`.
     */
    abstract fun addWidgetsTo(font: Font, rowX: Int, rowY: Int, addWidget: (AbstractWidget) -> Unit)

    protected fun buildCheckbox(rowX: Int, rowY: Int): Button {
        var btn: Button? = null
        btn = Button.builder(Component.literal(if (included) "✓" else " ")) {
            included = !included
            btn?.setMessage(Component.literal(if (included) "✓" else " "))
        }.bounds(rowX, rowY, CHECK_W, ROW_H).build()
        return btn
    }

    // ------------------------------------------------------------------
    // BlockTypeRow — read-only block identity row
    // ------------------------------------------------------------------
    class BlockTypeRow(
        val blockId: Identifier,
        override var included: Boolean = false,
    ) : PropertyRow() {
        override val name: String = "block"

        override fun currentCondition(): StateCondition = StateCondition.BlockType(blockId)

        override fun addWidgetsTo(font: Font, rowX: Int, rowY: Int, addWidget: (AbstractWidget) -> Unit) {
            addWidget(buildCheckbox(rowX, rowY))
            // No value widget — block ID is fixed/read-only
        }
    }

    // ------------------------------------------------------------------
    // BoolRow — boolean property row
    // ------------------------------------------------------------------
    class BoolRow(
        override val name: String,
        var value: Boolean,
        override var included: Boolean = false,
    ) : PropertyRow() {
        override fun currentCondition(): StateCondition = StateCondition.BoolProperty(name, value)

        override fun addWidgetsTo(font: Font, rowX: Int, rowY: Int, addWidget: (AbstractWidget) -> Unit) {
            addWidget(buildCheckbox(rowX, rowY))
            val valueX = rowX + VALUE_X_OFFSET
            addWidget(
                CycleButton.builder<Boolean>(
                    { b -> Component.literal(b.toString()) },
                    value,
                )
                    .withValues(false, true)
                    .create(valueX, rowY, 60, ROW_H, Component.empty()) { _, v ->
                        value = v
                    }
            )
        }
    }

    // ------------------------------------------------------------------
    // IntRow — integer property row with − / EditBox / + controls
    // ------------------------------------------------------------------
    class IntRow(
        override val name: String,
        var value: Int,
        val min: Int,
        val max: Int,
        override var included: Boolean = false,
    ) : PropertyRow() {
        private var editBox: EditBox? = null

        override fun currentCondition(): StateCondition = StateCondition.IntProperty(name, value)

        fun syncFromEditBox() {
            val parsed = editBox?.value?.trim()?.toIntOrNull() ?: return
            value = parsed.coerceIn(min, max)
        }

        override fun addWidgetsTo(font: Font, rowX: Int, rowY: Int, addWidget: (AbstractWidget) -> Unit) {
            addWidget(buildCheckbox(rowX, rowY))
            val valueX = rowX + VALUE_X_OFFSET

            // − button
            addWidget(
                Button.builder(Component.literal("−")) {
                    if (value > min) {
                        value--
                        editBox?.value = value.toString()
                    }
                }.bounds(valueX, rowY, 14, ROW_H).build()
            )

            // EditBox
            val box = EditBox(font, valueX + 16, rowY, 36, ROW_H, Component.empty()).also {
                it.value = value.toString()
                it.setMaxLength(6)
            }
            editBox = box
            addWidget(box)

            // + button
            addWidget(
                Button.builder(Component.literal("+")) {
                    if (value < max) {
                        value++
                        editBox?.value = value.toString()
                    }
                }.bounds(valueX + 54, rowY, 14, ROW_H).build()
            )
        }
    }

    // ------------------------------------------------------------------
    // EnumRow — enum/string property row
    // ------------------------------------------------------------------
    class EnumRow(
        override val name: String,
        var value: String,
        val options: List<String>,
        override var included: Boolean = false,
    ) : PropertyRow() {
        override fun currentCondition(): StateCondition = StateCondition.EnumProperty(name, value)

        override fun addWidgetsTo(font: Font, rowX: Int, rowY: Int, addWidget: (AbstractWidget) -> Unit) {
            addWidget(buildCheckbox(rowX, rowY))
            val valueX = rowX + VALUE_X_OFFSET
            addWidget(
                CycleButton.builder<String>(
                    { s -> Component.literal(s) },
                    value,
                )
                    .withValues(*options.toTypedArray())
                    .create(valueX, rowY, 80, ROW_H, Component.empty()) { _, v ->
                        value = v
                    }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// BlockStateFormBuilder
// ---------------------------------------------------------------------------

object BlockStateFormBuilder {

    fun buildRows(state: BlockState, existingCondition: StateCondition?): List<PropertyRow> {
        val blockId = BuiltInRegistries.BLOCK.getKey(state.block)

        val rows = mutableListOf<PropertyRow>()
        rows += PropertyRow.BlockTypeRow(blockId)

        for (prop in state.block.stateDefinition.properties) {
            val row: PropertyRow = when (prop) {
                is BooleanProperty -> {
                    val currentValue = state.getValue(prop)
                    PropertyRow.BoolRow(name = prop.name, value = currentValue)
                }
                is IntegerProperty -> {
                    val currentValue = state.getValue(prop)
                    val minVal = prop.possibleValues.minOrNull() ?: 0
                    val maxVal = prop.possibleValues.maxOrNull() ?: 15
                    PropertyRow.IntRow(name = prop.name, value = currentValue, min = minVal, max = maxVal)
                }
                else -> {
                    @Suppress("UNCHECKED_CAST")
                    val castedProp = prop as Property<Comparable<Any>>
                    val currentValue = castedProp.getName(state.getValue(prop))
                    val options = prop.possibleValues.map { v ->
                        castedProp.getName(v)
                    }
                    PropertyRow.EnumRow(name = prop.name, value = currentValue, options = options)
                }
            }
            rows += row
        }

        if (existingCondition != null) {
            prePopulate(rows, existingCondition)
        }

        return rows
    }

    private fun prePopulate(rows: List<PropertyRow>, condition: StateCondition) {
        when (condition) {
            is StateCondition.All -> condition.conditions.forEach { prePopulate(rows, it) }
            is StateCondition.BlockType -> {
                rows.filterIsInstance<PropertyRow.BlockTypeRow>().firstOrNull()?.included = true
            }
            is StateCondition.BoolProperty -> {
                rows.filterIsInstance<PropertyRow.BoolRow>()
                    .firstOrNull { it.name == condition.name }
                    ?.also {
                        it.included = true
                        it.value = condition.value
                    }
            }
            is StateCondition.IntProperty -> {
                rows.filterIsInstance<PropertyRow.IntRow>()
                    .firstOrNull { it.name == condition.name }
                    ?.also {
                        it.included = true
                        it.value = condition.value
                    }
            }
            is StateCondition.EnumProperty -> {
                rows.filterIsInstance<PropertyRow.EnumRow>()
                    .firstOrNull { it.name == condition.name }
                    ?.also {
                        it.included = true
                        it.value = condition.value
                    }
            }
            // Any / Not / ContainerContents — leave rows unchecked
            else -> {}
        }
    }
}
