package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private var idEditMode = false
    private var structureEditMode = false
    private var lifespanBox: IntEditBox? = null

    override fun isPauseScreen() = false
    override fun isInGameUi() = true


    override fun init() {
        super.init()
        val spec = getSpec()

        val content = LinearLayout.vertical().spacing(4)

        // Title
        content.addChild(StringWidget(Component.translatable("screen.redstonespecs.spec_overview"), font))

        content.addChild(SpacerElement(0, 4))

        // ID row
        val idRow = LinearLayout.horizontal().spacing(4)
        idRow.addChild(StringWidget(40, 20, Component.literal("ID:"), font))
        if (idEditMode) {
            val idBox = EditBox(font, 180, 20, Component.empty())
            idBox.value = spec?.id ?: ""
            idRow.addChild(idBox)
            idRow.addChild(Button.builder(Component.literal("✔")) {
                val newId = idBox.value.trim().takeIf { it.isNotBlank() }
                if (newId != null) { sendPacket(SetSpecIdC2SPayload(originPos, newId)); idEditMode = false; rebuildWidgets() }
            }.pos(0, 0).width(20).build())
        } else {
            idRow.addChild(StringWidget(180, 20, Component.literal(spec?.id ?: ""), font))
            idRow.addChild(Button.builder(Component.literal("✎")) {
                idEditMode = true; rebuildWidgets()
            }.pos(0, 0).width(20).build())
        }
        content.addChild(idRow)

        // Mode row
        val modeRow = LinearLayout.horizontal().spacing(4)
        modeRow.addChild(StringWidget(40, 20, Component.literal("Mode:"), font))
        val modeButton = CycleButton.builder<SpecMode>(
            { mode ->
                Component.literal(when (mode) {
                    SpecMode.SIMPLE -> "Simple"
                    SpecMode.TICK_AWARE -> "Tick-Aware"
                    SpecMode.UPDATE_AWARE -> "Update-Aware"
                })
            },
            spec?.mode ?: SpecMode.SIMPLE,
        ).withValues(*SpecMode.entries.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 180, 20, Component.empty()) { _, value ->
                sendPacket(SetSpecModeC2SPayload(originPos, value))
            }
        modeRow.addChild(modeButton)
        content.addChild(modeRow)

        // Lifespan row
        val lifespanRow = LinearLayout.horizontal().spacing(4)
        lifespanRow.addChild(StringWidget(40, 20, Component.literal("Life:"), font))
        val box = IntEditBox(font, 100, 20, 1, Int.MAX_VALUE, spec?.lifespan ?: 20, onChange = {})
        lifespanBox = box
        val decBtn = Button.builder(Component.literal("−")) {
            box.setIntValue(box.getIntValue() - 1)
            sendPacket(SetLifespanC2SPayload(originPos, box.getIntValue()))
        }.pos(0, 0).width(20).build()
        val incBtn = Button.builder(Component.literal("+")) {
            box.setIntValue(box.getIntValue() + 1)
            sendPacket(SetLifespanC2SPayload(originPos, box.getIntValue()))
        }.pos(0, 0).width(20).build()
        lifespanRow.addChild(box)
        lifespanRow.addChild(decBtn)
        lifespanRow.addChild(incBtn)
        content.addChild(lifespanRow)

        // Structure row
        val structureRow = LinearLayout.horizontal().spacing(4)
        structureRow.addChild(StringWidget(40, 20, Component.literal("Struct:"), font))
        if (structureEditMode) {
            val structBox = EditBox(font, 180, 20, Component.empty())
            structBox.value = spec?.structure ?: (spec?.id ?: "")
            structureRow.addChild(structBox)
            structureRow.addChild(Button.builder(Component.literal("✔")) {
                val s = structBox.value.trim()
                sendPacket(SetStructureC2SPayload(originPos, s.ifBlank { null }))
                structureEditMode = false; rebuildWidgets()
            }.pos(0, 0).width(20).build())
        } else {
            structureRow.addChild(StringWidget(180, 20, Component.literal(spec?.structure ?: "(none)"), font))
            structureRow.addChild(Button.builder(Component.literal("✎")) {
                structureEditMode = true; rebuildWidgets()
            }.pos(0, 0).width(20).build())
        }
        content.addChild(structureRow)

        content.addChild(SpacerElement(0, 4))

        // Entry list
        val entries = spec?.allEntries ?: emptyList()
        val entryListContent = LinearLayout.vertical().spacing(2)
        entries.forEach { entry ->
            val tag = when (entry) {
                is InputSpec -> "IN"
                is OutputSpec -> "OUT"
                is BreakpointSpec -> "BP"
                is AutoSpec -> "AUTO"
            }
            val label = Component.literal("► $tag  ${entry.label.ifEmpty { "—" }}  (${entry.pos.x},${entry.pos.y},${entry.pos.z})")
                .withStyle { it.withColor(entry.color) }
            entryListContent.addChild(Button.builder(label) {
                minecraft.setScreen(SpecEditorScreen(originPos, entry.pos))
            }.pos(0, 0).width(240).build())
        }
        if (entries.isEmpty()) {
            entryListContent.addChild(StringWidget(240, 18, Component.literal("(no entries)"), font))
        }

        val entryScrollHeight = (height - 240).coerceIn(60, 160)
        val scrollable = ScrollableLayout(minecraft, entryListContent, entryScrollHeight)
        content.addChild(scrollable)

        content.addChild(SpacerElement(0, 4))

        // Last result
        val result = getBe()?.lastTestResult
        if (result != null) {
            val text = if (result.pass)
                "✓ ${result.passCount}/${result.checks.size} checks passed"
            else
                "✗ ${result.checks.size - result.passCount}/${result.checks.size} checks failed"
            content.addChild(StringWidget(Component.literal(text), font))
            content.addChild(SpacerElement(0, 2))
        }

        // Action buttons row
        val actionRow = LinearLayout.horizontal().spacing(4)
        actionRow.addChild(Button.builder(Component.translatable("screen.redstonespecs.spec_overview.run")) {
            sendPacket(RunSpecC2SPayload(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(Component.literal("Load")) {
            spec?.id?.let { id -> sendPacket(LoadSpecC2SPayload(originPos, id)) }
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(Component.literal("Save")) {
            sendPacket(SaveSpecC2SPayload(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(Component.literal("Bounds")) {
            minecraft.setScreen(SpecBoundsScreen(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(CommonComponents.GUI_DONE) {
            onClose()
        }.pos(0, 0).width(60).build())
        content.addChild(actionRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    override fun onClose() {
        idEditMode = false
        structureEditMode = false
        super.onClose()
    }

    private fun getBe() = minecraft.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
