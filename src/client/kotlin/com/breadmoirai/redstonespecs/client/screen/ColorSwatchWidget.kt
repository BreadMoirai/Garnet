package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.network.chat.Component

class ColorSwatchWidget(x: Int, y: Int, private var rgb: Int) :
    AbstractWidget(x, y, 16, 16, Component.empty()) {

    fun setColor(newRgb: Int) {
        rgb = newRgb
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(x, y, x + width, y + height, (0xFF000000.toInt() or (rgb and 0xFFFFFF)))
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {}
}
