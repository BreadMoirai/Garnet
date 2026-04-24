package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class DropdownButton<T>(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val font: Font,
    val options: List<T>,
    private val toComponent: (T) -> Component,
    initial: T,
    private val onChange: (T) -> Unit,
) : AbstractWidget(x, y, width, height, toComponent(initial)) {

    var selected: T = initial
        private set

    private var isOpen = false
    private val itemHeight = 14

    fun close() {
        isOpen = false
    }

    override fun extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fill(x, y, x + width, y + height, if (isHovered) 0xFF888888.toInt() else 0xFF666666.toInt())
        // -1 == 0xFFFFFFFF (white, full alpha); 0xFFFFFF alone has alpha=0 and renders as invisible
        graphics.centeredText(font, toComponent(selected), x + width / 2, y + (height - 8) / 2, -1)
        if (!isOpen) return

        graphics.nextStratum()
        // Dropdown items extend below the button and may be clipped by a ScrollableLayout scissor.
        // Pop the current scissor, draw items unclipped, then restore it so the Container's
        // disableScissor() call after the children loop still pops the right element.
        val savedScissor = graphics.scissorStack.peek()
        if (savedScissor != null) graphics.disableScissor()

        val listY = y + height
        for ((i, option) in options.withIndex()) {
            val iy = listY + i * itemHeight
            val hovered = mouseX in x until x + width && mouseY in iy until iy + itemHeight
            graphics.fill(x, iy, x + width, iy + itemHeight,
                if (hovered) 0xCC555555.toInt() else 0xCC333333.toInt())
            graphics.text(font, toComponent(option), x + 4, iy + 3, -1)
        }

        if (savedScissor != null) {
            graphics.enableScissor(savedScissor.left(), savedScissor.top(), savedScissor.right(), savedScissor.bottom())
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, consumed: Boolean): Boolean {
        if (!active || !visible) return false
        if (isOpen) {
            val listY = y + height
            val mx = event.x().toInt()
            val my = event.y().toInt()
            for ((i, option) in options.withIndex()) {
                val iy = listY + i * itemHeight
                if (mx in x until x + width && my in iy until iy + itemHeight) {
                    selected = option
                    onChange(option)
                    isOpen = false
                    return true
                }
            }
            isOpen = false
            return true
        }
        if (isMouseOver(event.x(), event.y())) {
            isOpen = true
            return true
        }
        return false
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
