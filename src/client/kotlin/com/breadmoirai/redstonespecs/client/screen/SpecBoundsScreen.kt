package com.breadmoirai.redstonespecs.client.screen

import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.network.ResizeBoundsC2SPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.FrameLayout
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.gui.layouts.SpacerElement
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component

class SpecBoundsScreen(private val originPos: BlockPos) :
    Screen(Component.translatable("screen.redstonespecs.spec_bounds")) {

    private var sx = 5
    private var sy = 5
    private var sz = 5

    private var originalBounds: Vec3i? = null
    private var valuesLoaded = false

    override fun isPauseScreen(): Boolean = false
    override fun isInGameUi(): Boolean = true

    override fun init() {
        super.init()

        if (!valuesLoaded) {
            val bounds = getBounds() ?: Vec3i(5, 5, 5)
            originalBounds = bounds
            sx = bounds.x; sy = bounds.y; sz = bounds.z
            valuesLoaded = true
        }

        val row = buildSizeRow()

        val revertBtn = Button.builder(Component.literal("Revert")) { revert() }.pos(0, 0).width(60).build()
        val doneBtn = Button.builder(CommonComponents.GUI_DONE) { onClose() }.pos(0, 0).width(60).build()
        val bottomRow = LinearLayout.horizontal().spacing(8)
        bottomRow.addChild(revertBtn)
        bottomRow.addChild(doneBtn)

        val content = LinearLayout.vertical().spacing(6)
        content.addChild(StringWidget(0, 20, Component.literal("Size (X / Y / Z)"), font))
        content.addChild(SpacerElement(0, 4))
        content.addChild(row)
        content.addChild(SpacerElement(0, 4))
        content.addChild(bottomRow)

        content.arrangeElements()
        FrameLayout.centerInRectangle(content, 10, 10, width - 10, height - 10)
        content.visitWidgets { addRenderableWidget(it) }
    }

    private fun buildSizeRow(): LinearLayout {
        val row = LinearLayout.horizontal().spacing(4)
        row.addChild(StringWidget(10, 20, Component.literal("X"), font))
        row.addChild(makeFieldGroup(sx) { sx = it.coerceAtLeast(1); send() })
        row.addChild(StringWidget(10, 20, Component.literal("Y"), font))
        row.addChild(makeFieldGroup(sy) { sy = it.coerceAtLeast(1); send() })
        row.addChild(StringWidget(10, 20, Component.literal("Z"), font))
        row.addChild(makeFieldGroup(sz) { sz = it.coerceAtLeast(1); send() })
        return row
    }

    private fun makeFieldGroup(initial: Int, onChange: (Int) -> Unit): LinearLayout {
        val box = IntEditBox(font, 52, 20, 1, Int.MAX_VALUE, initial, onChange)
        val decBtn = Button.builder(Component.literal("−")) {
            box.setIntValue((box.getIntValue() - 1).coerceAtLeast(1))
        }.pos(0, 0).width(20).build()
        val incBtn = Button.builder(Component.literal("+")) {
            box.setIntValue(box.getIntValue() + 1)
        }.pos(0, 0).width(20).build()
        val group = LinearLayout.horizontal().spacing(2)
        group.addChild(decBtn)
        group.addChild(box)
        group.addChild(incBtn)
        return group
    }

    private fun send() {
        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, sx, sy, sz))
    }

    private fun revert() {
        val b = originalBounds ?: return
        sx = b.x; sy = b.y; sz = b.z
        rebuildWidgets()
        ClientPlayNetworking.send(ResizeBoundsC2SPayload(originPos, sx, sy, sz))
    }

    private fun getBounds(): Vec3i? =
        (minecraft.level?.getBlockEntity(originPos) as? SpecBlockEntity)?.spec?.bounds
}
