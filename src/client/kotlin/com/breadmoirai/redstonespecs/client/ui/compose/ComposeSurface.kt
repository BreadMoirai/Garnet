package com.breadmoirai.redstonespecs.client.ui.compose

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import org.jetbrains.skia.SurfaceColorFormat
import org.jetbrains.skia.SurfaceOrigin
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
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
 * Rather than manage a raw GL texture that [com.breadmoirai.redstonespecs.client.viewport.BlitUvPipeline]
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

    private val logger = LoggerFactory.getLogger("Redstone Specs")

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
    private var directContext: DirectContext? = null

    private var target: TextureTarget? = null
    private var backendRt: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** Width (px) of the last surface we rendered — the reserved-left strip. Read by the overlay. */
    @Volatile
    var lastWidth: Int = 0
        private set

    /** Height (px) of the last surface we rendered — the real window height. Read by the overlay. */
    @Volatile
    var lastHeight: Int = 0
        private set

    private val fill = Paint().apply { color = 0xFF1B2433.toInt() }
    private val accent = Paint().apply { color = 0xFF4CC2FF.toInt() }
    private val stripe = Paint().apply { color = 0xFF243044.toInt() }

    private fun kill(reason: String, t: Throwable?) {
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
            logger.info("[compose-spike] Skiko native loaded (skiko 0.150.1, desktop-GL)")
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
        val tt = TextureTarget("RedstoneSpecs compose-skia", width, height, /* useDepth = */ false)
        target = tt

        // GlDevice/GlTexture/DirectStateAccess are package-private; an access-widener opens them
        // (redstonespecs.accesswidener). getFbo lazily creates + caches a GL FBO bound to this
        // texture's color attachment — exactly the framebuffer id Skia's BackendRenderTarget wraps.
        val backend = RenderSystem.getDevice().backend as GlDevice
        val dsa = backend.directStateAccess()
        val glTex = tt.colorTexture as GlTexture
        val fbo = glTex.getFbo(dsa, null)

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
     * Render one Skia frame into a [width] x [height] surface (the reserved-left panel) and return the
     * [GpuTextureView] the caller should blit into the composite, or `null` if Compose is disabled or
     * anything failed this frame. Must be called on the render thread with MC's GL context current.
     *
     * Step 2 of the spike draws a plain Skia panel (no Compose yet): a filled background plus accent
     * bars, enough to prove Skia pixels reach the screen and MC survives the next frame.
     */
    fun renderFrame(width: Int, height: Int): GpuTextureView? {
        if (disabled) return null
        if (width <= 0 || height <= 0) return null
        if (!ensureNativeLoaded()) return null

        val saved = IntArray(SAVE_SLOTS)
        return try {
            val ctx = ensureDirectContext() ?: return null
            val s = ensureSurface(ctx, width, height) ?: return null

            saveGlState(saved)
            drawPanel(s.canvas, width, height)
            s.flush()
            ctx.flush()
            ctx.resetAll()
            restoreGlState(saved)

            lastWidth = width
            lastHeight = height
            target?.colorTextureView
        } catch (t: Throwable) {
            // Best-effort restore even on failure so we don't leave Blaze3D wedged, then disable.
            try {
                restoreGlState(saved)
            } catch (_: Throwable) {
            }
            kill("Skia render/coexistence failed", t)
            null
        }
    }

    /** Plain-Skia proof content (Step 2): a solid panel with a couple of accent bars. */
    private fun drawPanel(canvas: Canvas, width: Int, height: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.clear(0xFF1B2433.toInt())
        canvas.drawRect(0f, 0f, w, h, fill)
        // A vertical stripe on the right edge of the panel, and a horizontal accent bar near the top,
        // so the image unmistakably shows Skia geometry rather than a flat clear.
        canvas.drawRect(w - 6f, 0f, w, h, stripe)
        canvas.drawRect(16f, 24f, w - 16f, 44f, accent)
        canvas.drawRect(16f, 60f, w - 16f, 68f, stripe)
    }

    // --- GL-state snapshot/restore ---------------------------------------------------------------
    // Slots: 0 program, 1 VAO, 2 active-texture, 3 tex-binding-2D(unit0), 4 array-buffer,
    //        5 draw-fbo, 6 read-fbo, 7 blend, 8 depth-test, 9 scissor, 10 cull.
    private const val SAVE_SLOTS = 11

    private fun saveGlState(o: IntArray) {
        o[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        o[1] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        o[2] = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        o[3] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        o[4] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        o[5] = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING)
        o[6] = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING)
        o[7] = if (GL11.glIsEnabled(GL11.GL_BLEND)) 1 else 0
        o[8] = if (GL11.glIsEnabled(GL11.GL_DEPTH_TEST)) 1 else 0
        o[9] = if (GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)) 1 else 0
        o[10] = if (GL11.glIsEnabled(GL11.GL_CULL_FACE)) 1 else 0
    }

    private fun restoreGlState(o: IntArray) {
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, o[5])
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, o[6])
        GL30.glBindVertexArray(o[1])
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, o[4])
        GL13.glActiveTexture(o[2])
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, o[3])
        GL20.glUseProgram(o[0])
        setEnabled(GL11.GL_BLEND, o[7])
        setEnabled(GL11.GL_DEPTH_TEST, o[8])
        setEnabled(GL11.GL_SCISSOR_TEST, o[9])
        setEnabled(GL11.GL_CULL_FACE, o[10])
    }

    private fun setEnabled(cap: Int, on: Int) {
        if (on == 1) GL11.glEnable(cap) else GL11.glDisable(cap)
    }

    private fun releaseSurfaceOnly() {
        surface?.close(); surface = null
        backendRt?.close(); backendRt = null
        target?.destroyBuffers(); target = null
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
