package com.breadmoirai.redstonespecs.client.viewport

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping

// GLFW_KEY_B. `[`/`]`/`\` (91/93/92) are used for spec-marker cycling, `V` (86) for the
// viewport-shrink toggle; `B` is otherwise unused by this mod and not a default vanilla binding.
private const val GLFW_KEY_B = 66

private val keyToggleCursorFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.cursor_focus_toggle", GLFW_KEY_B, KeyMapping.Category.MISC)
)

/**
 * Whether the cursor is currently released (free to move, e.g. over the reserved edges reserved
 * by [ViewportState.contentRect]) rather than grabbed (normal first-person mouse-look). Spike
 * scaffolding for future panel-focus support — nothing reads this yet besides the toggle itself.
 */
var cursorFocusActive: Boolean = false
    private set

/**
 * Registers a spike-level keybind (spike Task 4 Step 2 / exit criterion (d)) that releases or
 * re-grabs the mouse cursor, independent of [ViewportState.active] — this is a standalone input
 * toggle, not a render effect, so there is nothing to early-return on when the viewport shrink
 * itself is off.
 *
 * Only acts while no [net.minecraft.client.gui.screens.Screen] is open: vanilla already owns
 * grab/release around screen open/close (`Minecraft#setScreen` releases on open, re-grabs on
 * close), and fighting that here would be a real regression, not a spike concern.
 *
 * On release, [net.minecraft.client.MouseHandler.releaseMouse] frees the cursor. On re-grab,
 * [net.minecraft.client.MouseHandler.setIgnoreFirstMove] is called *before*
 * [net.minecraft.client.MouseHandler.grabMouse] so the single large raw-mouse delta accumulated
 * while the cursor was free (from wherever the OS cursor happened to be repositioned) is
 * discarded instead of being read as a camera turn — without it the camera visibly snaps on
 * re-grab. Confirmed via `javap` against the MC 26.2 client jar that both methods exist with
 * these exact names (see the Task 4 report).
 */
fun registerCursorFocusToggle() {
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        while (keyToggleCursorFocus.consumeClick()) {
            if (mc.gui.screen() != null) {
                // A real GUI screen is open; let vanilla's own grab/release handling own the
                // cursor state instead of fighting it.
                continue
            }
            cursorFocusActive = !cursorFocusActive
            if (cursorFocusActive) {
                mc.mouseHandler.releaseMouse()
            } else {
                mc.mouseHandler.setIgnoreFirstMove()
                mc.mouseHandler.grabMouse()
            }
        }
    }
}
