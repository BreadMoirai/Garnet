package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.client.widget.BlockStateFormBuilder
import com.breadmoirai.redstonespecs.client.widget.PropertyRow
import com.breadmoirai.redstonespecs.client.widget.ROW_H
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.StateCondition
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class StateEntryEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
    private val initial: Pair<SimTime, StateCondition>?,  // null = new entry
    private val onConfirm: (SimTime, StateCondition) -> Unit,
) : Screen(Component.literal("Edit Entry")) {

    private val panelW = 300
    private val panelH = 240
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var rows: List<PropertyRow>? = null
    private var currentTick: Int = initial?.first?.tick ?: 0
    private var currentPhase: Phase = initial?.first?.phase ?: Phase.END_OF_TICK
    private var hasComplexCondition: Boolean =
        initial?.second is StateCondition.Any || initial?.second is StateCondition.Not

    private var tickEditBox: EditBox? = null

    override fun isInGameUi() = true
    override fun isPauseScreen() = false

    override fun init() {
        super.init()
        val x = panelX; val y = panelY

        val worldPos = originPos.offset(entryRelPos)
        val blockState = minecraft?.level?.getBlockState(worldPos) ?: return

        // Build rows once; reuse across rebuildWidgets() calls
        if (rows == null) {
            rows = BlockStateFormBuilder.buildRows(blockState, initial?.second)
        }

        // --- Tick stepper (top-left of panel) ---
        val tickY = y + 30
        val tickX = x + 8

        // − button
        addRenderableWidget(
            Button.builder(Component.literal("−")) {
                if (currentTick > -1) currentTick--
                tickEditBox?.value = if (currentTick < 0) "" else currentTick.toString()
            }.bounds(tickX + 28, tickY, 14, ROW_H).build()
        )

        // EditBox (36px wide)
        tickEditBox = EditBox(font, tickX + 44, tickY, 36, ROW_H, Component.empty()).also { box ->
            box.value = if (currentTick < 0) "" else currentTick.toString()
            box.setMaxLength(6)
            box.setResponder { text ->
                currentTick = if (text.isBlank()) -1 else text.toIntOrNull() ?: currentTick
            }
            addRenderableWidget(box)
        }

        // + button
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                if (currentTick < 0) currentTick = 0 else currentTick++
                tickEditBox?.value = currentTick.toString()
            }.bounds(tickX + 82, tickY, 14, ROW_H).build()
        )

        // --- Phase CycleButton ---
        addRenderableWidget(
            CycleButton.builder<Phase>(
                { phase -> Component.literal(phase.name) },
                currentPhase,
            )
                .withValues(*Phase.entries.toTypedArray())
                .create(x + 120, tickY, 160, ROW_H, Component.literal("Phase")) { _, phase ->
                    currentPhase = phase
                }
        )

        // --- Property rows ---
        val rowsSnapshot = rows ?: return
        val rowX = x + 8
        var rowY = y + 70
        for (row in rowsSnapshot) {
            row.addWidgetsTo(font, rowX, rowY, ::addRenderableWidget)
            rowY += ROW_H + 2
        }

        // --- Confirm / Cancel buttons ---
        val btnY = y + panelH - 22
        addRenderableWidget(
            Button.builder(Component.literal("Confirm")) {
                confirm()
            }.bounds(x + panelW / 2 - 80, btnY, 76, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) {
                onClose()
            }.bounds(x + panelW / 2 + 4, btnY, 76, 20).build()
        )
    }

    private fun confirm() {
        // Sync int row edit boxes
        rows?.filterIsInstance<PropertyRow.IntRow>()?.forEach { it.syncFromEditBox() }

        // Parse tick
        val tickText = tickEditBox?.value?.trim() ?: ""
        val tick = if (tickText.isBlank()) -1 else tickText.toIntOrNull() ?: return

        // Build SimTime
        val simTime = if (tick < 0) SimTime.INIT else SimTime(tick, currentPhase)

        // Collect included rows
        val included = rows?.filter { it.included } ?: return
        if (included.isEmpty()) return

        // Build condition
        val condition = if (included.size == 1) {
            included[0].currentCondition()
        } else {
            StateCondition.All(included.map { it.currentCondition() })
        }

        // onConfirm handles screen navigation (sets screen back to parent SpecEditorScreen).
        // Do NOT call onClose() here — that would set screen to null after onConfirm already
        // switched to the parent screen.
        onConfirm(simTime, condition)
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX; val y = panelY

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        // Title
        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())

        // "Tick:" label
        extractor.text(font, Component.literal("Tick:"), x + 8, y + 33, 0xFFAAAAAA.toInt())

        // Complex condition warning
        if (hasComplexCondition) {
            extractor.text(
                font,
                Component.literal("⚠ Complex condition (Any/Not) — properties shown unchecked"),
                x + 8, y + 56, 0xFFFF8800.toInt(),
            )
        }

        // Row name labels
        val rowsSnapshot = rows ?: return
        var rowY = y + 70
        for (row in rowsSnapshot) {
            extractor.text(font, Component.literal(row.name), x + 26, rowY + 4, 0xFFCCCCCC.toInt())
            rowY += ROW_H + 2
        }
    }
}
