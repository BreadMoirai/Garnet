package com.breadmoirai.garnet.ui.viewport

import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.input.DockInputRouter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

private const val GLFW_KEY_1 = 49

private val keyExplorerFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.garnet.dock_explorer_focus", GLFW_KEY_1, KeyMapping.Category.MISC)
)

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
                // No world: the dock's panels describe a session that does not exist. The click is
                // still consumed above so presses do not stack up and fire on the next world join.
                mc.level == null -> {}
                shift -> {
                    DockState.toggleVisible(DockRegion.LEFT)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) DockInputRouter.clearFocus()
                    else { DockState.setVisible(DockRegion.LEFT, true); DockInputRouter.focus(DockRegion.LEFT) }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
                else -> {} // bare "1" is the vanilla hotbar slot; do nothing here
            }
        }
    }
}

/**
 * Closes the dock when the client leaves a world. `DISCONNECT` covers every exit path that matters:
 * quit-to-title from singleplayer, a multiplayer disconnect, and a server kick. Without this the
 * dock keeps painting over the title screen and the viewport stays shrunk, because [DockState] is a
 * client-lifetime singleton with no notion of a world.
 *
 * The `garnet$updateScaledFramebuffer(true)` follow-up mirrors both keybind branches above: without
 * it the shrink survives until something else resizes the framebuffer.
 *
 * The whole body runs inside `mc.execute { ... }` because `fabric-networking-api-v1` fires this
 * event from two sites in `ClientConnectionMixin` — `handleDisconnection` on the main thread, or
 * `channelInactive` on a **Netty event-loop thread**, whichever wins the CAS — and
 * `garnet$updateScaledFramebuffer` reaches `eventHandler.resizeGui()`, which is unsafe to call
 * concurrently with rendering off the render thread.
 *
 * Explorer-specific state reset lives separately in `editor/ui/ExplorerLifecycle.kt`'s
 * `registerExplorerLifecycle()` — this dock shell has no notion of the Explorer feature.
 */
fun registerDockWorldLifecycle() {
    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        mc.execute {
            DockState.closeAll()
            syncDockViewport()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
    }
}
