package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.CycleButton
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.level.levelgen.structure.BoundingBox

class SpecBoundsScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_bounds")) {

    private enum class DisplayMode { OFFSET_SIZE, MIN_MAX }

    private var displayMode = DisplayMode.OFFSET_SIZE

    // Stored values — updated live via onChange callbacks
    private var v1x = 0; private var v1y = 0; private var v1z = 0
    private var v2x = 1; private var v2y = 1; private var v2z = 1

    // Original bounds for Revert
    private var originalBounds: BoundingBox? = null

    // Load initial values from block entity only once
    private var valuesLoaded = false

    override fun isPauseScreen(): Boolean = false
    override fun isInGameUi(): Boolean = true


    override fun init() {
        super.init()

        if (!valuesLoaded) {
            val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)
            originalBounds = bounds
            loadFromBounds(bounds)
            valuesLoaded = true
        }

        // Row labels depend on mode
        val label1 = if (displayMode == DisplayMode.OFFSET_SIZE) "Offset" else "Min"
        val label2 = if (displayMode == DisplayMode.OFFSET_SIZE) "Size" else "Max"

        // Mode cycle button
        val modeButton = CycleButton.builder<DisplayMode>(
            { mode -> Component.literal(if (mode == DisplayMode.OFFSET_SIZE) "Offset / Size" else "Min / Max") },
            displayMode,
        ).withValues(*DisplayMode.entries.toTypedArray())
            .displayOnlyValue()
            .create(0, 0, 120, 20, Component.empty()) { _, value ->
                switchMode(value)
            }

        // Build coordinate rows
        val row1 = buildCoordRow(label1, v1x, v1y, v1z,
            onChange1x = { v1x = it; sendBounds() },
            onChange1y = { v1y = it; sendBounds() },
            onChange1z = { v1z = it; sendBounds() },
        )

        val row2 = buildCoordRow(label2, v2x, v2y, v2z,
            onChange1x = { v2x = it; sendBounds() },
            onChange1y = { v2y = it; sendBounds() },
            onChange1z = { v2z = it; sendBounds() },
        )

        // Bottom buttons
        val revertBtn = Button.builder(Component.literal("Revert")) { revert() }.pos(0, 0).width(60).build()
        val doneBtn = Button.builder(CommonComponents.GUI_DONE) { onClose() }.pos(0, 0).width(60).build()

        val bottomRow = LinearLayout.horizontal().spacing(8)
        bottomRow.addChild(revertBtn)
        bottomRow.addChild(doneBtn)

        // Main content layout (vertical)
        val content = LinearLayout.vertical().spacing(6)
        content.addChild(modeButton)
        content.addChild(SpacerElement(0, 4))
        content.addChild(row1)
        content.addChild(row2)
        content.addChild(SpacerElement(0, 4))
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    /**
     * Build a single coordinate row: [label] [−][field][+] X  [−][field][+] Y  [−][field][+] Z
     */
    private fun buildCoordRow(
        label: String,
        initX: Int, initY: Int, initZ: Int,
        onChange1x: (Int) -> Unit,
        onChange1y: (Int) -> Unit,
        onChange1z: (Int) -> Unit,
    ): LinearLayout {
        val row = LinearLayout.horizontal().spacing(4)

        row.addChild(StringWidget(60, 20, Component.literal(label), font))

        row.addChild(StringWidget(10, 20, Component.literal("X"), font))
        row.addChild(makeFieldGroup(initX, onChange1x))

        row.addChild(StringWidget(10, 20, Component.literal("Y"), font))
        row.addChild(makeFieldGroup(initY, onChange1y))

        row.addChild(StringWidget(10, 20, Component.literal("Z"), font))
        row.addChild(makeFieldGroup(initZ, onChange1z))

        return row
    }

    /**
     * Returns a LinearLayout containing − field + for a single axis.
     */
    private fun makeFieldGroup(initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = IntEditBox(font, 52, 20, Int.MIN_VALUE, Int.MAX_VALUE, initial, onChange)
        val decBtn = Button.builder(Component.literal("−")) { box.setIntValue(box.getIntValue() - 1) }.pos(0, 0).width(20).build()
        val incBtn = Button.builder(Component.literal("+")) { box.setIntValue(box.getIntValue() + 1) }.pos(0, 0).width(20).build()
        val group = LinearLayout.horizontal().spacing(2)
        group.addChild(decBtn)
        group.addChild(box)
        group.addChild(incBtn)
        return group
    }

    /**
     * Load v1/v2 from a BoundingBox according to current displayMode.
     * In OFFSET_SIZE mode: v1 = minXYZ, v2 = sizeXYZ (always >= 1).
     * In MIN_MAX mode: v1 = minXYZ, v2 = maxXYZ.
     */
    private fun loadFromBounds(b: BoundingBox) {
        if (displayMode == DisplayMode.OFFSET_SIZE) {
            v1x = b.minX(); v1y = b.minY(); v1z = b.minZ()
            v2x = b.maxX() - b.minX() + 1
            v2y = b.maxY() - b.minY() + 1
            v2z = b.maxZ() - b.minZ() + 1
        } else {
            v1x = b.minX(); v1y = b.minY(); v1z = b.minZ()
            v2x = b.maxX(); v2y = b.maxY(); v2z = b.maxZ()
        }
    }

    /**
     * Switch display mode, converting current v1/v2 values through canonical min/max.
     */
    private fun switchMode(newMode: DisplayMode) {
        if (newMode == displayMode) return

        val minX: Int; val minY: Int; val minZ: Int
        val maxX: Int; val maxY: Int; val maxZ: Int
        if (displayMode == DisplayMode.OFFSET_SIZE) {
            val sx = v2x.coerceAtLeast(1); val sy = v2y.coerceAtLeast(1); val sz = v2z.coerceAtLeast(1)
            minX = v1x; minY = v1y; minZ = v1z
            maxX = v1x + sx - 1; maxY = v1y + sy - 1; maxZ = v1z + sz - 1
        } else {
            minX = minOf(v1x, v2x); minY = minOf(v1y, v2y); minZ = minOf(v1z, v2z)
            maxX = maxOf(v1x, v2x); maxY = maxOf(v1y, v2y); maxZ = maxOf(v1z, v2z)
        }

        if (newMode == DisplayMode.OFFSET_SIZE) {
            v1x = minX; v1y = minY; v1z = minZ
            v2x = maxX - minX + 1; v2y = maxY - minY + 1; v2z = maxZ - minZ + 1
        } else {
            v1x = minX; v1y = minY; v1z = minZ
            v2x = maxX; v2y = maxY; v2z = maxZ
        }

        displayMode = newMode
        rebuildWidgets()
    }

    private fun sendBounds() {
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
    }

    private fun revert() {
        val b = originalBounds ?: return
        loadFromBounds(b)
        rebuildWidgets()
        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, b))
    }

    private fun getBounds() =
        (minecraft.level?.getBlockEntity(originPos) as? SpecBlockEntity)?.spec?.bounds
}
