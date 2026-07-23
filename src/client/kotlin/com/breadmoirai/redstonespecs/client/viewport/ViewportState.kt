package com.breadmoirai.redstonespecs.client.viewport

/**
 * Client-side state for the viewport-shrink spike.
 *
 * When [active], [WindowMixin][com.breadmoirai.redstonespecs.mixin.client.WindowMixin] overrides
 * the game window's reported framebuffer size so vanilla renders/GUI-scales into a smaller
 * region than the real window. [contentRect] decides how big that region is, reserving fixed
 * strips of screen space (e.g. for future editor panels) around it.
 *
 * This is a simple mutable `object`: there is exactly one window per client process, and the
 * mixin needs to read/write this state from a single well-known place.
 */
object ViewportState {

    /** Whether the shrink override is currently toggled on. */
    var active: Boolean = false

    /** Reserved strip on the left edge of the real window, in real framebuffer pixels. */
    private const val RESERVED_LEFT = 260

    /** Reserved strip on the bottom edge of the real window, in real framebuffer pixels. */
    private const val RESERVED_BOTTOM = 160

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

    /** Whether [WindowMixin][com.breadmoirai.redstonespecs.mixin.client.WindowMixin] should apply its override. */
    fun shouldModify(): Boolean = active

    /**
     * The content sub-rect of the real window that the shrunk game should occupy, given the
     * real framebuffer size [realW] x [realH]. Reserves [RESERVED_LEFT]/[RESERVED_BOTTOM] for
     * future editor UI, clamping the remaining area to a sane non-zero minimum.
     */
    fun contentRect(realW: Int, realH: Int): ContentRect {
        val frameX = RESERVED_LEFT
        val frameY = 0
        val frameWidth = (realW - RESERVED_LEFT).coerceAtLeast(MIN_CONTENT_SIZE)
        val frameHeight = (realH - RESERVED_BOTTOM).coerceAtLeast(MIN_CONTENT_SIZE)
        return ContentRect(frameX, frameY, frameWidth, frameHeight)
    }

    data class ContentRect(val frameX: Int, val frameY: Int, val frameWidth: Int, val frameHeight: Int)
}
