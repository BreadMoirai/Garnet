package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

/**
 * Edits a single [SpecEntry] (label, color, time, and condition leaves).
 *
 * The condition is edited as a list of leaf [RowProp] rows, all sharing the
 * entry's single [SimTime]. Multiple leaves combine into [StateCondition.All]
 * on save. Compound conditions (Not / Any / nested All) round-trip through
 * a passthrough list — they are preserved but not editable in this UI.
 */
class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")), DropdownHost {

    private var launched = false
    private var workingLabel: String = ""
    private var workingColor: Int = -1
    private var workingTime: SimTime = SimTime.START
    private var workingRows: MutableList<RowProp>? = null
    private var workingPassthrough: List<StateCondition> = emptyList()
    private var originalEntry: SpecEntry? = null

    private var openDropdown: DropdownButton<*>? = null

    override fun getOpenDropdown(): DropdownButton<*>? = openDropdown
    override fun setOpenDropdown(d: DropdownButton<*>?) { openDropdown = d }

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val open = openDropdown
        if (open != null) {
            super.extractRenderState(graphics, Int.MIN_VALUE, Int.MIN_VALUE, partialTick)
            graphics.nextStratum()
            open.extractPopup(graphics, mouseX, mouseY, partialTick)
        } else {
            super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val open = openDropdown
        if (open != null) {
            if (open.popupMouseClicked(event)) return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun init() {
        super.init()
        if (!launched) return
        buildLayout()
    }

    override fun tick() {
        super.tick()
        if (launched) return
        val be = minecraft?.level?.getBlockEntity(originPos) as? SpecBlockEntity ?: return
        val entry = be.spec?.entries?.firstOrNull { it.pos == entryRelPos } ?: return
        originalEntry = entry
        workingLabel = entry.label
        workingColor = entry.color
        workingTime = entry.time

        val worldPos = originPos.offset(entryRelPos)
        val blockState = minecraft.level?.getBlockState(worldPos)
        val (rows, passthrough) = flattenSingleCondition(entry.condition, blockState)
        workingRows = rows
        workingPassthrough = passthrough

        launched = true
        rebuildWidgets()
    }

    private fun buildLayout() {
        openDropdown = null
        val entry = originalEntry ?: return
        val typeLabel = when (entry.kind) {
            EntryKind.INPUT -> "Input"
            EntryKind.OUTPUT -> "Output"
        }

        val content = LinearLayout.vertical().spacing(4)
        content.addChild(StringWidget(Component.literal("$typeLabel @ $entryRelPos"), font))
        content.addChild(SpacerElement(0, 4))

        // Label
        val labelRow = LinearLayout.horizontal().spacing(4)
        labelRow.addChild(StringWidget(50, 20, Component.literal("Label:"), font))
        val labelBox = EditBox(font, 180, 20, Component.empty())
        labelBox.value = workingLabel
        labelBox.setResponder { workingLabel = it }
        labelRow.addChild(labelBox)
        content.addChild(labelRow)

        // Color
        val colorRow = LinearLayout.horizontal().spacing(4)
        colorRow.addChild(StringWidget(50, 20, Component.literal("Color:"), font))
        val colorBox = EditBox(font, 80, 20, Component.empty())
        colorBox.value = "%08X".format(workingColor)
        colorBox.setMaxLength(8)
        val swatch = ColorSwatchWidget(0, 0, workingColor)
        colorBox.setResponder { hex ->
            val parsed = hex.toLongOrNull(16)
            if (parsed != null && hex.length <= 8) {
                workingColor = parsed.toInt()
                swatch.setColor(workingColor)
            }
        }
        colorRow.addChild(colorBox)
        colorRow.addChild(swatch)
        content.addChild(colorRow)

        // Time
        val timeRow = LinearLayout.horizontal().spacing(4)
        timeRow.addChild(StringWidget(50, 20, Component.literal("Tick:"), font))
        timeRow.addChild(intStepper(font, 80, 20, -1, Int.MAX_VALUE, workingTime.tick) { v ->
            workingTime = if (v < 0) SimTime.START else SimTime(v, workingTime.phase, workingTime.order)
        })
        val advancedPhases = Phase.entries.filter { it != Phase.USER_INTERACTION }
        timeRow.addChild(DropdownButton(
            this, 0, 0, 130, 20, font,
            advancedPhases,
            { phase -> Component.literal(phase.name) },
            workingTime.phase.takeIf { it != Phase.USER_INTERACTION } ?: Phase.START_OF_TICK,
        ) { phase ->
            workingTime = SimTime(workingTime.tick.coerceAtLeast(0), phase, workingTime.order)
        })
        content.addChild(timeRow)

        content.addChild(SpacerElement(0, 4))

        // Condition leaves
        val rows = workingRows
        if (rows != null) {
            content.addChild(StringWidget(Component.literal("Condition leaves:"), font))

            val worldPos = originPos.offset(entryRelPos)
            val blockState = minecraft.level?.getBlockState(worldPos)

            val tableContent = LinearLayout.vertical().spacing(1)
            rows.forEachIndexed { i, row ->
                tableContent.addChild(buildRowWidget(i, row, rows))
            }

            val availableProps = buildAvailableProps(blockState)
            val taken = rows.map { it.name }.toSet()
            val addOptions = availableProps.filter { it !in taken }.ifEmpty { availableProps }
            tableContent.addChild(
                DropdownButton(
                    this, 0, 0, 240, 16, font,
                    addOptions,
                    { Component.literal(it) },
                    addOptions.first(),
                    displayOverride = Component.literal("+ Add Leaf"),
                ) { propName ->
                    val newProp = buildRowPropForName(propName, blockState) ?: buildFirstRowProp(blockState)
                    if (newProp != null) rows.add(newProp)
                    rebuildWidgets()
                }
            )

            val tableScrollHeight = (height - 200).coerceAtLeast(60)
            content.addChild(ScrollableLayout(minecraft, tableContent, tableScrollHeight))

            if (workingPassthrough.isNotEmpty()) {
                content.addChild(StringWidget(0, 14,
                    Component.literal("(${workingPassthrough.size} compound condition(s) preserved unchanged)"),
                    font))
            }
        }

        content.addChild(SpacerElement(0, 4))

        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(Button.builder(Component.literal("Save")) { saveAndClose() }.pos(0, 0).width(80).build())
        bottomRow.addChild(Button.builder(CommonComponents.GUI_CANCEL) { onClose() }.pos(0, 0).width(80).build())
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildRowWidget(index: Int, row: RowProp, rows: MutableList<RowProp>): LinearLayout {
        val rowLayout = LinearLayout.horizontal().spacing(2)
        rowLayout.addChild(StringWidget(100, 16, Component.literal(row.name), font))
        rowLayout.addChild(buildValueWidget(row, index, rows))
        rowLayout.addChild(
            Button.builder(Component.literal("×")) {
                rows.removeAt(index)
                rebuildWidgets()
            }.pos(0, 0).size(20, 16).build()
        )
        return rowLayout
    }

    private fun buildValueWidget(row: RowProp, index: Int, rows: MutableList<RowProp>): LayoutElement = when (row) {
        is RowProp.Block -> StringWidget(110, 16, Component.literal(row.blockId.path), font)

        is RowProp.Bool -> DropdownButton(
            this, 0, 0, 110, 16, font,
            listOf(false, true),
            { v -> Component.literal(v.toString()) },
            row.value,
        ) { v -> row.value = v }

        is RowProp.ExactInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 60, 16, row.min, row.max, row.value, onChange = { v -> row.value = v }))
            valRow.addChild(Button.builder(Component.literal("Range")) {
                rows[index] = RowProp.RangeInt(row.name, row.value, row.max, row.min, row.max)
                rebuildWidgets()
            }.pos(0, 0).width(46).build())
            valRow
        }

        is RowProp.RangeInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 37, 16, row.absMin, row.absMax, row.lo, onChange = { v -> row.lo = v }))
            valRow.addChild(StringWidget(6, 16, Component.literal("-"), font))
            valRow.addChild(IntEditBox(font, 37, 16, row.absMin, row.absMax, row.hi, onChange = { v -> row.hi = v }))
            valRow.addChild(Button.builder(Component.literal("Exact")) {
                rows[index] = RowProp.ExactInt(row.name, row.lo, row.absMin, row.absMax)
                rebuildWidgets()
            }.pos(0, 0).width(40).build())
            valRow
        }

        is RowProp.Enum -> DropdownButton(
            this, 0, 0, 110, 16, font,
            row.options,
            { v -> Component.literal(v) },
            row.value,
        ) { v -> row.value = v }
    }

    private fun buildAvailableProps(blockState: BlockState?): List<String> {
        val names = mutableListOf("block")
        blockState?.block?.stateDefinition?.properties?.mapTo(names) { it.name }
        return names
    }

    private fun buildFirstRowProp(blockState: BlockState?): RowProp? {
        if (blockState == null) return null
        val firstProp = blockState.block.stateDefinition.properties.firstOrNull()
            ?: return RowProp.Block(BuiltInRegistries.BLOCK.getKey(blockState.block))
        return buildRowPropForName(firstProp.name, blockState)
    }

    private fun buildRowPropForName(propName: String, blockState: BlockState?): RowProp? {
        if (propName == "block") {
            val blockId = blockState?.let { BuiltInRegistries.BLOCK.getKey(it.block) } ?: return null
            return RowProp.Block(blockId)
        }
        val prop = blockState?.block?.stateDefinition?.getProperty(propName) ?: return null
        return when (prop) {
            is BooleanProperty -> RowProp.Bool(propName, blockState.getValue(prop))
            is IntegerProperty -> {
                val min = prop.possibleValues.min()
                val max = prop.possibleValues.max()
                RowProp.ExactInt(propName, blockState.getValue(prop), min, max)
            }
            else -> {
                @Suppress("UNCHECKED_CAST")
                val cast = prop as Property<Comparable<Any>>
                RowProp.Enum(
                    propName,
                    cast.getName(blockState.getValue(prop)),
                    prop.possibleValues.map { cast.getName(it) })
            }
        }
    }

    private fun saveAndClose() {
        val entry = originalEntry ?: return
        val rows = workingRows ?: return
        val leaves: List<StateCondition> = rows.map { it.toCondition() } + workingPassthrough
        val condition: StateCondition = when (leaves.size) {
            0 -> entry.condition  // nothing left to write — preserve original
            1 -> leaves.single()
            else -> StateCondition.All(leaves)
        }
        val updated = entry.copy(
            label = workingLabel,
            color = workingColor,
            time = workingTime,
            condition = condition,
        )
        ClientPlayNetworking.send(SaveSpecEntryC2SPayload(originPos, updated))
        onClose()
    }
}
