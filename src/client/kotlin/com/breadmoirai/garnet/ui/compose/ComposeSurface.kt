package com.breadmoirai.garnet.ui.compose

import com.mojang.blaze3d.GpuFormat
import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.slf4j.LoggerFactory

/**
 * Skia-over-Minecraft-GL surface for the Compose-in-MC feasibility spike.
 *
 * This owns the risky part of the spike: standing up a Skia [DirectContext] on Minecraft's *own*
 * live OpenGL context (via Skiko's desktop-GL native), rendering into a GL framebuffer, and doing
 * so **without corrupting Blaze3D's rendering on the next frame**. See
 * `docs/ui/compose-in-mc-feasibility.md` for the full rationale and verdict.
 *
 * ## How it avoids reinventing the blit
 * Rather than manage a raw GL texture that [com.breadmoirai.garnet.ui.viewport.BlitUvPipeline]
 * (which speaks the Blaze3D GPU abstraction, not raw GL ids) could not consume, we render Skia into a
 * Blaze3D [TextureTarget]: its color [GlTexture] hands us both a raw GL FBO id (for Skia's
 * [BackendRenderTarget]) *and* a [GpuTextureView] (for `BlitUvPipeline`). One texture, two views of it.
 *
 * ## GL-state coexistence (the crux)
 * Skia issues raw GL calls that bypass Blaze3D's `GlStateManager` cache. If we let Skia leave the GL
 * context in a different state than `GlStateManager` *believes*, Blaze3D's cache-skipping rebinds will
 * silently no-op and corrupt subsequent frames. So around every Skia draw we:
 *  1. snapshot the real GL state (which currently matches Blaze3D's belief, since Blaze3D just ran),
 *  2. let Skia draw + [DirectContext.flush],
 *  3. call [DirectContext.resetAll] so Skia re-reads GL state next frame (Blaze3D will have changed it),
 *  4. restore the exact snapshotted GL state so `GlStateManager`'s cache is consistent with reality.
 *
 * ## Guarding
 * Every entry point is guarded. Any [Throwable] — native load failure under Fabric's Knot classloader,
 * a missing GL entry point, a Skia error — flips [disabled] and logs once, so the client falls back to
 * the plain viewport composite and **never crashes startup or normal play**.
 */
object ComposeSurface {

    private val logger = LoggerFactory.getLogger("Garnet")

    private const val GR_GL_RGBA8 = 0x8058

    /** Set once any Skia/Skiko entry point throws; from then on [renderFrame] is a no-op. */
    @Volatile
    var disabled: Boolean = false
        private set

    /** Human-readable reason [disabled] was set (for the spike report / logs). */
    @Volatile
    var disabledReason: String? = null
        private set

    private var nativeLoaded = false
    private var loggedUpload = false
    private var directContext: DirectContext? = null

    private var target: TextureTarget? = null
    private var backendRt: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** The full-window dock scene, recreated when the window size changes. */
    private var host: ComposeSceneHost? = null

    /**
     * Set when the dock stops rendering, meaning the live scene must be torn down and rebuilt before
     * it is used again.
     *
     * ## Why (do not remove)
     * The scene is a long-lived singleton but the overlay only renders while the dock is active, and
     * **composition only advances during a render**. So when the dock is hidden, whatever was on
     * screen at that instant is frozen *inside* the scene: removed panels are never disposed,
     * focus stays where it was, and any `Popup`/`ComposeSceneLayer` a Jewel `Dropdown` added is still
     * attached. Showing the dock again then repaints that stale content over the fresh panel (the
     * "ghost menu" defect), and — more subtly — a stale focused widget keeps consuming forwarded key
     * events, which is enough to swallow the ESC that is supposed to drop dock focus.
     *
     * Marking instead of closing here is deliberate: [markSceneStale] is called from wherever the
     * overlay is switched off (a client tick, a test worker), while `ImageComposeScene.close()` must
     * happen on the render thread. The flag is honored at the top of [renderFrame] — which is on the
     * render thread by construction — and by [ComposeInput]'s guard, so a stale scene accepts no input
     * in the window between the two.
     */
    @Volatile
    private var sceneStale = false

