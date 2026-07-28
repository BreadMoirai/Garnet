package com.breadmoirai.garnet.client.screen

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * Square icon-only button that paints a redstone-dust sprite over the standard button
 * background. Used for the title-screen "Project Specs" entry point so the affordance
 * matches the mod's identity at a glance.
 *
 * The icon is sourced from a GUI sprite (`garnet:icon/redstone`) rather than from
 * `ItemStack(Items.REDSTONE)`: vanilla item-component holders are not bound when the title
 * screen first renders, so constructing an ItemStack there throws
 * `Components not bound yet`.
 */
class GarnetIconButton(
    x: Int,
    y: Int,
    size: Int,
    narration: Component,
    onPress: OnPress,
) : Button(x, y, size, size, narration, onPress, DEFAULT_NARRATION) {

    override fun extractContents(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        extractDefaultSprite(graphics)
        val ix = x + (width - ICON_SIZE) / 2
        val iy = getY() + (height - ICON_SIZE) / 2
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, ICON, ix, iy, ICON_SIZE, ICON_SIZE)
    }

    companion object {
        private const val ICON_SIZE = 16
        private val ICON: Identifier = Identifier.fromNamespaceAndPath("garnet", "icon/redstone")
    }
}
