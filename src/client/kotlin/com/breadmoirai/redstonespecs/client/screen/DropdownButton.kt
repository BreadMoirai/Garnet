package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

interface DropdownHost {
    fun getOpenDropdown(): DropdownButton<*>?
    fun setOpenDropdown(d: DropdownButton<*>?)
}

class DropdownButton<T>(
    private val host: DropdownHost,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    private val font: Font,
    val options: List<T>,
    private val toComponent: (T) -> Component,
    initial: T,
    private val onChange: (T) -> Unit,
) : Button(x, y, width, height, toComponent(initial), OnPress {}, DEFAULT_NARRATION) {

    var selected: T = initial
        private set

    private val itemHeight = 16

    private val buttonSprite = Identifier.withDefaultNamespace("widget/button")
    private val buttonHighlightedSprite = Identifier.withDefaultNamespace("widget/button_highlighted")

    val isOpen: Boolean
        get() = host.getOpenDropdown() === this

    fun close() {
        if (isOpen) host.setOpenDropdown(null)
    }

    override fun onPress(input: InputWithModifiers) {
        if (isOpen) host.setOpenDropdown(null) else host.setOpenDropdown(this)
    }

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractDefaultSprite(graphics)
        graphics.centeredText(font, toComponent(selected), x + width / 2, y + (height - 8) / 2, -1)
    }

    fun extractPopup(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val listY = y + height
        for ((i, option) in options.withIndex()) {
            val iy = listY + i * itemHeight
            val hovered = mouseX in x until x + width && mouseY in iy until iy + itemHeight
            graphics.blitSprite(
                RenderPipelines.GUI_TEXTURED,
                if (hovered) buttonHighlightedSprite else buttonSprite,
                x, iy, width, itemHeight,
            )
            graphics.centeredText(font, toComponent(option), x + width / 2, iy + (itemHeight - 8) / 2, -1)
        }
    }

    fun popupMouseClicked(event: MouseButtonEvent): Boolean {
        val mx = event.x().toInt()
        val my = event.y().toInt()
        val listY = y + height
        for ((i, option) in options.withIndex()) {
            val iy = listY + i * itemHeight
            if (mx in x until x + width && my in iy until iy + itemHeight) {
                playDownSound(Minecraft.getInstance().soundManager)
                selected = option
                onChange(option)
                host.setOpenDropdown(null)
                return true
            }
        }
        val onButton = mx in x until x + width && my in y until y + height
        host.setOpenDropdown(null)
        return onButton
    }
}
