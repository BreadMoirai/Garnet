package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.client.config.ModConfig
import com.breadmoirai.redstonespecs.client.widget.ColorPickerWidget
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.network.RemoveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.network.SaveSpecEntryC2SPayload
import com.breadmoirai.redstonespecs.runner.captureBlockStateProps
import com.breadmoirai.redstonespecs.runner.propsToCondition
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_editor")) {

    private val panelW = 320
    private val panelH = 230
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var labelEditBox: EditBox? = null
    // colorPicker persists across rebuildWidgets() like workingEntries
    private var colorPicker: ColorPickerWidget? = null

    // Persists across rebuildWidgets(); null until entry available from server BE
    private var workingEntries: MutableList<Pair<SimTime, StateCondition>>? = null
    private var scrollOffset = 0

    companion object {
        private const val MAX_VISIBLE_ROWS = 5
        private const val ROW_H = 14
        private const val TABLE_TOP = 80
        private const val TABLE_COL_TICK = 8
        private const val TABLE_COL_PHASE = 50
        private const val TABLE_COL_STATE = 140
        private const val TABLE_COL_EDIT = -44
        private const val TABLE_COL_REMOVE = -22
    }

    override fun init() {
        super.init()
        val x = panelX; val y = panelY
        val entry = getEntry()

        if (workingEntries == null) {
            workingEntries = when (entry) {
                is InputSpec -> entry.entries.toMutableList()
                is OutputSpec -> entry.entries.toMutableList()
                else -> null
            }
        }

        labelEditBox = EditBox(font, x + 52, y + 26, 200, 16, Component.literal("Label")).also {
            it.value = entry?.label ?: ""
            addRenderableWidget(it)
        }

        // Persist colorPicker across rebuildWidgets()
        if (colorPicker == null) {
            colorPicker = ColorPickerWidget(x + 52, y + 46, 180, 16, entry?.color ?: 0xFFFFFF)
        }
        addRenderableWidget(colorPicker!!)
        // If dropdown is open (e.g. after rebuildWidgets()), re-register hexBox
        if (colorPicker!!.isDropdownOpen()) {
            colorPicker!!.openDropdown()?.let { addRenderableWidget(it) }
        }

        val entries = workingEntries
        if (entries != null) {
            val visible = entries.drop(scrollOffset).take(MAX_VISIBLE_ROWS)
            visible.forEachIndexed { i, _ ->
                val absIdx = scrollOffset + i
                val rowY = y + TABLE_TOP + i * ROW_H

                // ✎ edit button
                addRenderableWidget(
                    Button.builder(Component.literal("✎")) {
                        openEntryEditor(absIdx)
                    }.bounds(x + panelW + TABLE_COL_EDIT, rowY, 18, 12).build()
                )
                // ✕ remove button
                addRenderableWidget(
                    Button.builder(Component.literal("✕")) {
                        entries.removeAt(absIdx)
                        if (scrollOffset > 0 && scrollOffset >= entries.size) scrollOffset--
                        rebuildWidgets()
                    }.bounds(x + panelW + TABLE_COL_REMOVE, rowY, 18, 12).build()
                )
            }

            // Scroll buttons if needed
            if (entries.size > MAX_VISIBLE_ROWS) {
                addRenderableWidget(
                    Button.builder(Component.literal("▲")) {
                        if (scrollOffset > 0) { scrollOffset--; rebuildWidgets() }
                    }.bounds(x + panelW - 20, y + TABLE_TOP - 14, 14, 12).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("▼")) {
                        if (scrollOffset + MAX_VISIBLE_ROWS < entries.size) { scrollOffset++; rebuildWidgets() }
                    }.bounds(x + panelW - 20, y + TABLE_TOP + MAX_VISIBLE_ROWS * ROW_H, 14, 12).build()
                )
            }

            // + Add Entry
            addRenderableWidget(
                Button.builder(Component.literal("+ Add Entry")) {
                    openEntryEditor(null)
                }.bounds(x + 8, y + 168, 80, 14).build()
            )

            // Capture State
            addRenderableWidget(
                Button.builder(Component.literal("Capture State")) { captureState() }
                    .bounds(x + 96, y + 168, 90, 14).build()
            )
        }

        // Bottom buttons
        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 8, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("Remove")) { remove() }
                .bounds(x + 74, y + panelH - 22, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 22, 60, 18).build()
        )
    }

    override fun mouseClicked(event: MouseButtonEvent, unknownBoolean: Boolean): Boolean {
        val wasOpen = colorPicker?.isDropdownOpen() ?: false
        val result = super.mouseClicked(event, unknownBoolean)
        val isOpenNow = colorPicker?.isDropdownOpen() ?: false
        if (wasOpen != isOpenNow) {
            rebuildWidgets()
        }
        return result
    }

    private fun openEntryEditor(editIndex: Int?) {
        val entries = workingEntries
        val initial = if (editIndex != null) entries?.getOrNull(editIndex) else null
        minecraft?.setScreen(
            StateEntryEditorScreen(
                originPos = originPos,
                entryRelPos = entryRelPos,
                initial = initial,
                onConfirm = { simTime, condition ->
                    if (editIndex != null && entries != null) {
                        entries[editIndex] = simTime to condition
                    } else {
                        workingEntries?.add(simTime to condition)
                    }
                    minecraft?.setScreen(this)
                    rebuildWidgets()
                },
            )
        )
    }

    private fun captureState() {
        val mc = minecraft ?: return
        val level = mc.level ?: return
        val worldPos = originPos.offset(entryRelPos)
        val blockState = level.getBlockState(worldPos)
        val currentProps = captureBlockStateProps(blockState)
        val entries = workingEntries ?: return

        if (entries.isEmpty()) {
            entries.add(0, SimTime.INIT to propsToCondition(currentProps, blockState))
            rebuildWidgets()
            return
        }

        // Find last entry by SimTime order
        val lastEntry = entries.maxByOrNull { it.first }!!
        val lastKnown = flattenConditionToMap(lastEntry.second)

        // Diff: only properties that changed
        val diff = currentProps.filter { (k, v) -> lastKnown[k] != v }
        if (diff.isEmpty()) return

        val newTick = if (lastEntry.first == SimTime.INIT) 0 else lastEntry.first.tick + 1
        entries.add(SimTime(newTick, Phase.END_OF_TICK) to propsToCondition(diff, blockState))
        rebuildWidgets()
    }

    /** Flattens All(leaves) or single typed leaf to a Map<String,String> for diffing. */
    private fun flattenConditionToMap(condition: StateCondition): Map<String, String> {
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

    private fun isDirty(): Boolean {
        val entry = getEntry() ?: return false
        val currentLabel = labelEditBox?.value ?: ""
        val currentColor = colorPicker?.color ?: 0xFFFFFF
        if (currentLabel != entry.label || currentColor != entry.color) return true
        return when (entry) {
            is InputSpec -> workingEntries != entry.entries
            is OutputSpec -> workingEntries != entry.entries
            else -> false
        }
    }

    override fun onClose() {
        if (!isDirty()) {
            doClose()
            return
        }
        if (ModConfig.autoSaveOnExit) {
            save()
            return
        }
        minecraft?.setScreen(
            ConfirmScreen(
                { confirmed ->
                    if (confirmed) save() else doClose()
                },
                Component.literal("Unsaved Changes"),
                Component.literal("You have unsaved changes. Save before closing?"),
                Component.literal("Save"),
                Component.literal("Discard"),
            )
        )
    }

    private fun doClose() {
        workingEntries = null
        colorPicker = null
        scrollOffset = 0
        super.onClose()
    }

    override fun tick() {
        super.tick()
        if (workingEntries == null) {
            when (val entry = getEntry()) {
                is InputSpec -> { workingEntries = entry.entries.toMutableList(); rebuildWidgets() }
                is OutputSpec -> { workingEntries = entry.entries.toMutableList(); rebuildWidgets() }
                else -> {}
            }
        }
    }

    private fun save() {
        val entry = getEntry() ?: run { doClose(); return }
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        val label = labelEditBox?.value ?: ""
        val color = colorPicker?.color ?: 0xFFFFFF

        val updated: SpecEntry = when (entry) {
            is InputSpec -> entry.copy(label = label, color = color,
                entries = workingEntries?.toList() ?: entry.entries)
            is OutputSpec -> entry.copy(label = label, color = color,
                entries = workingEntries?.toList() ?: entry.entries)
            is BreakpointSpec -> entry.copy(label = label, color = color)
            is AutoSpec -> entry.copy(label = label, color = color)
        }
        sendPacket(SaveSpecEntryC2SPayload(originPos, specCaseIndex, updated))
        doClose()
    }

    private fun remove() {
        val specCaseIndex = getBe()?.activeSpecCaseIndex ?: 0
        sendPacket(RemoveSpecEntryC2SPayload(originPos, specCaseIndex, entryRelPos))
        doClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX; val y = panelY
        val entry = getEntry()
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val typeLabel = when (entry) {
            is InputSpec -> "Input"
            is OutputSpec -> "Output"
            is BreakpointSpec -> "Breakpoint"
            is AutoSpec -> "AutoSpec"
            null -> "Entry"
        }
        extractor.centeredText(font, Component.literal("$typeLabel @ $entryRelPos"), x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())
        extractor.text(font, Component.literal("Label:"), x + 8, y + 29, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Color:"), x + 8, y + 49, 0xFFAAAAAA.toInt())

        val entries = workingEntries
        if (entries != null) {
            extractor.text(font, Component.literal("State entries: ${entries.size}"), x + 8, y + 68, 0xFF888888.toInt())

            // Table column headers
            extractor.text(font, Component.literal("TICK"), x + TABLE_COL_TICK, y + TABLE_TOP - 10, 0xFF666666.toInt())
            extractor.text(font, Component.literal("PHASE"), x + TABLE_COL_PHASE, y + TABLE_TOP - 10, 0xFF666666.toInt())
            extractor.text(font, Component.literal("STATE"), x + TABLE_COL_STATE, y + TABLE_TOP - 10, 0xFF666666.toInt())

            val visible = entries.drop(scrollOffset).take(MAX_VISIBLE_ROWS)
            visible.forEachIndexed { i, (simTime, condition) ->
                val rowY = y + TABLE_TOP + i * ROW_H + 1
                val tickLabel = if (simTime == SimTime.INIT) "INIT" else "t${simTime.tick}"
                val phaseLabel = simTime.phase.name.take(9)
                val statePreview = previewCondition(condition).let {
                    if (it.length > 28) it.take(27) + "…" else it
                }
                extractor.text(font, Component.literal(tickLabel), x + TABLE_COL_TICK, rowY, 0xFFAAAAAA.toInt())
                extractor.text(font, Component.literal(phaseLabel), x + TABLE_COL_PHASE, rowY, 0xFF888888.toInt())
                extractor.text(font, Component.literal(statePreview), x + TABLE_COL_STATE, rowY, 0xFF888888.toInt())
            }

            if (entries.size > MAX_VISIBLE_ROWS) {
                val scrollInfo = "${scrollOffset + 1}–${(scrollOffset + MAX_VISIBLE_ROWS).coerceAtMost(entries.size)}/${entries.size}"
                extractor.text(font, Component.literal(scrollInfo), x + panelW - 60, y + TABLE_TOP - 10, 0xFF555555.toInt())
            }
        } else {
            when (entry) {
                is BreakpointSpec -> extractor.text(
                    font, Component.literal("Enabled: ${entry.enabled}"),
                    x + 8, y + 70, if (entry.enabled) 0xFF44FF88.toInt() else 0xFFFF4444.toInt(),
                )
                is AutoSpec -> extractor.text(
                    font, Component.literal("Trigger: ${entry.condition::class.simpleName}"),
                    x + 8, y + 70, 0xFFFFAA00.toInt(),
                )
                null -> extractor.centeredText(
                    font, Component.literal("Entry not found"), x + panelW / 2, y + 70, 0xFFFF4444.toInt(),
                )
                else -> {}
            }
        }
    }

    private fun previewCondition(condition: StateCondition): String = when (condition) {
        is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
        is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
        is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
        is StateCondition.BlockType -> "block=${condition.blockId.path}"
        is StateCondition.All -> condition.conditions.joinToString(",") { previewCondition(it) }
        is StateCondition.Any -> condition.conditions.joinToString("|") { previewCondition(it) }
        is StateCondition.Not -> "!${previewCondition(condition.condition)}"
        is StateCondition.ContainerContents -> "container(...)"
    }

    override fun isPauseScreen() = false
    override fun isInGameUi() = true

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getEntry(): SpecEntry? {
        val be = getBe() ?: return null
        val spec = be.spec ?: return null
        return spec.specCases.getOrNull(be.activeSpecCaseIndex)?.entryAt(entryRelPos)
    }
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
