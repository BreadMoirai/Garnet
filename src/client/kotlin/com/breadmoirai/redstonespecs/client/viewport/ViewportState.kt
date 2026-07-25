package com.breadmoirai.redstonespecs.client.viewport

import com.breadmoirai.redstonespecs.client.ui.compose.dock.insets

/**
 * Client-side state for the viewport-shrink spike.
 *
 * When [active], [WindowMixin][com.breadmoirai.redstonespecs.mixin.client.WindowMixin] overrides
 * the game window's reported framebuffer size so vanilla renders/GUI-scales into a smaller
 * region than the real window. [contentRect] decides how big that region is, reserving strips
 * reserved by the dock (`DockState`) around it.
 *
 * This is a simple mutable `object`: there is exactly one window per client process, and the
 * mixin needs to read/write this state from a single well-known place.
 */
object ViewportState {

    /** Whether the shrink override is currently toggled on. */
    var active: Boolean = false

    /** Content rect dimensions never shrink below this, even if the real window is tiny. */
    private const val MIN_CONTENT_SIZE = 64

    /**
     * Real (un-overridden) framebuffer width, cached by [WindowMixin][com.breadmoirai.redstonespecs.mixin.client.WindowMixin]
     * before it overrides. Public var (rather than a Kotlin `internal` setter) so the Java mixin
     * can write it directly without name-mangling friction across the java/kotlin split of the
     * `client` source set.
     */
    var realWidth: Int = 0

    /** Real (un-overridden) framebuffer height, cached the same way as [realWidth]. */
    var realHeight: Int = 0

    /**
     * One-shot request to dump the next composited frame to this path (diagnostic / spike visual
     * proof). The present mixin consumes it: when non-null after compositing a frame, it reads the
     * composite target back to a PNG at this path and clears the field. `@Volatile` for cross-thread
     * visibility between a test worker that sets it and the render thread that reads it.
     */
    @Volatile
    var compositeCaptureRequest: java.nio.file.Path? = null

    /** Whether [WindowMixin][com.breadmoirai.redstonespecs.mixin.client.WindowMixin] should apply its override. */
    fun shouldModify(): Boolean = active

    /**
     * The content sub-rect of the real window that the shrunk game should occupy, given the
     * real framebuffer size [realW] x [realH]. Reserves the insets derived from `DockState` for
     * currently-visible dock regions, clamping the remaining area to a sane non-zero minimum.
     */
    fun contentRect(realW: Int, realH: Int): ContentRect {
        val insets = com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState.insets()
        val frameX = insets.left
        val frameY = insets.top
        val frameWidth = (realW - insets.left - insets.right).coerceAtLeast(MIN_CONTENT_SIZE)
        val frameHeight = (realH - insets.top - insets.bottom).coerceAtLeast(MIN_CONTENT_SIZE)
        return ContentRect(frameX, frameY, frameWidth, frameHeight)
    }

    data class ContentRect(val frameX: Int, val frameY: Int, val frameWidth: Int, val frameHeight: Int)
}