    /**
     * Discard the live Compose scene: no state composed into it may outlive the dock being hidden.
     * Cheap and non-blocking (see [sceneStale]); the actual teardown/rebuild happens on the next
     * rendered frame. Safe to call from any thread and any number of times.
     */
    fun markSceneStale() {
        sceneStale = true
    }

    /** Width (px) of the last surface we rendered — the full window width. Read by the overlay. */
    @Volatile
    var lastWidth: Int = 0
        private set

    /** Height (px) of the last surface we rendered — the real window height. Read by the overlay. */
    @Volatile
    var lastHeight: Int = 0
        private set

    internal fun kill(reason: String, t: Throwable?) {
        disabled = true
        disabledReason = reason
        if (t != null) logger.error("[compose-spike] disabling Compose surface: {}", reason, t)
        else logger.error("[compose-spike] disabling Compose surface: {}", reason)
        releaseGpu()
    }

    /**
     * Force Skiko's native library to extract + load under the current (Knot) classloader. This is
     * the biggest single risk (Step 1); we do it eagerly and loudly so a failure is a clean logged
     * disable rather than a mid-render surprise.
     */
    fun ensureNativeLoaded(): Boolean {
        if (disabled) return false
        if (nativeLoaded) return true
        return try {
            org.jetbrains.skiko.Library.load()
            nativeLoaded = true
            logger.info("[compose-spike] Skiko native loaded (skiko 0.144.6, desktop-GL)")
            true
        } catch (t: Throwable) {
            kill("Skiko native load failed under Knot classloader", t)
            false
        }
    }

    private fun ensureDirectContext(): DirectContext? {
        directContext?.let { return it }
        val ctx = DirectContext.makeGL()
        directContext = ctx
        logger.info("[compose-spike] Skia DirectContext.makeGL() over MC's live GL context OK")
        return ctx
    }

    private fun ensureSurface(ctx: DirectContext, width: Int, height: Int): Surface? {
        if (surface != null && surfaceWidth == width && surfaceHeight == height) return surface
        releaseSurfaceOnly()

        // Skia renders into a Blaze3D-owned TextureTarget: gives us a raw GL FBO (for Skia) and a
        // GpuTextureView (for BlitUvPipeline) backed by the same GL texture. No depth: Skia here needs
        // no depth/stencil, and a bare color attachment keeps the FBO valid for Skia (stencilBits=0).
        val tt = TextureTarget("garnet compose-skia", width, height, /* useDepth = */ false, GpuFormat.RGBA8_UNORM)
        target = tt

        // GlDevice/GlTexture/DirectStateAccess are package-private; the class-tweaker opens them
        // (garnet.classtweaker). MC 26.2 removed GlTexture.getFbo — FBO acquisition now lives
        // on GlDevice.frameBufferCache(): getFbo(dsa, colorAttachments, depthAttachment) lazily creates
        // + caches a GL FBO bound to those attachments. GlTexture implements FrameBufferAttachment, so
        // the color texture is itself the sole (color) attachment here — exactly the framebuffer id
        // Skia's BackendRenderTarget wraps. No depth attachment (Skia needs none).
        val backend = RenderSystem.getDevice().backend as GlDevice
        val dsa = backend.directStateAccess()
        val glTex = tt.colorTexture as GlTexture
        val fbo = backend.frameBufferCache().getFbo(dsa, listOf(glTex), null)

        val brt = BackendRenderTarget.makeGL(width, height, 0, 0, fbo, GR_GL_RGBA8)
        backendRt = brt
        // BOTTOM_LEFT origin: Skia stores the result bottom-up, exactly like a Blaze3D render-target
        // color texture, so BlitUvPipeline's flipV=true path presents it upright with no extra math.
        val s = Surface.makeFromBackendRenderTarget(
            ctx, brt, SurfaceOrigin.BOTTOM_LEFT, SurfaceColorFormat.RGBA_8888, ColorSpace.sRGB,
        ) ?: return null
        surface = s
        surfaceWidth = width
        surfaceHeight = height
        logger.info("[compose-spike] Skia Surface over Blaze3D FBO {} ({}x{}) OK", fbo, width, height)
        return s
    }

