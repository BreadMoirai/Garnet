package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
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

    private val panelW = 260
    private val panelH = 114
    private val panelX get() = (width - panelW) / 2
    private val panelY get() = (height - panelH) / 2

    private var displayMode = DisplayMode.OFFSET_SIZE

    // Row 1: first triple (Offset X/Y/Z or Min X/Y/Z)
    private var box1x: EditBox? = null
    private var box1y: EditBox? = null
    private var box1z: EditBox? = null

    // Row 2: second triple (Size X/Y/Z or Max X/Y/Z)
    private var box2x: EditBox? = null
    private var box2y: EditBox? = null
    private var box2z: EditBox? = null

    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()
        val x = panelX
        val y = panelY
        val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)

        // Mode toggle — top-right of header
        addRenderableWidget(
            CycleButton.builder<DisplayMode>(
                { mode -> Component.literal(if (mode == DisplayMode.OFFSET_SIZE) "Offset / Size" else "Corners") },
                displayMode,
            ).withValues(*DisplayMode.entries.toTypedArray())
                .create(x + panelW - 90, y + 8, 86, 18, Component.empty()) { _, value ->
                    displayMode = value
                    rebuildWidgets()
                }
        )

        val (v1x, v1y, v1z, v2x, v2y, v2z) = toDisplayValues(bounds)

        // Row 1 at y+36: label + X, Y, Z boxes
        box1x = addBox(x + 68, y + 36, v1x)
        box1y = addBox(x + 130, y + 36, v1y)
        box1z = addBox(x + 192, y + 36, v1z)

        // Row 2 at y+58: label + X, Y, Z boxes
        box2x = addBox(x + 68, y + 58, v2x)
        box2y = addBox(x + 130, y + 58, v2y)
        box2z = addBox(x + 192, y + 58, v2z)

        addRenderableWidget(
            Button.builder(Component.literal("Save")) { save() }
                .bounds(x + 8, y + panelH - 26, 60, 20).build()
        )
        addRenderableWidget(
            Button.builder(CommonComponents.GUI_CANCEL) { onClose() }
                .bounds(x + panelW - 68, y + panelH - 26, 60, 20).build()
        )
    }

    private fun addBox(bx: Int, by: Int, value: Int): EditBox =
        EditBox(font, bx, by, 48, 18, Component.empty()).also {
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
        val x = panelX
        val y = panelY

        super.extractRenderState(extractor, mouseX, mouseY, partialTick)

        // Title
        extractor.centeredText(font, title, x + panelW / 2, y + 6, 0xFFFFFFFF.toInt())

        val (label1, label2) = if (displayMode == DisplayMode.OFFSET_SIZE) "Offset" to "Size" else "Min" to "Max"

        // Row 1 labels
        extractor.text(font, Component.literal(label1), x + 8, y + 41, 0xFF888888.toInt())
        extractor.text(font, Component.literal("X"), x + 60, y + 41, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Y"), x + 122, y + 41, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Z"), x + 184, y + 41, 0xFFAAAAAA.toInt())

        // Row 2 labels
        extractor.text(font, Component.literal(label2), x + 8, y + 63, 0xFF888888.toInt())
        extractor.text(font, Component.literal("X"), x + 60, y + 63, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Y"), x + 122, y + 63, 0xFFAAAAAA.toInt())
        extractor.text(font, Component.literal("Z"), x + 184, y + 63, 0xFFAAAAAA.toInt())
    }

    override fun isPauseScreen(): Boolean = false

    private fun getBounds() =
        (minecraft?.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity)?.spec?.bounds
}
