package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LayoutElement
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.level.block.state.properties.Property

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private var launched = false
    private var workingLabel: String = ""
    private var workingColor: Int = 0xFFFFFF
    private var workingRows: MutableList<FlatRow>? = null
    private var workingPassthrough: MutableList<Pair<SimTime, StateCondition>>? = null
    private var originalEntry: SpecEntry? = null
    private var specMode: SpecMode = SpecMode.SIMPLE

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    override fun init() {
        super.init()
        if (!launched) return
        buildLayout()
    }

    override fun tick() {
        super.tick()
        if (launched) return
        val be = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity ?: return
        val entry = be.spec?.entryAt(entryRelPos) ?: return
        originalEntry = entry
        workingLabel = entry.label
        workingColor = entry.color
        specMode = be.spec?.mode ?: SpecMode.SIMPLE

        val entries: List<Pair<SimTime, StateCondition>>? = when (entry) {
            is InputSpec -> entry.entries
            is OutputSpec -> entry.entries
            else -> null
        }
        if (entries != null) {
            val worldPos = originPos.offset(entryRelPos)
            val blockState = minecraft?.level?.getBlockState(worldPos)
            val (rows, passthrough) = flattenEntries(entries, blockState)
            workingRows = rows
            workingPassthrough = passthrough
        }

        launched = true
        rebuildWidgets()
    }

    private fun buildLayout() {
        val entry = originalEntry ?: return
        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
        }

        val content = LinearLayout.vertical().spacing(4)

        content.addChild(StringWidget(Component.literal("$typeLabel @ $entryRelPos"), font))
        content.addChild(SpacerElement(0, 4))

        val labelRow = LinearLayout.horizontal().spacing(4)
        labelRow.addChild(StringWidget(50, 20, Component.literal("Label:"), font))
        val labelBox = EditBox(font, 180, 20, Component.empty())
        labelBox.value = workingLabel
        labelBox.setResponder { workingLabel = it }
        labelRow.addChild(labelBox)
        content.addChild(labelRow)

        val colorRow = LinearLayout.horizontal().spacing(4)
        colorRow.addChild(StringWidget(50, 20, Component.literal("Color:"), font))
        val colorBox = EditBox(font, 80, 20, Component.empty())
        colorBox.value = "%06X".format(workingColor)
        colorBox.setMaxLength(6)
        val swatch = ColorSwatchWidget(0, 0, workingColor)
        colorBox.setResponder { hex ->
            val parsed = hex.toLongOrNull(16)
            if (parsed != null && hex.length <= 6) {
                workingColor = parsed.toInt() and 0xFFFFFF
                swatch.setColor(workingColor)
            }
        }
        colorRow.addChild(colorBox)
        colorRow.addChild(swatch)
        content.addChild(colorRow)

        content.addChild(SpacerElement(0, 4))

        val rows = workingRows
        if (rows != null) {
            content.addChild(StringWidget(Component.literal("Entries:"), font))

            val worldPos = originPos.offset(entryRelPos)
            val blockState = minecraft?.level?.getBlockState(worldPos)
            val availableProps = buildAvailableProps(blockState)
            val advancedPhases = Phase.entries.filter { it != Phase.USER_INTERACTION }

            val tableContent = LinearLayout.vertical().spacing(1)
            rows.forEachIndexed { i, row ->
                tableContent.addChild(buildTableRow(i, row, blockState, availableProps, advancedPhases))
            }

            tableContent.addChild(
                LowProfileButtonWidget(0, 0, 400, 16, Component.literal("+ Add Row")) {
                    val lastTime = rows.lastOrNull()?.simTime
                    val newTick = when {
                        lastTime == null -> -1
                        lastTime == SimTime.INIT -> 0
                        else -> lastTime.tick + 1
                    }
                    val newPhase = lastTime?.phase?.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK
                    val newTime = if (newTick < 0) SimTime.INIT else SimTime(newTick, newPhase)
                    val firstProp = buildFirstRowProp(blockState)
                    if (firstProp != null) rows.add(FlatRow(newTime, firstProp))
                    rebuildWidgets()
                }
            )

            content.addChild(ScrollableLayout(minecraft, tableContent, 140))

            content.addChild(
                LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Capture State")) {
                    captureState(rows)
                }
            )

            content.addChild(SpacerElement(0, 4))
        }

        content.addChild(
            LowProfileButtonWidget(0, 0, 120, 20, Component.literal("Remove Spec")) {
                ClientPlayNetworking.send(RemoveSpecEntryC2SPayload(originPos, entryRelPos))
                minecraft?.setScreen(null)
            }
        )

        content.addChild(SpacerElement(0, 4))

        val bottomRow = LinearLayout.horizontal().spacing(4)
        bottomRow.addChild(LowProfileButtonWidget(0, 0, 80, 20, Component.literal("Save")) { saveAndClose() })
        bottomRow.addChild(LowProfileButtonWidget(0, 0, 80, 20, CommonComponents.GUI_CANCEL) { onClose() })
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 0, 0, width, height)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildTableRow(
        index: Int,
        row: FlatRow,
        blockState: BlockState?,
        availableProps: List<String>,
        advancedPhases: List<Phase>,
    ): LinearLayout {
        val rowLayout = LinearLayout.horizontal().spacing(2)

        if (specMode == SpecMode.TICK_AWARE || specMode == SpecMode.UPDATE_AWARE) {
            val tickVal = if (row.simTime == SimTime.INIT) -1 else row.simTime.tick
            val tickBox = IntEditBox(
                font, 60, 16, -1, Int.MAX_VALUE, tickVal,
                onChange = { v ->
                    row.simTime = if (v < 0) SimTime.INIT
                        else SimTime(v, row.simTime.phase.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK)
                },
                onHoverEnd = { sortAndRebuild() },
            )
            rowLayout.addChild(tickBox)
        }

        if (specMode == SpecMode.UPDATE_AWARE) {
            val currentPhase = row.simTime.phase.takeIf { it != Phase.USER_INTERACTION } ?: Phase.END_OF_TICK
            val phaseDropdown = DropdownButton(
                0, 0, 110, 16, font,
                advancedPhases,
                { phase -> Component.literal(phase.name) },
                currentPhase,
            ) { phase ->
                if (row.simTime != SimTime.INIT) {
                    row.simTime = SimTime(row.simTime.tick, phase)
                }
                sortAndRebuild()
            }
            phaseDropdown.active = row.simTime != SimTime.INIT
            rowLayout.addChild(phaseDropdown)
        }

        val propDropdown = DropdownButton(
            0, 0, 100, 16, font,
            availableProps,
            { Component.literal(it) },
            row.prop.name,
        ) { propName ->
            val newProp = buildRowPropForName(propName, blockState)
            if (newProp != null) row.prop = newProp
            rebuildWidgets()
        }
        rowLayout.addChild(propDropdown)

        rowLayout.addChild(buildValueWidget(index, row))

        rowLayout.addChild(
            LowProfileButtonWidget(0, 0, 20, 16, Component.literal("×")) {
                workingRows!!.removeAt(index)
                rebuildWidgets()
            }
        )

        return rowLayout
    }

    private fun buildValueWidget(index: Int, row: FlatRow): LayoutElement = when (val prop = row.prop) {
        is RowProp.Block -> StringWidget(110, 16, Component.literal(prop.blockId.path), font)

        is RowProp.Bool -> CycleButton.builder<Boolean>(
            { v -> Component.literal(v.toString()) },
            prop.value,
        ).withValues(false, true)
            .displayOnlyValue()
            .create(0, 0, 110, 16, Component.empty()) { _, v -> prop.value = v }

        is RowProp.ExactInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 80, 16, prop.min, prop.max, prop.value, onChange = { v -> prop.value = v }))
            valRow.addChild(LowProfileButtonWidget(0, 0, 28, 16, Component.literal("~")) {
                row.prop = RowProp.RangeInt(prop.name, prop.value, prop.max, prop.min, prop.max)
                rebuildWidgets()
            })
            valRow
        }

        is RowProp.RangeInt -> {
            val valRow = LinearLayout.horizontal().spacing(1)
            valRow.addChild(IntEditBox(font, 37, 16, prop.absMin, prop.absMax, prop.lo, onChange = { v -> prop.lo = v }))
            valRow.addChild(StringWidget(6, 16, Component.literal("-"), font))
            valRow.addChild(IntEditBox(font, 37, 16, prop.absMin, prop.absMax, prop.hi, onChange = { v -> prop.hi = v }))
            valRow.addChild(LowProfileButtonWidget(0, 0, 20, 16, Component.literal("=")) {
                row.prop = RowProp.ExactInt(prop.name, prop.lo, prop.absMin, prop.absMax)
                rebuildWidgets()
            })
            valRow
        }

        is RowProp.Enum -> CycleButton.builder<String>(
            { v -> Component.literal(v) },
            prop.value,
        ).withValues(*prop.options.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 110, 16, Component.empty()) { _, v -> prop.value = v }
    }

    private fun sortAndRebuild() {
        workingRows?.sortWith(compareBy { it.simTime })
        rebuildWidgets()
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
                RowProp.Enum(propName, cast.getName(blockState.getValue(prop)), prop.possibleValues.map { cast.getName(it) })
            }
        }
    }

    private fun captureState(rows: MutableList<FlatRow>) {
        val level = minecraft?.level ?: return
        val worldPos = originPos.offset(entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)

        if (rows.isEmpty()) {
            val firstProp = buildFirstRowProp(blockState) ?: return
            rows.add(FlatRow(SimTime.INIT, firstProp))
            rebuildWidgets()
            return
        }

        val lastTime = rows.maxByOrNull { it.simTime }?.simTime ?: return
        val lastKnown = mutableMapOf<String, String>()
        rows.filter { it.simTime == lastTime }.forEach { r ->
            when (val p = r.prop) {
                is RowProp.Bool -> lastKnown[p.name] = p.value.toString()
                is RowProp.ExactInt -> lastKnown[p.name] = p.value.toString()
                is RowProp.Enum -> lastKnown[p.name] = p.value
                else -> {}
            }
        }

        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return

        val newTick = if (lastTime == SimTime.INIT) 0 else lastTime.tick + 1
        val newTime = SimTime(newTick, Phase.END_OF_TICK)
        diff.keys.forEach { propName ->
            val newProp = buildRowPropForName(propName, blockState) ?: return@forEach
            rows.add(FlatRow(newTime, newProp))
        }
        rebuildWidgets()
    }

    private fun saveAndClose() {
        val entry = originalEntry ?: return
        val entries = workingRows?.let { reconstitute(it, workingPassthrough ?: emptyList()) }
        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = workingLabel, color = workingColor, entries = entries ?: entry.entries)
            is OutputSpec -> entry.copy(label = workingLabel, color = workingColor, entries = entries ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = workingLabel, color = workingColor)
            is AutoSpec -> entry.copy(label = workingLabel, color = workingColor)
        }
        ClientPlayNetworking.send(SaveSpecEntryC2SPayload(originPos, updated))
        onClose()
    }
}
