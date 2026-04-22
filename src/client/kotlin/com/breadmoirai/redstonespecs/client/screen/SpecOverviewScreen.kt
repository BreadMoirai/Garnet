package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.network.AddSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RemoveSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RenameSpecC2SPayload
import com.breadmoirai.redstonespecs.network.ResetSpecC2SPayload
import com.breadmoirai.redstonespecs.network.RunSpecC2SPayload
import com.breadmoirai.redstonespecs.network.SelectSpecCaseC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class SpecOverviewScreen(val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_overview")) {

    private val panelW = 320
    private val panelH = 220
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var nameEditBox: EditBox? = null

    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val spec = getSpec()

        nameEditBox = EditBox(font, x + 58, y + 14, 200, 16, Component.literal("Spec name")).also { box ->
            box.value = spec?.name ?: ""
            addRenderableWidget(box)
        }

        spec?.specCases?.forEachIndexed { i, case ->
            if (i >= 7) return@forEachIndexed
            addRenderableWidget(
                Button.builder(Component.literal(case.name)) {
                    sendPacket(SelectSpecCaseC2SPayload(originPos, i))
                }.bounds(x + 10, y + 42 + i * 22, 180, 18).build()
            )
        }

        addRenderableWidget(
            Button.builder(Component.literal("▶ Run")) {
                sendPacket(RunSpecC2SPayload(originPos, false))
            }.bounds(x + 10, y + panelH - 60, 58, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("▶▶ All")) {
                sendPacket(RunSpecC2SPayload(originPos, true))
            }.bounds(x + 74, y + panelH - 60, 58, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("↺ Reset")) {
                sendPacket(ResetSpecC2SPayload(originPos))
            }.bounds(x + 138, y + panelH - 60, 58, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("+ Case")) {
                val size = getSpec()?.specCases?.size ?: 0
                sendPacket(AddSpecCaseC2SPayload(originPos, "Case ${size + 1}"))
            }.bounds(x + 10, y + panelH - 35, 70, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.literal("- Case")) {
                val index = getBe()?.activeSpecCaseIndex ?: return@builder
                sendPacket(RemoveSpecCaseC2SPayload(originPos, index))
            }.bounds(x + 86, y + panelH - 35, 70, 20).build()
        )

        addRenderableWidget(
            Button.builder(Component.literal("Bounds…")) {
                minecraft?.setScreen(SpecBoundsScreen(originPos))
            }.bounds(x + 162, y + panelH - 35, 62, 20).build()
        )

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(x + panelW - 72, y + panelH - 35, 62, 20).build()
        )
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX
        val y = panelY

        extractor.fill(x, y, x + panelW, y + panelH, 0xB0101010.toInt())
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        extractor.centeredText(font, title, x + panelW / 2, y + 4, 0xFFFFFF)
        extractor.text(font, Component.literal("Name:"), x + 10, y + 17, 0x888888)

        val be = getBe()
        val spec = be?.spec
        if (spec == null) {
            extractor.centeredText(font, Component.literal("No spec loaded"), x + panelW / 2, y + 60, 0xFF4444)
            return
        }

        extractor.text(font, Component.literal("Cases:"), x + 10, y + 36, 0x888888)

        val activeIndex = be.activeSpecCaseIndex
        if (activeIndex < spec.specCases.size) {
            val highlightY = y + 42 + activeIndex * 22
            extractor.fill(x + 9, highlightY - 1, x + 191, highlightY + 19, 0x44FFFFFF)
        }

        val testResult = be.lastTestResult
        spec.specCases.forEachIndexed { i, case ->
            if (i >= 7) return@forEachIndexed
            val caseResult = testResult?.results?.find { it.specCaseName == case.name }
            val (statusText, statusColor) = when {
                caseResult == null -> "○" to 0x888888
                caseResult.checks.all { it.pass } -> "✓" to 0x44FF88
                else -> "✗" to 0xFF4444
            }
            extractor.text(font, Component.literal(statusText), x + 196, y + 47 + i * 22, statusColor)
        }

        if (testResult != null) {
            val pass = testResult.results.count { r -> r.checks.all { it.pass } }
            val total = testResult.results.size
            val color = if (pass == total) 0x44FF88 else 0xFF6644
            extractor.text(
                font,
                Component.literal("Last: $pass/$total passed"),
                x + 10, y + panelH - 78, color,
            )
        }
    }

    override fun onClose() {
        val newName = nameEditBox?.value?.trim() ?: ""
        val currentName = getSpec()?.name ?: ""
        if (newName.isNotBlank() && newName != currentName) {
            sendPacket(RenameSpecC2SPayload(originPos, newName))
        }
        super.onClose()
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
