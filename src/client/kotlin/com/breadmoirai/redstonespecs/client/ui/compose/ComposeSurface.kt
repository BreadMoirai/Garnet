package com.breadmoirai.redstonespecs.client.ui.compose

import com.mojang.blaze3d.opengl.GlDevice
import com.mojang.blaze3d.opengl.GlTexture
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.GpuTextureView
import androidx.compose.ui.geometry.Offset
import org.jetbrains.skia.BackendRenderTarget
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.DirectContext
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
    private var loggedUpload = false
    private var directContext: DirectContext? = null

    private var target: TextureTarget? = null
    private var backendRt: BackendRenderTarget? = null
    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    /** The full-window dock scene, recreated when the window size changes. */
    private var host: ComposeSceneHost? = null

    /** Width (px) of the last surface we rendered — the full window width. Read by the overlay. */
    @Volatile
    var lastWidth: Int = 0
        private set

    /** Height (px) of the last surface we rendered — the real window height. Read by the overlay. */
    @Volatile
    var lastHeight: Int = 0
        private set

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
     * Render one Skia frame into a [width] x [height] surface (the full window) and return the
     * [GpuTextureView] the caller should blit into the composite, or `null` if Compose is disabled or
     * anything failed this frame. Must be called on the render thread with MC's GL context current.
     *
     * Composes the full-window [ComposeSceneHost] (hosting `RedstoneDock`) into an off-screen raster
     * [org.jetbrains.skia.Image] (pure CPU, no GL), then draws that image onto the GL FBO — so the
     * pixels reaching the screen are produced by actual Compose, not hand-rolled Skia geometry.
     */
    fun renderFrame(width: Int, height: Int): GpuTextureView? {
        if (disabled) return null
        if (width <= 0 || height <= 0) return null
        if (!ensureNativeLoaded()) return null

        val saved = IntArray(SAVE_SLOTS)
        return try {
            val ctx = ensureDirectContext() ?: return null
            val s = ensureSurface(ctx, width, height) ?: return null
            val h = ensureHost(width, height)

            // Compose the frame on Compose's own raster surface (no GL), then upload the one image.
            val image = h.render(System.nanoTime())
            saveGlState(saved)
            val unpack = saveAndResetUnpack()
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
            restoreUnpack(unpack)
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
            kill("Compose/Skia render/coexistence failed", t)
            null
        }
    }

    private fun ensureHost(width: Int, height: Int): ComposeSceneHost {
        host?.let { if (it.width == width && it.height == height) return it }
        host?.close()
        val h = ComposeSceneHost(width, height) {
            com.breadmoirai.redstonespecs.client.ui.compose.dock.RedstoneDock(width, height)
        }
        host = h
        logger.info("[compose] RedstoneDock scene ({}x{}) created", width, height)
        return h
    }

    // --- Input (Task 4): forward GLFW-derived pointer/scroll/key events into the live dock scene ----
    // Scene-local coords == window-local screen coords (Compose draws top-down; the BOTTOM_LEFT surface
    // + flipV blit presents it upright, so no Y flip is needed for hit-testing).

    fun sendPointerMove(pos: Offset) = guardedInput { host?.pointerMove(pos) }
    fun sendPointerPress(pos: Offset) = guardedInput { host?.pointerPress(pos) }
    fun sendPointerRelease(pos: Offset) = guardedInput { host?.pointerRelease(pos) }
    fun sendScroll(pos: Offset, delta: Offset) = guardedInput { host?.scroll(pos, delta) }
    fun sendKey(event: androidx.compose.ui.input.key.KeyEvent) = guardedInput { host?.sendKey(event) }

    private inline fun guardedInput(block: () -> Unit) {
        if (disabled) return
        try {
            block()
        } catch (t: Throwable) {
            kill("ComposeScene input dispatch failed", t)
        }
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

    // --- GL pixel-store (unpack) snapshot/reset --------------------------------------------------
    // Skia's drawImage uploads a CPU raster via glTexSubImage2D, which reads the pixel buffer using
    // the current GL_UNPACK_* state. Blaze3D leaves GL_UNPACK_ROW_LENGTH / SKIP_PIXELS set from its
    // own texture writes; inherited, they roll/shear Skia's upload (the horizontal wraparound the
    // asymmetric Compose panel exposed — the old symmetric plain-Skia panel didn't upload anything,
    // so never hit it). We reset these to their GL defaults around the draw and restore MC's values
    // after, keeping GlStateManager's belief intact. Slots: 0 align,1 row_len,2 skip_px,3 skip_rows.

    private fun saveAndResetUnpack(): IntArray {
        val o = IntArray(4)
        o[0] = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
        o[1] = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)
        o[2] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS)
        o[3] = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS)
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        return o
    }

    private fun restoreUnpack(o: IntArray) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, o[0])
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, o[1])
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, o[2])
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, o[3])
    }

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
