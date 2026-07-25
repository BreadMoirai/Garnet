package com.breadmoirai.redstonespecs.client.viewport

import com.mojang.blaze3d.buffers.GpuBuffer
import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import com.mojang.blaze3d.textures.GpuTextureView
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
import net.minecraft.resources.Identifier
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.OptionalInt

/**
 * Clean-room blit pipeline for MC 26.2's Blaze3D GPU API.
 *
 * Draws an arbitrary [GpuTextureView] into an arbitrary normalized sub-rect of a
 * [RenderTarget] by recording a one-shot [com.mojang.blaze3d.systems.RenderPass]
 * that samples the source through our own `blit_uv` shader pair.
 *
 * Modeled conceptually on how vanilla records a blit (see
 * `RenderTarget.blitAndBlendToTexture`, which uses the `screenquad`/`blit_screen`
 * shaders and a vertex-ID generated triangle). We differ deliberately: we use a
 * real `POSITION_TEX` quad so the caller controls both the destination rect (via
 * NDC positions) and the source region (via UVs), rather than always covering the
 * whole target.
 */
object BlitUvPipeline {

    private const val NAMESPACE = "redstonespecs"

    /** Bytes per `POSITION_TEX` vertex: 3 floats position + 2 floats UV. */
    private const val VERTEX_STRIDE = (3 + 2) * Float.SIZE_BYTES

    /** Four vertices, indexed as two triangles by the shared QUADS index buffer. */
    private const val VERTEX_COUNT = 4
    private const val INDEX_COUNT = 6

    val PIPELINE: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/blit_uv"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withSampler("InSampler")
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
        // A screen-aligned blit quad should never be back-face culled regardless of
        // winding, so disable culling for robustness.
        .withCull(false)
        .build()

    /**
     * Blend variant for premultiplied-alpha sources (Skia/Compose): `dst = src + dst*(1-srcA)`.
     *
     * MC 26.2's [RenderPipeline.Builder] has no `withBlend(...)` method and
     * `SourceFactor`/`DestFactor` are top-level types under `com.mojang.blaze3d.platform`
     * (not nested in `GlStateManager`). Blending is configured via
     * [RenderPipeline.Builder.withColorTargetState] with a [ColorTargetState] wrapping a
     * [BlendFunction]. [BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA] already encodes the
     * exact `(ONE, ONE_MINUS_SRC_ALPHA)` factors needed for both the color and alpha channels.
     */
    val PIPELINE_BLEND: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/blit_uv_blend"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withSampler("InSampler")
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
        .withCull(false)
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        .build()

    /**
     * Blit [from] into the sub-rect of [to] described by the normalized rectangle
     * `(x1,y1)`..`(x2,y2)`, where `(0,0)` is the top-left of the target and `(1,1)`
     * the bottom-right. The full source texture is sampled across the rect.
     *
     * [flipV] mirrors the source vertically. Pass `true` when [from] is a Blaze3D render-target
     * color texture: those are stored bottom-up (row 0 = bottom), the same convention vanilla's
     * `Screenshot` readback compensates for with `height - y - 1`. Sampling one with the default
     * top-left UV mapping would present it upside-down. The default (`false`) keeps the plain
     * top-left-origin mapping for ordinary textures (atlases, PNG-backed textures).
     *
     * [blend] selects [PIPELINE_BLEND] instead of the opaque [PIPELINE], alpha-compositing
     * [from] over the existing contents of [to] (premultiplied-alpha over). Use this for
     * Skia/Compose surfaces so transparent regions let the destination show through.
     */
    fun blit(from: GpuTextureView, to: RenderTarget, x1: Float, y1: Float, x2: Float, y2: Float, flipV: Boolean = false, blend: Boolean = false) {
        // Normalized (top-left origin) -> NDC. X: [0,1] -> [-1,1]. Y is flipped
        // because NDC Y grows upward while our normalized Y grows downward.
        val ndcX1 = x1 * 2f - 1f
        val ndcX2 = x2 * 2f - 1f
        val ndcTop = 1f - y1 * 2f
        val ndcBottom = 1f - y2 * 2f

        // Source V at the top/bottom edges of the quad. Swapped when [flipV] so a bottom-up
        // render-target texture samples upright.
        val vTop = if (flipV) 1f else 0f
        val vBottom = if (flipV) 0f else 1f

        val vertexData = ByteBuffer.allocateDirect(VERTEX_COUNT * VERTEX_STRIDE)
            .order(ByteOrder.nativeOrder())
        // QUADS winding: TL, BL, BR, TR. UV (0,0) is the top-left of the source (for the
        // un-flipped case) so the image is upright for standard top-left-origin textures.
        putVertex(vertexData, ndcX1, ndcTop, 0f, vTop)
        putVertex(vertexData, ndcX1, ndcBottom, 0f, vBottom)
        putVertex(vertexData, ndcX2, ndcBottom, 1f, vBottom)
        putVertex(vertexData, ndcX2, ndcTop, 1f, vTop)
        vertexData.flip()

        val target = requireNotNull(to.colorTextureView) { "Blit target has no color texture view" }
        val device = RenderSystem.getDevice()
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        val indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)
        val indexBuffer = indices.getBuffer(INDEX_COUNT)
        val indexType = indices.type()

        device.createBuffer({ "RedstoneSpecs blit_uv vertices" }, GpuBuffer.USAGE_VERTEX, vertexData).use { vertexBuffer ->
            device.createCommandEncoder()
                .createRenderPass({ "RedstoneSpecs blit_uv" }, target, OptionalInt.empty())
                .use { pass ->
                    pass.setPipeline(if (blend) PIPELINE_BLEND else PIPELINE)
                    pass.bindTexture("InSampler", from, sampler)
                    pass.setVertexBuffer(0, vertexBuffer)
                    pass.setIndexBuffer(indexBuffer, indexType)
                    pass.drawIndexed(0, 0, INDEX_COUNT, 1)
                }
        }
    }

    private fun putVertex(buffer: ByteBuffer, x: Float, y: Float, u: Float, v: Float) {
        buffer.putFloat(x).putFloat(y).putFloat(0f).putFloat(u).putFloat(v)
    }
}
