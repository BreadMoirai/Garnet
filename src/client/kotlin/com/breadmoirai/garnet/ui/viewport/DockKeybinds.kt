package com.breadmoirai.garnet.ui.viewport

import com.breadmoirai.garnet.ui.dock.DockAutoOpenGate
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.dock.applyDockAutoOpen
import com.breadmoirai.garnet.ui.input.DockInputRouter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

private const val GLFW_KEY_1 = 49

private const val EXPLORER_PANEL_ID = "garnet.explorer"

private val keyExplorerFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.garnet.dock_explorer_focus", GLFW_KEY_1, KeyMapping.Category.MISC)
)

/**
 * Alt+1 focuses the Explorer (releases the cursor, routes input to Compose); Shift+1 toggles the
 * **Explorer panel** — opening it, or closing LEFT when the Explorer is already what LEFT shows, or
 * switching LEFT to the Explorer when it is showing something else (freeing/reclaiming LEFT's inset,
 * which resizes the world). Bound to a single mapping on `1`; the Alt/Shift distinction is read from
 * live GLFW modifier state on click.
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
                    DockState.togglePanel(EXPLORER_PANEL_ID)
                    // Persist here, on the user's keypress, rather than reading DockState at
                    // DISCONNECT: closeAll() hides LEFT on that same event, so a disconnect-time
                    // save would race Fabric's handler ordering and persist the programmatic close
                    // instead of what the player chose. Writing on the keypress also means no
                    // CLIENT_STOPPING backstop is needed for the close-the-window-in-world path.
                    // This keybind is not gated on DockAutoOpenGate.isGarnetServer(), unlike
                    // applyDockAutoOpen() — deliberately: toggling on a vanilla server still
                    // persists "what the player last chose" for the next Garnet join, which is
                    // the one place this keybind's scope and the auto-open gate's scope diverge.
                    // The save, the drop-focus-if-LEFT-just-closed guard, syncDockViewport() and the
                    // framebuffer re-cache all live in commitDockVisibilityChange().
                    commitDockVisibilityChange()
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) {
                        // Focus-only change: visibility is untouched, so nothing to persist.
                        DockInputRouter.clearFocus()
                        commitDockVisibilityChange(persist = false)
                    } else {
                        // Focus needs something to focus: open the Explorer when LEFT is empty, but
                        // leave an already-open Local History alone — Alt+1 is "give the dock the
                        // keyboard", not "switch panels".
                        if (!DockState.isVisible(DockRegion.LEFT)) DockState.showPanel(EXPLORER_PANEL_ID)
                        DockInputRouter.focus(DockRegion.LEFT)
                        commitDockVisibilityChange()
                    }
                }
                else -> {} // bare "1" is the vanilla hotbar slot; do nothing here
            }
        }
    }
}

/**
 * Opens the dock on world join and closes it when the client leaves.
 *
 * JOIN restores the remembered open panels through [applyDockAutoOpen] — gated on the peer being
 * a Garnet server, and deliberately visibility-only, so the game keeps input and the cursor stays
 * grabbed. See `ui/dock/DockAutoOpen.kt` for both decisions.
 *
 * `DISCONNECT` covers every exit path that matters: quit-to-title from singleplayer, a multiplayer
 * disconnect, and a server kick. Without this the dock keeps painting over the title screen and the
 * viewport stays shrunk, because [DockState] is a client-lifetime singleton with no notion of a world.
 *
 * Both bodies end in [commitDockVisibilityChange], the same follow-up both keybind branches run:
 * without its `garnet$updateScaledFramebuffer(true)` the shrink survives until something else resizes
 * the framebuffer. On the JOIN side it is run only when the dock actually opened, since a resize for
 * an unchanged layout is pure churn.
 *
 * Both bodies run inside `mc.execute { ... }` because `fabric-networking-api-v1` fires these events
 * from two sites in `ClientConnectionMixin` — `handleDisconnection` on the main thread, or
 * `channelInactive` on a **Netty event-loop thread**, whichever wins the CAS — and
 * `garnet$updateScaledFramebuffer` reaches `eventHandler.resizeGui()`, which is unsafe to call
 * concurrently with rendering off the render thread.
 *
 * Explorer-specific state (the tree snapshot, its expansion/selection, and the join-time tree
 * request) lives separately in `editor/ui/ExplorerLifecycle.kt`'s `registerExplorerLifecycle()`.
 * This dock shell knows only whether the peer is a Garnet server, asked through [DockAutoOpenGate].
 */
fun registerDockWorldLifecycle() {
    ClientPlayConnectionEvents.JOIN.register { _, _, mc ->
        mc.execute {
            // Only follow up when the dock actually opened: applyDockAutoOpen returns false on a
            // vanilla server, on a remembered-closed dock, and when every remembered panel is
            // already the open one, and
            // a framebuffer resize for a no-op change is pure churn.
            // persist = false on both lifecycle paths: neither is the player choosing a layout.
            // JOIN is replaying what was already saved, and DISCONNECT's closeAll() is exactly the
            // programmatic close the shift-branch comment above refuses to let overwrite the record.
            if (applyDockAutoOpen()) commitDockVisibilityChange(persist = false)
        }
    }
    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        mc.execute {
            DockState.closeAll()
            commitDockVisibilityChange(persist = false)
        }
    }
}