    /**
     * Render one Skia frame into a [width] x [height] surface (the full window) and return the
     * [GpuTextureView] the caller should blit into the composite, or `null` if Compose is disabled or
     * anything failed this frame. Must be called on the render thread with MC's GL context current.
     *
     * Composes the full-window [ComposeSceneHost] (hosting `GarnetDock`) into an off-screen raster
     * [org.jetbrains.skia.Image] (pure CPU, no GL), then draws that image onto the GL FBO — so the
     * pixels reaching the screen are produced by actual Compose, not hand-rolled Skia geometry.
     */
    fun renderFrame(width: Int, height: Int): GpuTextureView? {
        if (disabled) return null
        if (width <= 0 || height <= 0) return null
        if (!ensureNativeLoaded()) return null

        val saved = IntArray(GlStateStash.SAVE_SLOTS)
        return try {
            val ctx = ensureDirectContext() ?: return null
            val s = ensureSurface(ctx, width, height) ?: return null
            val h = ensureHost(width, height)

            // Compose the frame on Compose's own raster surface (no GL), then upload the one image.
            val image = h.render(System.nanoTime())
            GlStateStash.saveGlState(saved)
            val unpack = GlStateStash.saveAndResetUnpack()
            try {
                if (!loggedUpload) {
                    loggedUpload = true
                    logger.info(
                        "[compose-spike] Compose image {}x{} -> FBO {}x{}; MC unpack row_len={} skip_px={}",
                        image.width, image.height, width, height, unpack[1], unpack[2],
                    )
                }
                s.canvas.clear(0x00000000)   // fully transparent; Compose paints its own opaque regions
                s.canvas.drawImage(image, 0f, 0f)
                s.flush()
                ctx.flush()
            } finally {
                image.close()
            }
            ctx.resetAll()
            GlStateStash.restoreUnpack(unpack)
            GlStateStash.restoreGlState(saved)

            lastWidth = width
            lastHeight = height
            target?.colorTextureView
        } catch (t: Throwable) {
            // Best-effort restore even on failure so we don't leave Blaze3D wedged, then disable.
            try {
                GlStateStash.restoreGlState(saved)
            } catch (_: Throwable) {
            }
            kill("Compose/Skia render/coexistence failed", t)
            null
        }
    }

    private fun ensureHost(width: Int, height: Int): ComposeSceneHost {
        // On the render thread: honor a pending staleness mark before anything can compose again.
        if (sceneStale) {
            sceneStale = false
            host?.close()
            host = null
        }
        host?.let { if (it.width == width && it.height == height) return it }
        host?.close()
        val h = ComposeSceneHost(width, height) {
            com.breadmoirai.garnet.ui.dock.GarnetDock(
                width,
                height,
                { com.breadmoirai.garnet.ui.viewport.commitDockVisibilityChange() },
            )
        }
        host = h
        logger.info("[compose] GarnetDock scene ({}x{}) created", width, height)
        return h
    }

    /** Internal accessor so [ComposeInput] can reach the live scene without [host] itself going public. */
    internal fun currentHost(): ComposeSceneHost? = host

    /** Internal accessor so [ComposeInput] can honor [sceneStale] without that field itself going public. */
    internal fun isSceneStale(): Boolean = sceneStale

    private fun releaseSurfaceOnly() {
        surface?.close(); surface = null
        backendRt?.close(); backendRt = null
        target?.destroyBuffers(); target = null
        host?.close(); host = null
        surfaceWidth = 0; surfaceHeight = 0
    }

    private fun releaseGpu() {
        try {
            releaseSurfaceOnly()
            directContext?.close(); directContext = null
        } catch (_: Throwable) {
        }
    }
}
