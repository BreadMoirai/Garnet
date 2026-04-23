package com.breadmoirai.redstonespecs.client.screen

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import kotlin.math.sign

fun parseIntValue(text: String, min: Int, max: Int): Int {
    if (text == "INIT" && min == -1) return -1
    return text.toIntOrNull()?.coerceIn(min, max) ?: min
}

fun formatIntValue(value: Int, min: Int): String =
    if (value == -1 && min == -1) "INIT" else value.toString()

class IntEditBox(
    font: Font,
    width: Int,
    height: Int,
    private val min: Int,
    private val max: Int,
    initial: Int,
    private val onChange: (Int) -> Unit,
) : EditBox(font, width, height, Component.empty()) {

    init {
        setValue(formatIntValue(initial, min))
        setResponder { text ->
            val parsed = parseIntValue(text, min, max)
            onChange(parsed)
        }
    }

    fun getIntValue(): Int = parseIntValue(getValue(), min, max)

    fun setIntValue(n: Int) {
        val clamped = n.coerceIn(min, max)
        setValue(formatIntValue(clamped, min))
        onChange(clamped)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (!isMouseOver(mouseX, mouseY)) return false
        setIntValue(getIntValue() + verticalAmount.sign.toInt())
        return true
    }
}
