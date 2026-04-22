package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.structure.BoundingBox

class SpecBoundsScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_bounds")) {

    private enum class DisplayMode { OFFSET_SIZE, MIN_MAX }

    private val panelW = 252
    private val panelH = 172
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var displayMode = DisplayMode.OFFSET_SIZE

    private var box1x: EditBox? = null
    private var box1y: EditBox? = null
    private var box1z: EditBox? = null
    private var box2x: EditBox? = null
    private var box2y: EditBox? = null
    private var box2z: EditBox? = null

    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)

        addRenderableWidget(
            CycleButton.builder<DisplayMode>(
                { mode -> Component.literal(if (mode == DisplayMode.OFFSET_SIZE) "Offset+Size" else "Min+Max") },
                displayMode,
            ).withValues(*DisplayMode.entries.toTypedArray())
                .create(x + panelW - 92, y + 14, 88, 14, Component.empty()) { _, value ->
                    displayMode = value
                    rebuildWidgets()
                }
        )

        val (v1x, v1y, v1z, v2x, v2y, v2z) = toDisplayValues(bounds)

        box1x = addBox(x + 8,   y + 56, v1x)
        box1y = addBox(x + 88,  y + 56, v1y)
        box1z = addBox(x + 168, y + 56, v1z)

        box2x = addBox(x + 8,   y + 110, v2x)
        box2y = addBox(x + 88,  y + 110, v2y)
        box2z = addBox(x + 168, y + 110, v2z)

        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 8, y + panelH - 24, 60, 18).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 68, y + panelH - 24, 60, 18).build()
        )
    }

    private fun addBox(bx: Int, by: Int, value: Int): EditBox =
        EditBox(font, bx, by, 72, 14, Component.empty()).also {
            it.value = value.toString()
            addRenderableWidget(it)
        }

    private data class DisplayValues(
        val v1x: Int, val v1y: Int, val v1z: Int,
        val v2x: Int, val v2y: Int, val v2z: Int,
    )

    private fun toDisplayValues(b: BoundingBox): DisplayValues = if (displayMode == DisplayMode.OFFSET_SIZE)
        DisplayValues(b.minX(), b.minY(), b.minZ(), b.maxX() - b.minX() + 1, b.maxY() - b.minY() + 1, b.maxZ() - b.minZ() + 1)
    else
        DisplayValues(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())

    private fun save() {
        val v1x = box1x?.value?.toIntOrNull() ?: return
        val v1y = box1y?.value?.toIntOrNull() ?: return
        val v1z = box1z?.value?.toIntOrNull() ?: return
        val v2x = box2x?.value?.toIntOrNull() ?: return
        val v2y = box2y?.value?.toIntOrNull() ?: return
        val v2z = box2z?.value?.toIntOrNull() ?: return

        val newBounds = if (displayMode == DisplayMode.OFFSET_SIZE) {
            BoundingBox(
                v1x, v1y, v1z,
                v1x + v2x.coerceAtLeast(1) - 1,
                v1y + v2y.coerceAtLeast(1) - 1,
                v1z + v2z.coerceAtLeast(1) - 1,
            )
        } else {
            BoundingBox(
                minOf(v1x, v2x), minOf(v1y, v2y), minOf(v1z, v2z),
                maxOf(v1x, v2x), maxOf(v1y, v2y), maxOf(v1z, v2z),
            )
        }

        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, newBounds))
        onClose()
    }

    override fun extractRenderState(
        extractor: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        super.extractBackground(extractor, mouseX, mouseY, partialTick)
        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        val x = panelX
        val y = panelY
        extractor.fill(x, y, x + panelW, y + panelH, 0xB0101010.toInt())
        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFF)

        val (label1, label2) = if (displayMode == DisplayMode.OFFSET_SIZE) "Offset" to "Size" else "Min" to "Max"

        extractor.text(font, Component.literal(label1), x + 8, y + 38, 0x888888)
        extractor.text(font, Component.literal("X"), x + 8,   y + 44, 0x555555)
        extractor.text(font, Component.literal("Y"), x + 88,  y + 44, 0x555555)
        extractor.text(font, Component.literal("Z"), x + 168, y + 44, 0x555555)

        extractor.text(font, Component.literal(label2), x + 8, y + 92, 0x888888)
        extractor.text(font, Component.literal("X"), x + 8,   y + 98, 0x555555)
        extractor.text(font, Component.literal("Y"), x + 88,  y + 98, 0x555555)
        extractor.text(font, Component.literal("Z"), x + 168, y + 98, 0x555555)
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBounds() =
        (minecraft?.level?.getBlockEntity(originPos) as? SpecOriginBlockEntity)?.spec?.bounds
}
