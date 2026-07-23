package com.breadmoirai.redstonespecs.client.viewport

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft

// GLFW_KEY_V. `[`/`]` (91/93) are already used for spec-marker cycling; `\` (92) for cycle_backward.
private const val GLFW_KEY_V = 86

private val keyToggleViewportShrink = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.viewport_shrink_toggle", GLFW_KEY_V, KeyMapping.Category.MISC)
)

/**
 * Registers the spike keybind that flips [ViewportState.active] and asks the [WindowMixin][
 * com.breadmoirai.redstonespecs.mixin.client.WindowMixin] to recompute its framebuffer-size
 * override immediately, so the effect is visible without a manual window resize.
 */
fun registerViewportToggle() {
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        while (keyToggleViewportShrink.consumeClick()) {
            ViewportState.active = !ViewportState.active
            // Window is `final` at compile time, so the mixin-added interface must be reached
            // through an unchecked `Any` cast (mirrors Java's `(WindowExt)(Object)window` idiom).
            val windowExt = (mc.window as Any) as WindowViewportExt
            windowExt.`redstonespecs$updateScaledFramebuffer`(true)
        }
    }
}
