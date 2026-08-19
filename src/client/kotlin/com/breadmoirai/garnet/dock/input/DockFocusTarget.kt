package com.breadmoirai.garnet.dock.input

import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState

/**
 * Which region the dock-focus keybind should hand the keyboard and cursor to.
 *
 * In order: the region focus was last in (when it still has a panel open), else the first visible
 * region in [DockRegion] order, else LEFT with nothing open at all.
 *
 * That last case is not a degenerate fallback, it is the "I want the UI" state: focusing a region
 * makes [DockState.anyActive] true, which is what draws the stripe, and `regionAt` attributes the
 * stripe's column to LEFT — so the cursor is freed over a stripe whose icons are clickable and open
 * the panel the player is after. Nothing is opened on their behalf.
 *
 * Pure — no `Minecraft`, no GLFW — so it is unit-tested in `DockFocusTargetTest` (`src/test`), the
 * same split [com.breadmoirai.garnet.dock.shell.regionAt] uses.
 */
fun DockState.focusTarget(): DockRegion =
    lastFocusedRegion?.takeIf { isVisible(it) }
        ?: DockRegion.entries.firstOrNull { isVisible(it) }
        ?: DockRegion.LEFT
