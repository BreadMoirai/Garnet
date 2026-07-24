package com.breadmoirai.redstonespecs.client.ui.compose

import com.breadmoirai.redstonespecs.client.viewport.BlitUvPipeline
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.mojang.blaze3d.pipeline.RenderTarget
import org.slf4j.LoggerFactory

/**
 * Thin integration seam between the viewport present path and [ComposeSurface].
 *
 * Called from `MinecraftPresentMixin` after the world has been blitted into the composite. When
 * [enabled] (and the viewport is active), it asks [ComposeSurface] for a freshly-rendered Skia panel
 * and blits it into the reserved-left strip of the composite via the existing [BlitUvPipeline] — no
 * new blit machinery. Off by default; a single toggle (test/keybind) turns it on so nothing renders
 * during normal play. Fully guarded: any failure logs once and disables, leaving the plain composite.
 */
object ComposeOverlay {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    /** Master switch for the spike overlay. OFF unless a test or the debug keybind turns it on. */
    @Volatile
    var enabled: Boolean = false

    private var loggedDisabled = false

    /**
     * Blit the Compose/Skia panel into [composite]'s reserved-left strip. [realW]/[realH] are the real
     * (un-shrunk) framebuffer size. No-op unless [enabled], the viewport is active, and the surface
     * produced a texture this frame.
     */
    fun renderInto(composite: RenderTarget, realW: Int, realH: Int) {
        if (!enabled || !ViewportState.active) return
        if (realW <= 0 || realH <= 0) return
        try {
            val stripWidth = ViewportState.contentRect(realW, realH).frameX
            if (stripWidth <= 0) return

            val texture = ComposeSurface.renderFrame(stripWidth, realH) ?: run {
                if (ComposeSurface.disabled && !loggedDisabled) {
                    loggedDisabled = true
                    logger.warn(
                        "[compose-spike] overlay inert: ComposeSurface disabled ({})",
                        ComposeSurface.disabledReason,
                    )
                }
                return
            }

            // Reserved-left strip: x in [0, stripWidth], full height. flipV=true because the Skia
            // surface (BOTTOM_LEFT origin) is stored bottom-up, like MC render-target textures.
            val x2 = stripWidth.toFloat() / realW
            BlitUvPipeline.blit(texture, composite, 0f, 0f, x2, 1f, /* flipV = */ true)
        } catch (t: Throwable) {
            if (!loggedDisabled) {
                loggedDisabled = true
                logger.error("[compose-spike] overlay blit failed; leaving plain composite", t)
            }
        }
    }
}
