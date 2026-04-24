package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.RedstoneSpecBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import dev.isxander.yacl3.gui.LowProfileButtonWidget
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
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

    // IntEditBox refs for reading in save()
    private var box1x: IntEditBox? = null; private var box1y: IntEditBox? = null; private var box1z: IntEditBox? = null
    private var box2x: IntEditBox? = null; private var box2y: IntEditBox? = null; private var box2z: IntEditBox? = null

    // Load initial values from block entity only once
    private var valuesLoaded = false

    override fun isPauseScreen(): Boolean = false
    override fun isInGameUi(): Boolean = true


    override fun init() {
        super.init()

        if (!valuesLoaded) {
            val bounds = getBounds() ?: BoundingBox(-4, -1, -4, 4, 3, 4)
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
            onChange1x = { v1x = it },
            onChange1y = { v1y = it },
            onChange1z = { v1z = it },
            assignBoxes = { bx, by, bz -> box1x = bx; box1y = by; box1z = bz }
        )

        val row2 = buildCoordRow(label2, v2x, v2y, v2z,
            onChange1x = { v2x = it },
            onChange1y = { v2y = it },
            onChange1z = { v2z = it },
            assignBoxes = { bx, by, bz -> box2x = bx; box2y = by; box2z = bz }
        )

        // Bottom buttons
        val saveBtn = LowProfileButtonWidget(0, 0, 60, 20, Component.literal("Save")) { save() }
        val cancelBtn = LowProfileButtonWidget(0, 0, 60, 20, CommonComponents.GUI_CANCEL) { onClose() }

        val bottomRow = LinearLayout.horizontal().spacing(8)
        bottomRow.addChild(saveBtn)
        bottomRow.addChild(cancelBtn)

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
        assignBoxes: (IntEditBox, IntEditBox, IntEditBox) -> Unit,
    ): LinearLayout {
        val row = LinearLayout.horizontal().spacing(4)

        val labelWidget = StringWidget(60, 20, Component.literal(label), font)
        row.addChild(labelWidget)

        val bx = makeFieldGroup(initX, onChange1x)
        val by = makeFieldGroup(initY, onChange1y)
        val bz = makeFieldGroup(initZ, onChange1z)
        assignBoxes(bx.second, by.second, bz.second)

        // X axis group
        val xLabel = StringWidget(10, 20, Component.literal("X"), font)
        row.addChild(xLabel)
        row.addChild(bx.first)

        // Y axis group
        val yLabel = StringWidget(10, 20, Component.literal("Y"), font)
        row.addChild(yLabel)
        row.addChild(by.first)

        // Z axis group
        val zLabel = StringWidget(10, 20, Component.literal("Z"), font)
        row.addChild(zLabel)
        row.addChild(bz.first)

        return row
    }

    /**
     * Returns a Pair of (container LinearLayout, IntEditBox) for a single field with − and + buttons.
     */
    private fun makeFieldGroup(initial: Int, onChange: (Int) -> Unit): Pair<LinearLayout, IntEditBox> {
        val box = IntEditBox(font, 52, 20, Int.MIN_VALUE, Int.MAX_VALUE, initial, onChange)
        val decBtn = LowProfileButtonWidget(0, 0, 20, 20, Component.literal("−")) {
            box.setIntValue(box.getIntValue() - 1)
        }
        val incBtn = LowProfileButtonWidget(0, 0, 20, 20, Component.literal("+")) {
            box.setIntValue(box.getIntValue() + 1)
        }
        val group = LinearLayout.horizontal().spacing(2)
        group.addChild(decBtn)
        group.addChild(box)
        group.addChild(incBtn)
        return Pair(group, box)
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

        // Read current live values from boxes (may differ from stored v1/v2 if user is mid-edit)
        val cur1x = box1x?.getIntValue() ?: v1x
        val cur1y = box1y?.getIntValue() ?: v1y
        val cur1z = box1z?.getIntValue() ?: v1z
        val cur2x = box2x?.getIntValue() ?: v2x
        val cur2y = box2y?.getIntValue() ?: v2y
        val cur2z = box2z?.getIntValue() ?: v2z

        // Convert current mode values to canonical min/max
        val minX: Int; val minY: Int; val minZ: Int
        val maxX: Int; val maxY: Int; val maxZ: Int
        if (displayMode == DisplayMode.OFFSET_SIZE) {
            val sx = cur2x.coerceAtLeast(1); val sy = cur2y.coerceAtLeast(1); val sz = cur2z.coerceAtLeast(1)
            minX = cur1x; minY = cur1y; minZ = cur1z
            maxX = cur1x + sx - 1; maxY = cur1y + sy - 1; maxZ = cur1z + sz - 1
        } else {
            minX = minOf(cur1x, cur2x); minY = minOf(cur1y, cur2y); minZ = minOf(cur1z, cur2z)
            maxX = maxOf(cur1x, cur2x); maxY = maxOf(cur1y, cur2y); maxZ = maxOf(cur1z, cur2z)
        }

        // Convert canonical min/max to new mode
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

    private fun save() {
        val cur1x = box1x?.getIntValue() ?: return
        val cur1y = box1y?.getIntValue() ?: return
        val cur1z = box1z?.getIntValue() ?: return
        val cur2x = box2x?.getIntValue() ?: return
        val cur2y = box2y?.getIntValue() ?: return
        val cur2z = box2z?.getIntValue() ?: return

        val newBounds = if (displayMode == DisplayMode.OFFSET_SIZE) {
            BoundingBox(
                cur1x, cur1y, cur1z,
                cur1x + cur2x.coerceAtLeast(1) - 1,
                cur1y + cur2y.coerceAtLeast(1) - 1,
                cur1z + cur2z.coerceAtLeast(1) - 1,
            )
        } else {
            BoundingBox(
                minOf(cur1x, cur2x), minOf(cur1y, cur2y), minOf(cur1z, cur2z),
                maxOf(cur1x, cur2x), maxOf(cur1y, cur2y), maxOf(cur1z, cur2z),
            )
        }

        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, newBounds))
        onClose()
    }

    private fun getBounds() =
        (minecraft.level?.getBlockEntity(originPos) as? RedstoneSpecBlockEntity)?.spec?.bounds
}
