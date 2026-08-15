package com.breadmoirai.garnet.ui.dock

/**
 * Which dock region owns the window coordinate ([x], [y]), or `null` when the point is over the
 * **bare world viewport** — the transparent area the composited game shows through.
 *
 * This is the pointer-side mirror of [insets]: `insets()` says how much space the dock reserves,
 * this says who owns a given pixel. `DockInputRouter` keys both click-to-focus gestures off it — a
 * click on `null` returns the user to the game, a click on a region focuses that region.
 *
 * Coordinates are **raw GLFW window coordinates**, matching the rest of the dock's input path
 * (`ComposeSceneHost` runs full-window at `Density(1f)`, so window coords are scene coords).
 * [realW]/[realH] are the real, un-shrunk framebuffer size cached on
 * [com.breadmoirai.garnet.ui.viewport.ViewportState].
 *
 * The z-order below is not arbitrary — it reproduces the order [GarnetDock] draws in, and must stay
 * in lockstep with it:
 *
 * 0. The stripe owns its full-height column, drawn last and therefore tested first.
 * 1. BOTTOM is a full-width band, drawn over where the LEFT/RIGHT columns would reach (those stop
 *    at `realH - bottom`), so it wins both bottom corners.
 * 2. LEFT, then RIGHT, own their edge strips above that band.
 * 3. CENTER owns whatever is left — but **only when it actually has an open panel**. A closed CENTER
 *    is transparent by omission and *is* the world, which is the whole point of the `null` case.
 *
 * A hidden region reserves nothing even though its size is remembered, and splitters are drawn
 * inside the reserved strips, so neither needs a special case. Points outside the window belong to
 * no region.
 */
fun DockState.regionAt(x: Int, y: Int, realW: Int, realH: Int): DockRegion? {
    if (x < 0 || y < 0 || x >= realW || y >= realH) return null

    val stripe = if (anyActive()) STRIPE_WIDTH else 0
    val left = if (isVisible(DockRegion.LEFT)) leftWidth else 0
    val right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0
    val bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0

    // The stripe is drawn LAST in GarnetDock — full height, over everything — so it is tested FIRST
    // here, before BOTTOM's full-width band. Attributed to LEFT so clicking an icon also focuses the
    // panel it opens.
    if (stripe > 0 && x < stripe) return DockRegion.LEFT
    if (bottom > 0 && y >= realH - bottom) return DockRegion.BOTTOM
    if (left > 0 && x < stripe + left) return DockRegion.LEFT
    if (right > 0 && x >= realW - right) return DockRegion.RIGHT
    if (isVisible(DockRegion.CENTER)) return DockRegion.CENTER
    return null
}
