package com.breadmoirai.garnet.dock.shell

/** Reserved edge strips (real framebuffer px) the shrunk world must avoid. */
data class DockInsets(val left: Int, val right: Int, val bottom: Int, val top: Int)

/**
 * Current reserved insets derived purely from [DockState]. A closed region reserves nothing.
 * CENTER reserves nothing here (an occupying CENTER panel occludes the world at composite time,
 * it does not shrink it).
 *
 * The stripe's width is gated on [DockState.anyActive] rather than on LEFT being open: the stripe
 * is visible whenever *any* region is open, which is what lets a user with only BOTTOM open still
 * reach the LEFT icons.
 */
fun DockState.insets(): DockInsets = DockInsets(
    left = (if (anyActive()) STRIPE_WIDTH else 0) + (if (isVisible(DockRegion.LEFT)) leftWidth else 0),
    right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0,
    bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0,
    top = 0,
)
