package com.breadmoirai.garnet.dock.compose

import com.breadmoirai.garnet.dock.viewport.BlitUvPipeline
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.mojang.blaze3d.pipeline.RenderTarget
import org.slf4j.LoggerFactory

/**
 * Thin integration seam between the viewport present path and [ComposeSurface].
 *
 * Called from `MinecraftPresentMixin` after the world has been blitted into the composite. When
 * [enabled] (and the viewport is active), it asks [ComposeSurface] for a freshly-rendered, full-window
 * Skia panel and alpha-blends it over the entire composite via [BlitUvPipeline]'s `blend=true` path,
 * so the live world composited underneath shows through everywhere Compose left transparent. Off by
 * default; a single toggle (test/keybind) turns it on so nothing renders during normal play. Fully
 * guarded: any failure logs once and disables, leaving the plain composite.
 */
object ComposeOverlay {

    private val logger = LoggerFactory.getLogger("Garnet")

    /**
     * Master switch for the spike overlay. OFF unless a test or the debug keybind turns it on.
     *
     * Switching it **off** marks the Compose scene stale ([ComposeSurface.markSceneStale]), because
     * composition only advances while frames are being rendered: the moment this goes false the
     * scene freezes with whatever was mounted — panels that were then removed are never disposed,
     * focus is never released, and an open `Dropdown`'s popup layer stays attached, to be repainted
     * over the next mount. Doing it in the setter rather than at each call site is deliberate: this
     * flag is the single choke point every hide path (keybind, `syncDockViewport`, tests) already
     * goes through, so the invariant "nothing composed survives the dock being hidden" cannot be
     * bypassed by forgetting a cleanup call.
     */
    @Volatile
    var enabled: Boolean = false
        set(value) {
            if (field && !value) ComposeSurface.markSceneStale()
            field = value
        }

    private var loggedDisabled = false

    /**
     * Render and blit the Compose/Skia panel over the **whole** window with premultiplied-alpha
     * blending. [realW]/[realH] are the real (un-shrunk) framebuffer size. No-op unless [enabled],
     * the viewport is active, and the surface produced a texture this frame.
     */
    fun renderInto(composite: RenderTarget, realW: Int, realH: Int) {
        if (!enabled || !ViewportState.active) return
        if (realW <= 0 || realH <= 0) return
        try {
            val texture = ComposeSurface.renderFrame(realW, realH) ?: run {
                if (ComposeSurface.disabled && !loggedDisabled) {
                    loggedDisabled = true
                    logger.warn("[compose] overlay inert: ComposeSurface disabled ({})", ComposeSurface.disabledReason)
                }
                return
            }
            // Full-window, alpha-blended over the composited world. flipV=true: the Skia surface
            // (BOTTOM_LEFT origin) is stored bottom-up like MC render-target textures.
            BlitUvPipeline.blit(texture, composite, 0f, 0f, 1f, 1f, /* flipV = */ true, /* blend = */ true)
        } catch (t: Throwable) {
            if (!loggedDisabled) {
                loggedDisabled = true
                logger.error("[compose] overlay blit failed; leaving plain composite", t)
            }
        }
    }
}
