package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.network.ResetSpecC2SPayload
import com.breadmoirai.redstonespecs.network.RunSpecC2SPayload
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
    private val panelH = 190
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var nameEditMode = false
    private var nameEditBox: EditBox? = null

    override fun isInGameUi() = true

    override fun init() {
        super.init()
        val x = panelX; val y = panelY

        // Spec ID row: display name text + pencil button, or edit box + confirm button
        if (nameEditMode) {
            nameEditBox = EditBox(font, x + 64, y + 12, panelW - 92, 16, Component.empty()).also {
                it.value = getSpec()?.id ?: ""
                addRenderableWidget(it)
            }
            addRenderableWidget(
                Button.builder(Component.literal("✔")) {
                    val newId = nameEditBox?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return@builder
                    // TODO Task 4: send RenameSpecC2SPayload or equivalent
                    nameEditMode = false
                    rebuildWidgets()
                }.bounds(x + panelW - 26, y + 12, 18, 16).build()
            )
        } else {
            addRenderableWidget(
                Button.builder(Component.literal("✎")) {
                    nameEditMode = true
                    rebuildWidgets()
                }.bounds(x + panelW - 26, y + 12, 18, 16).build()
            )
        }

        // Row 1: Run, Bounds, Reset & Load
        addRenderableWidget(
            Button.builder(Component.translatable("screen.redstonespecs.spec_overview.run")) {
                sendPacket(RunSpecC2SPayload(originPos, false))
            }.bounds(x + 8, y + panelH - 48, 76, 20).build()
        )
        // TODO Task 3/5: Run All button (removed runAll concept for now)

        // Row 2: Bounds, Reset & Load, Done
        addRenderableWidget(
            Button.builder(Component.translatable("screen.redstonespecs.spec_overview.bounds")) {
                minecraft?.setScreen(SpecBoundsScreen(originPos))
            }.bounds(x + 8, y + panelH - 24, 58, 20).build()
        )
        addRenderableWidget(
            Button.builder(Component.translatable("screen.redstonespecs.spec_overview.reset_load")) {
                sendPacket(ResetSpecC2SPayload(originPos))
            }.bounds(x + 70, y + panelH - 24, 88, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_DONE) { onClose() }
                .bounds(x + panelW - 66, y + panelH - 24, 58, 20).build()
        )
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        val x = panelX; val y = panelY
        val be = getBe()
        val spec = be?.spec

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)
        extractor.centeredText(font, title, x + panelW / 2, y + 4, 0xFFFFFFFF.toInt())

        // Spec ID label
        extractor.text(
            font,
            Component.translatable("screen.redstonespecs.spec_overview.spec_id").append(":"),
            x + 8, y + 16, 0xFF888888.toInt(),
        )
        if (!nameEditMode) {
            extractor.text(font, Component.literal(spec?.id ?: ""), x + 64, y + 16, 0xFFFFFFFF.toInt())
        }

        if (spec == null) {
            extractor.centeredText(
                font,
                Component.translatable("screen.redstonespecs.spec_overview.no_spec"),
                x + panelW / 2, y + 60, 0xFFFF4444.toInt(),
            )
            return
        }

        // TODO Task 3/5: display test result summary
        extractor.text(
            font,
            Component.literal("In: ${spec.inputs.size}  Out: ${spec.outputs.size}  BP: ${spec.breakpoints.size}"),
            x + 8, y + 50, 0xFF888888.toInt(),
        )
        val b = spec.bounds
        extractor.text(
            font,
            Component.literal("(${b.minX()},${b.minY()},${b.minZ()})→(${b.maxX()},${b.maxY()},${b.maxZ()})"),
            x + 8, y + 62, 0xFF888888.toInt(),
        )
    }

    override fun onClose() {
        nameEditMode = false
        super.onClose()
    }

    override fun isPauseScreen() = false

    private fun getBe() = minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity
    private fun getSpec() = getBe()?.spec
    private fun sendPacket(payload: CustomPacketPayload) = ClientPlayNetworking.send(payload)
}
