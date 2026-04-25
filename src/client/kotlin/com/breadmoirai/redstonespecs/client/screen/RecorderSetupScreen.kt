package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.data.SpecMode
import com.breadmoirai.redstonespecs.network.*
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class RecorderSetupScreen(
    val originPos: BlockPos,
    private val initialIsRecording: Boolean,
) : Screen(Component.literal("Spec Recorder")), DropdownHost {

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
        val spec = currentBe()?.spec
        val isRecording = currentBe()?.isRecording ?: initialIsRecording

        val content = LinearLayout.vertical().spacing(4)

        // Title
        content.addChild(StringWidget(Component.literal("Spec Recorder"), font))
        content.addChild(SpacerElement(0, 4))

        // ID row: label + EditBox (fires SetSpecIdC2SPayload on change)
        val idRow = LinearLayout.horizontal().spacing(4)
        idRow.addChild(StringWidget(40, 20, Component.literal("ID:"), font))
        val idBox = EditBox(font, 200, 20, Component.empty())
        // setValue fires the responder synchronously — set responder first, then value
        idBox.setResponder { value ->
            if (value.isNotBlank()) sendPacket(SetSpecIdC2SPayload(originPos, value))
        }
        idBox.value = spec?.id ?: ""
        idRow.addChild(idBox)
        content.addChild(idRow)

        // Mode row: label + DropdownButton
        val modeRow = LinearLayout.horizontal().spacing(4)
        modeRow.addChild(StringWidget(40, 20, Component.literal("Mode:"), font))
        val modeButton = DropdownButton(
            this, 0, 0, 200, 20, font,
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

        // Bounds row: label showing coords + "Edit Bounds" button
        val bounds = spec?.bounds
        val boundsText = if (bounds != null)
            "${bounds.minX()},${bounds.minY()},${bounds.minZ()} → ${bounds.maxX()},${bounds.maxY()},${bounds.maxZ()}"
        else
            "(none)"
        val boundsRow = LinearLayout.horizontal().spacing(4)
        boundsRow.addChild(StringWidget(160, 20, Component.literal("Bounds: $boundsText"), font))
        boundsRow.addChild(Button.builder(Component.literal("Edit Bounds")) {
            minecraft.setScreen(SpecBoundsScreen(originPos))
        }.pos(0, 0).width(80).build())
        content.addChild(boundsRow)

        // Structure row: label + "Load…" button
        val structureName = spec?.structure ?: "(none)"
        val structureRow = LinearLayout.horizontal().spacing(4)
        structureRow.addChild(StringWidget(160, 20, Component.literal("Structure: $structureName"), font))
        structureRow.addChild(Button.builder(Component.literal("Load…")) {
            sendPacket(RequestFileBrowserC2SPayload(originPos))
        }.pos(0, 0).width(80).build())
        content.addChild(structureRow)

        // Counts row: read-only "Inputs: N    Outputs: M"
        val inputCount = spec?.inputs?.size ?: 0
        val outputCount = spec?.outputs?.size ?: 0
        content.addChild(StringWidget(
            Component.literal("Inputs: $inputCount    Outputs: $outputCount"), font
        ))

        content.addChild(SpacerElement(0, 4))

        // Gating rules
        val gatingReasons = mutableListOf<String>()
        val specId = spec?.id ?: ""
        if (specId.isBlank()) gatingReasons.add("Need spec id")
        if (inputCount < 1) gatingReasons.add("Need ≥1 input marker")
        if (outputCount < 1) gatingReasons.add("Need ≥1 output marker")
        if (bounds == null || (bounds.maxX() - bounds.minX()) * (bounds.maxY() - bounds.minY()) * (bounds.maxZ() - bounds.minZ()) == 0) {
            gatingReasons.add("Need non-empty bounds")
        }
        val canRecord = gatingReasons.isEmpty()

        // Status row: only shown when Record is disabled and not currently recording
        if (!isRecording && !canRecord) {
            content.addChild(StringWidget(
                Component.literal("§c${gatingReasons.joinToString(", ")}"), font
            ))
        }

        // Record / Stop button (full-width, prominent)
        if (isRecording) {
            content.addChild(Button.builder(Component.literal("Stop")) {
                sendPacket(StopRecordingC2SPayload(originPos))
                onClose()
            }.pos(0, 0).width(244).build())
        } else {
            val recordBtn = Button.builder(Component.literal("Record")) {
                sendPacket(StartRecordingC2SPayload(originPos))
                onClose()
            }.pos(0, 0).width(244).build()
            recordBtn.active = canRecord
            content.addChild(recordBtn)
        }

        content.addChild(SpacerElement(0, 4))

        // Done button
        content.addChild(Button.builder(Component.literal("Done")) {
            onClose()
        }.pos(0, 0).width(244).build())

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun currentBe(): SpecBlockEntity? =
        Minecraft.getInstance().level?.getBlockEntity(originPos) as? SpecBlockEntity

    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
