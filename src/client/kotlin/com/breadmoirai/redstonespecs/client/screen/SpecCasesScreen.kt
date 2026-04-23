package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.network.AddSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RemoveSpecCaseC2SPayload
import com.breadmoirai.redstonespecs.network.RenameSpecCaseC2SPayload
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

class SpecCasesScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_cases")) {

    private val panelW = 280
    private val panelH = 224
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var editingIndex: Int? = null
    private var editingBox: EditBox? = null
    private var addCaseBox: EditBox? = null

    private var lastCaseCount = -1

    override fun isInGameUi() = true
    override fun isPauseScreen() = false

    override fun init() {
        super.init()
        val x = panelX; val y = panelY
        val spec = getSpec()
        val cases = spec?.specCases ?: emptyList()
        val activeIndex = getBe()?.activeSpecCaseIndex ?: 0
        lastCaseCount = cases.size

        cases.take(7).forEachIndexed { i, case ->
            val rowY = y + 22 + i * 22
            if (i == editingIndex) {
                editingBox = EditBox(font, x + 8, rowY + 1, 192, 16, Component.empty()).also {
                    it.value = case.name
                    addRenderableWidget(it)
                }
                addRenderableWidget(
                    Button.builder(Component.literal("✔")) {
                        val newName = editingBox?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return@builder
                        sendPacket(RenameSpecCaseC2SPayload(originPos, i, newName))
                        editingIndex = null
                        rebuildWidgets()
                    }.bounds(x + 204, rowY, 22, 18).build()
                )
            } else {
                addRenderableWidget(
                    Button.builder(Component.literal(case.name)) {
                        sendPacket(SelectSpecCaseC2SPayload(originPos, i))
                    }.bounds(x + 8, rowY, 190, 18).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("✎")) {
                        editingIndex = i
                        rebuildWidgets()
                    }.bounds(x + 202, rowY, 22, 18).build()
                )
                addRenderableWidget(
                    Button.builder(Component.literal("✕")) {
                        sendPacket(RemoveSpecCaseC2SPayload(originPos, i))
                    }.bounds(x + 228, rowY, 18, 18).build()
                )
            }
        }

        // Add row
        val addRowY = y + 178
        addCaseBox = EditBox(font, x + 8, addRowY + 1, 190, 16, Component.translatable("screen.redstonespecs.spec_cases.name_hint")).also {
            addRenderableWidget(it)
        }
        addRenderableWidget(
            Button.builder(Component.literal("+")) {
                val count = getSpec()?.specCases?.size ?: 0
                val name = addCaseBox?.value?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "Test Case ${count + 1}"
                sendPacket(AddSpecCaseC2SPayload(originPos, name))
                addCaseBox?.value = ""
            }.bounds(x + 202, addRowY, 22, 18).build()
        )

        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) {
                minecraft?.setScreen(SpecOverviewScreen(originPos))
            }.bounds(x + panelW - 68, y + panelH - 24, 60, 20).build()
        )
    }

    override fun tick() {
        super.tick()
        val caseCount = getSpec()?.specCases?.size ?: 0
        if (caseCount != lastCaseCount) {
            lastCaseCount = caseCount
            editingIndex = null
            rebuildWidgets()
        }
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX; val y = panelY
        val spec = getSpec()
        val cases = spec?.specCases ?: emptyList()
        val activeIndex = getBe()?.activeSpecCaseIndex ?: 0

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())

        if (activeIndex < cases.size.coerceAtMost(7)) {
            val highlightY = y + 22 + activeIndex * 22
            extractor.fill(x + 7, highlightY - 1, x + 247, highlightY + 19, 0x44FFFFFF)
        }

        if (cases.isEmpty()) {
            extractor.centeredText(
                font, Component.literal("No test cases yet"),
                x + panelW / 2, y + 40, 0xFF888888.toInt(),
            )
        }
    }

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(p: CustomPacketPayload) = ClientPlayNetworking.send(p)
}
