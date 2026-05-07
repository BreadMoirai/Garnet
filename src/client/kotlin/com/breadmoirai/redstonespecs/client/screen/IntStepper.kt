package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.StringWidget
import net.minecraft.client.gui.layouts.LinearLayout
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.network.chat.Component

private class StepButton(
    x: Int, y: Int, width: Int, height: Int, message: Component,
    private val onStep: (InputWithModifiers) -> Unit,
) : Button(x, y, width, height, message, OnPress {}, DEFAULT_NARRATION) {
    override fun onPress(input: InputWithModifiers) = onStep(input)

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractDefaultSprite(graphics)
        extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE))
    }
}

fun intStepper(
    font: Font,
    width: Int,
    height: Int,
    min: Int,
    max: Int,
    value: Int,
    onChange: (Int) -> Unit,
): LinearLayout {
    val btnW = 14
    val labelW = (width - 2 * btnW - 2).coerceAtLeast(20)

    fun step(input: InputWithModifiers, dir: Int) {
        val mag = when {
            input.hasShiftDown() -> 100
            input.hasControlDown() -> 10
            else -> 1
        }
        onChange((value + dir * mag).coerceIn(min, max))
    }

    val layout = LinearLayout.horizontal().spacing(1)
    layout.addChild(StepButton(0, 0, btnW, height, Component.literal("-")) { step(it, -1) })
    layout.addChild(StringWidget(labelW, height, Component.literal(formatIntValue(value, min)), font))
    layout.addChild(StepButton(0, 0, btnW, height, Component.literal("+")) { step(it, 1) })
    return layout
}
