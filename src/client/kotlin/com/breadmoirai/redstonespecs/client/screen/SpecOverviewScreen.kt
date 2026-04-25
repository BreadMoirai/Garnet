package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.*
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.ScrollableLayout
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(
    val originPos: BlockPos,
) : Screen(Component.translatable("screen.redstonespecs.spec_overview")), DropdownHost {

    private var idEditMode = false
    private var lifespanBox: IntEditBox? = null

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
        openDropdown = null
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
        val modeButton = DropdownButton(
            this, 0, 0, 180, 20, font,
            SpecMode.entries.toList(),
            { mode -> Component.literal(when (mode) {
                SpecMode.SIMPLE -> "Simple"
                SpecMode.TICK_AWARE -> "Tick-Aware"
                SpecMode.UPDATE_AWARE -> "Update-Aware"
            }) },
            spec?.mode ?: SpecMode.SIMPLE,
        ) { value -> sendPacket(SetSpecModeC2SPayload(originPos, value)) }
        modeRow.addChild(modeButton)
        content.addChild(modeRow)

        // Lifespan row
        val lifespanRow = LinearLayout.horizontal().spacing(4)
        lifespanRow.addChild(StringWidget(40, 20, Component.literal("Life:"), font))
        val box = IntEditBox(font, 100, 20, 1, Int.MAX_VALUE, spec?.lifespan ?: 20, onChange = { sendPacket(SetLifespanC2SPayload(originPos, it)) })
        lifespanBox = box
        val decBtn = Button.builder(Component.literal("−")) {
            box.setIntValue(box.getIntValue() - 1)
        }.pos(0, 0).width(20).build()
        val incBtn = Button.builder(Component.literal("+")) {
            box.setIntValue(box.getIntValue() + 1)
        }.pos(0, 0).width(20).build()
        lifespanRow.addChild(box)
        lifespanRow.addChild(decBtn)
        lifespanRow.addChild(incBtn)
        content.addChild(lifespanRow)

        content.addChild(SpacerElement(0, 4))

        // Entry list — dynamic height
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

        // Last result — fetched early so fixedHeight can account for the optional rows
        val result = getBe()?.lastTestResult

        // Fixed height = sum of non-scroll children + (N-1)*4 spacing gaps + 20px margins.
        // Without result: 9 children, 8 gaps → (9+4+20+20+20+4+4+20) + 8*4 + 20 = 153
        // With result: 2 extra children (text=9, spacer=2) + 2 extra gaps → 153 + 9+2+8 = 172
        val fixedHeight = 153 + if (result != null) 19 else 0
        val entryScrollHeight = (height - fixedHeight).coerceAtLeast(60)
        val scrollable = ScrollableLayout(minecraft, entryListContent, entryScrollHeight)
        content.addChild(scrollable)

        content.addChild(SpacerElement(0, 4))
        if (result != null) {
            val text = if (result.pass)
                "✓ ${result.passCount}/${result.checks.size} checks passed"
            else
                "✗ ${result.checks.size - result.passCount}/${result.checks.size} checks failed"
            content.addChild(StringWidget(Component.literal(text), font))
            content.addChild(SpacerElement(0, 2))
        }

        // Action buttons
        val actionRow = LinearLayout.horizontal().spacing(4)
        actionRow.addChild(Button.builder(Component.literal("Load")) {
            sendPacket(RequestFileBrowserC2SPayload(originPos))
        }.pos(0, 0).width(60).build())
        actionRow.addChild(Button.builder(Component.literal("Run")) {
            sendPacket(RunSpecC2SPayload(originPos))
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
        super.onClose()
    }

    private fun getBe() = minecraft.level?.getBlockEntity(originPos) as? SpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
