package com.breadmoirai.redstonespecs.client.ui.compose.dock

/** Reserved edge strips (real framebuffer px) the shrunk world must avoid. */
data class DockInsets(val left: Int, val right: Int, val bottom: Int, val top: Int)

/**
 * Current reserved insets derived purely from [DockState]. A hidden region reserves nothing.
 * CENTER reserves nothing here (an occupying CENTER panel occludes the world at composite time,
 * it does not shrink it).
 */
fun DockState.insets(): DockInsets = DockInsets(
    left = if (isVisible(DockRegion.LEFT)) leftWidth else 0,
    right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0,
    bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0,
    top = 0,
)
