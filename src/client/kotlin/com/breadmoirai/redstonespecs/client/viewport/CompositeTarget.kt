package com.breadmoirai.redstonespecs.client.viewport

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Screenshot
import java.nio.file.Files
import java.nio.file.Path

/**
 * Off-screen composite target management for the viewport-shrink pipeline.
 *
 * Owns the lifecycle of a [TextureTarget] we composite the shrunk world into
 * before presenting it into a sub-rect of the real surface. Kept intentionally
 * small: creation/resize and a color clear, both built on the MC 26.2
 * GPU API ([RenderSystem.getDevice] + [com.mojang.blaze3d.systems.CommandEncoder]).
 */
object CompositeTarget {

    private const val LABEL = "RedstoneSpecs composite"

    /**
     * Return a [TextureTarget] sized `width` x `height`, reusing [existing] when it
     * already is a correctly sized [TextureTarget]. A wrongly sized [TextureTarget]
     * is resized in place (preserving its GPU handles); any other target is
     * released and replaced.
     */
    fun resizeOrCreate(existing: RenderTarget?, width: Int, height: Int): TextureTarget {
        if (existing is TextureTarget) {
            if (existing.width != width || existing.height != height) {
                existing.resize(width, height)
            }
            return existing
        }
        existing?.destroyBuffers()
        return TextureTarget(LABEL, width, height, /* useDepth = */ true)
    }

    /**
     * Clear [target]'s color to [argb] (and depth to far, if present). Used to prime
     * the composite with an opaque fill color before the game texture is blitted into
     * its sub-rect, so the reserved edge region ends up that solid color for free.
     */
    fun clearColor(target: RenderTarget, argb: Int) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val colorTexture = requireNotNull(target.colorTexture) { "Composite target has no color texture" }
        if (target.useDepth) {
            val depthTexture = requireNotNull(target.depthTexture) { "Composite target has no depth texture" }
            encoder.clearColorAndDepthTextures(colorTexture, argb, depthTexture, 1.0)
        } else {
            encoder.clearColorTexture(colorTexture, argb)
        }
    }

    /**
     * Read [target]'s color texture back to CPU and write it to [path] as a PNG.
     *
     * The normal in-game screenshot path (and Fabric's `takeScreenshot`) reads the *main* render
     * target, which is upstream of our present-time composite — so it can never show the composited
     * sub-rect. This reuses vanilla [Screenshot.takeScreenshot] (a render-target → `NativeImage`
     * download) pointed at our composite instead, giving a PNG of exactly what gets presented to the
     * window surface. Diagnostic only (spike visual proof); the readback callback is asynchronous, so
     * callers should poll for the file to appear.
     */
    fun captureToPng(target: RenderTarget, path: Path) {
        Screenshot.takeScreenshot(target) { image ->
            try {
                path.parent?.let(Files::createDirectories)
                image.writeToFile(path)
            } finally {
                image.close()
            }
        }
    }
}
