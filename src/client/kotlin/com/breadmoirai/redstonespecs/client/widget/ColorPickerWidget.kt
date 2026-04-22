package com.breadmoirai.redstonespecs.client.widget

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component

class ColorPickerWidget(
    x: Int, y: Int, width: Int, height: Int,
    initialColor: Int,
) : AbstractWidget(x, y, width, height, Component.empty()) {

    var color: Int = initialColor and 0xFFFFFF
        private set

    private var dropdownOpen = false
    private var hexBox: EditBox? = null

    private val dropW = 4 * SWATCH_SIZE + 2 * PAD
    private val dropH = 4 * SWATCH_SIZE + PAD + HEX_ROW_H + 2 * PAD

    companion object {
        private const val SWATCH_SIZE = 20
        private const val PAD = 4
        private const val HEX_ROW_H = 14

        val DYE_COLORS: List<Pair<String, Int>> = listOf(
            "White"       to 0xF9FFFE,
            "Orange"      to 0xF9801D,
            "Magenta"     to 0xC74EBD,
            "Light Blue"  to 0x3AB3DA,
            "Yellow"      to 0xFED83D,
            "Lime"        to 0x80C71F,
            "Pink"        to 0xF38BAA,
            "Gray"        to 0x474F52,
            "Light Gray"  to 0x9D9D97,
            "Cyan"        to 0x169C9C,
            "Purple"      to 0x8932B8,
            "Blue"        to 0x3C44AA,
            "Brown"       to 0x835432,
            "Green"       to 0x5E7C16,
            "Red"         to 0xB02E26,
            "Black"       to 0x1D1D21,
        )

        fun nearestDyeName(color: Int): String {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF
            return DYE_COLORS.minByOrNull { (_, c) ->
                val dr = r - ((c shr 16) and 0xFF)
                val dg = g - ((c shr 8) and 0xFF)
                val db = b - (c and 0xFF)
                dr * dr + dg * dg + db * db
            }?.first ?: "Custom"
        }
    }

    /**
     * Opens the dropdown and returns the hex EditBox.
     * The caller is responsible for registering the returned EditBox with their screen
     * (e.g. via `addRenderableWidget`).
     *
     * If already open, returns the existing [EditBox] so it can be re-registered after
     * a `rebuildWidgets()` call without resetting dropdown state.
     *
     * @return the [EditBox] for the hex input field
     */
    fun openDropdown(): EditBox {
        if (!dropdownOpen) {
            dropdownOpen = true
            val font = Minecraft.getInstance().font
            hexBox = EditBox(font, x, y + height + dropH - HEX_ROW_H - PAD, dropW - SWATCH_SIZE - PAD, HEX_ROW_H, Component.empty()).also {
                it.value = String.format("%06X", color)
                it.setMaxLength(6)
            }
        }
        return hexBox!!
    }

    /**
     * Closes the dropdown and clears the [hexBox] reference.
     *
     * NOTE: [Screen.removeWidget] is protected and cannot be called from here.
     * Callers that registered the [EditBox] from [openDropdown] must call
     * `rebuildWidgets()` after this to remove the stale widget from the screen.
     */
    fun closeDropdown() {
        dropdownOpen = false
        hexBox = null
    }

    fun isDropdownOpen() = dropdownOpen

    /** Returns the dropdown panel bounding box [x, y, x+w, y+h] relative to screen. */
    fun dropdownBounds(): IntArray = intArrayOf(x, y + height, x + dropW, y + height + dropH)

    override fun extractWidgetRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Button background
        extractor.fill(x, y, x + width, y + height, 0xFF333333.toInt())
        extractor.fill(x + 1, y + 1, x + 1 + 12, y + height - 1, color or 0xFF000000.toInt())
        val name = nearestDyeName(color)
        val hex = String.format("#%06X", color)
        extractor.text(Minecraft.getInstance().font, "$name  $hex", x + 16, y + (height - 8) / 2, 0xFFFFFFFF.toInt())

        if (dropdownOpen) extractDropdownRenderState(extractor, mouseX, mouseY)
    }

    private fun extractDropdownRenderState(extractor: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val dx = x
        val dy = y + height
        extractor.fill(dx, dy, dx + dropW, dy + dropH, 0xFF222222.toInt())

        // 4×4 swatch grid
        DYE_COLORS.forEachIndexed { i, (_, c) ->
            val col = i % 4
            val row = i / 4
            val sx = dx + PAD + col * SWATCH_SIZE
            val sy = dy + PAD + row * SWATCH_SIZE
            extractor.fill(sx, sy, sx + SWATCH_SIZE - 1, sy + SWATCH_SIZE - 1, c or 0xFF000000.toInt())
            if (mouseX in sx until sx + SWATCH_SIZE && mouseY in sy until sy + SWATCH_SIZE) {
                extractor.fill(sx, sy, sx + SWATCH_SIZE - 1, sy + SWATCH_SIZE - 1, 0x44FFFFFF)
            }
        }

        // Hex preview swatch
        val hexY = dy + PAD + 4 * SWATCH_SIZE
        val hexInput = hexBox?.value ?: String.format("%06X", color)
        val previewColor = hexInput.toLongOrNull(16)?.toInt() ?: color
        extractor.fill(dx + dropW - SWATCH_SIZE, hexY, dx + dropW, hexY + HEX_ROW_H, previewColor or 0xFF000000.toInt())
    }

    override fun mouseClicked(event: MouseButtonEvent, unknownBoolean: Boolean): Boolean {
        val mouseX = event.x()
        val mouseY = event.y()

        if (!dropdownOpen) {
            if (isHovered) {
                dropdownOpen = true
                return true
            }
            return false
        }

        // Click on swatch grid
        val dx = x; val dy = y + height
        DYE_COLORS.forEachIndexed { i, (_, c) ->
            val col = i % 4; val row = i / 4
            val sx = dx + PAD + col * SWATCH_SIZE
            val sy = dy + PAD + row * SWATCH_SIZE
            if (mouseX >= sx && mouseX < sx + SWATCH_SIZE && mouseY >= sy && mouseY < sy + SWATCH_SIZE) {
                color = c
                hexBox?.value = String.format("%06X", c)
                dropdownOpen = false
                hexBox = null
                return true
            }
        }

        // Forward clicks to hexBox for keyboard focus
        hexBox?.mouseClicked(event, unknownBoolean)

        // Outside dropdown: close
        val bounds = dropdownBounds()
        if (mouseX < bounds[0] || mouseX > bounds[2] || mouseY < bounds[1] || mouseY > bounds[3]) {
            applyHexInput()
            closeDropdown()
        }
        return super.mouseClicked(event, unknownBoolean)
    }

    private fun applyHexInput() {
        val hex = hexBox?.value ?: return
        val parsed = hex.toLongOrNull(16) ?: return
        color = parsed.toInt() and 0xFFFFFF
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {}
}
