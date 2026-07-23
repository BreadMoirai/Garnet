package com.breadmoirai.redstonespecs.client.viewport

import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem

/**
 * Off-screen composite target management for the viewport-shrink pipeline.
 *
 * Owns the lifecycle of a [TextureTarget] we composite the shrunk world into
 * before presenting it into a sub-rect of the real surface. Kept intentionally
 * small: creation/resize and a transparent clear, both built on the MC 26.2
 * GPU API ([RenderSystem.getDevice] + [com.mojang.blaze3d.systems.CommandEncoder]).
 */
object CompositeTarget {

    private const val LABEL = "RedstoneSpecs composite"

    /** Fully transparent black; sub-rect blits then draw over the cleared area. */
    private const val TRANSPARENT_ARGB = 0x00000000

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
     * Clear [target]'s color to transparent (and depth to far, if present) so the
     * next composite starts from a clean, edge-transparent slate.
     */
    fun clearTransparent(target: RenderTarget) {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val colorTexture = requireNotNull(target.colorTexture) { "Composite target has no color texture" }
        if (target.useDepth) {
            val depthTexture = requireNotNull(target.depthTexture) { "Composite target has no depth texture" }
            encoder.clearColorAndDepthTextures(colorTexture, TRANSPARENT_ARGB, depthTexture, 1.0)
        } else {
            encoder.clearColorTexture(colorTexture, TRANSPARENT_ARGB)
        }
    }
}
