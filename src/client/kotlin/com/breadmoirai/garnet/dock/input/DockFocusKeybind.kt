package com.breadmoirai.garnet.dock.input

import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.viewport.commitDockVisibilityChange
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping

// GLFW_KEY_G. Unbound in vanilla, and free here: `V` is the viewport-shrink toggle, `C` the Compose
// overlay toggle, `1` the dock's Alt/Shift keybind, `[`/`]`/`\` spec-marker cycling.
private const val GLFW_KEY_G = 71

private val keyDockFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.garnet.dock_focus", GLFW_KEY_G, KeyMapping.Category.MISC)
)

/**
 * Whether [glfwKey] is the key currently bound to the dock-focus keybind.
 *
 * Asked by [DockInputRouter.onGlfwKey], which is the *only* way this keybind can fire while the dock
 * already has focus: `KeyboardHandlerMixin` cancels every key event while captured, so vanilla never
 * ticks the [KeyMapping] and `consumeClick()` below never sees the press. Routed through
 * `KeyMapping.matches` rather than comparing against [GLFW_KEY_G] so rebinding the key in the
 * vanilla Controls screen moves *both* halves of the toggle, not just the entering half.
 */
fun isDockFocusKey(glfwKey: Int): Boolean =
    keyDockFocus.matches(InputConstants.Type.KEYSYM.getOrCreate(glfwKey))

/**
 * `G` — hand the cursor and keyboard to the Garnet dock, or give them back to the game.
 *
 * The one keybind a player needs to go from playing to using the UI: it frees the OS cursor (via
 * [DockInputRouter.focus], which calls `MouseHandler.releaseMouse`) and starts routing GLFW input
 * into the dock's Compose scene. Pressing it again — like ESC, or clicking the bare world — hands
 * both back and re-grabs the cursor without the camera snapping.
 *
 * Leaving again is symmetric — the same key — but that half is handled in [DockInputRouter.onGlfwKey]
 * for the reason [isDockFocusKey] documents, and is suppressed while a text field has focus so the
 * letter `g` stays a letter. ESC and a click on the bare world remain the other two ways out.
 *
 * It never opens or closes a panel. With nothing open, [focusTarget] picks LEFT, which owns the
 * stripe's column: focus alone makes `DockState.anyActive()` true, the stripe appears, and the freed
 * cursor can click an icon to open whichever panel the player wants. That is why the focusing branch
 * passes `dropStaleFocus = false` — the region it just focused has no panel by design, and the
 * default guard would read that as "the focused region was closed underneath us" and undo the
 * keypress. Nothing about *which panels are open* changed either way, so neither branch persists.
 *
 * No-op while `mc.level == null`: the dock describes a world session that does not exist on the
 * title screen, and releasing the cursor there would fight vanilla's own screen handling. The click
 * is still consumed so presses cannot stack up and fire on the next world join — same contract as
 * [com.breadmoirai.garnet.dock.viewport.registerDockKeybinds].
 *
 * Registered from `GarnetClient.onInitializeClient()`.
 */
fun registerDockFocusKeybind() {
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        while (keyDockFocus.consumeClick()) {
            if (mc.level == null) continue
            if (DockInputRouter.captured) {
                DockInputRouter.clearFocus()
                commitDockVisibilityChange(persist = false)
            } else {
                DockInputRouter.focus(DockState.focusTarget())
                commitDockVisibilityChange(persist = false, dropStaleFocus = false)
            }
        }
    }
}
