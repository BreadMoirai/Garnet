package com.breadmoirai.redstonespecs.client.viewport

import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.ui.compose.input.DockInputRouter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

private const val GLFW_KEY_1 = 49

private val keyExplorerFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.dock_explorer_focus", GLFW_KEY_1, KeyMapping.Category.MISC)
)

/**
 * Derives [ViewportState.active] and [ComposeOverlay.enabled] from [DockState.anyActive]: the
 * viewport shrink + Compose overlay render exactly when the dock has something to show, and the
 * game is plain vanilla otherwise. This is the seam that makes the dock keybind self-sufficient
 * (no dependency on the separate V/C debug toggles in `ViewportToggle.kt`) — call it after any
 * `DockState` mutation. Does **not** touch the framebuffer; callers with a live `Window` must
 * follow up with `WindowViewportExt.redstonespecs$updateScaledFramebuffer(true)` to apply it.
 */
fun syncDockViewport() {
    val active = DockState.anyActive()
    ViewportState.active = active
    ComposeOverlay.enabled = active
}

/**
 * Alt+1 focuses the Explorer (releases the cursor, routes input to Compose); Shift+1 toggles the
 * LEFT region's visibility (freeing/reclaiming its inset, which resizes the world). Bound to a
 * single mapping on `1`; the Alt/Shift distinction is read from live GLFW modifier state on click.
 */
fun registerDockKeybinds() {
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        while (keyExplorerFocus.consumeClick()) {
            val handle = mc.window.handle()
            val shift = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
            val alt = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS
            when {
                shift -> {
                    DockState.toggleVisible(DockRegion.LEFT)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) DockInputRouter.clearFocus()
                    else { DockState.setVisible(DockRegion.LEFT, true); DockInputRouter.focus(DockRegion.LEFT) }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
                }
                else -> {} // bare "1" is the vanilla hotbar slot; do nothing here
            }
        }
    }
}
