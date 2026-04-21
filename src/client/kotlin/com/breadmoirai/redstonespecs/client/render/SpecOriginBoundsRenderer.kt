package com.breadmoirai.redstonespecs.client.render

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecOriginBlockEntity
import com.breadmoirai.redstonespecs.data.AutoSpec
import com.breadmoirai.redstonespecs.data.BreakpointSpec
import com.breadmoirai.redstonespecs.data.InputSpec
import com.breadmoirai.redstonespecs.data.OutputSpec
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.levelgen.structure.BoundingBox
import org.joml.Matrix4f

fun registerBoundsRenderer() {
    BlockEntityRendererRegistry.register(
        ModRegistries.SPEC_ORIGIN_BLOCK_ENTITY_TYPE,
        ::SpecOriginBlockEntityRenderer,
    )
}

class SpecOriginRenderState : BlockEntityRenderState() {
    var bounds: BoundingBox? = null
    var activeEntries: List<SpecEntry> = emptyList()
}

class SpecOriginBlockEntityRenderer(ctx: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<SpecOriginBlockEntity, SpecOriginRenderState> {

    override fun createRenderState(): SpecOriginRenderState = SpecOriginRenderState()

    override fun extractRenderState(
        entity: SpecOriginBlockEntity,
        state: SpecOriginRenderState,
        partialTick: Float,
        cameraPos: net.minecraft.world.phys.Vec3,
        crumbling: net.minecraft.client.renderer.feature.ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        super.extractRenderState(entity, state, partialTick, cameraPos, crumbling)
        state.bounds = entity.spec?.bounds
        val spec = entity.spec
        state.activeEntries = if (spec != null && entity.activeSpecCaseIndex < spec.specCases.size) {
            spec.specCases[entity.activeSpecCaseIndex].allEntries
        } else {
            emptyList()
        }
    }

    override fun submit(
        state: SpecOriginRenderState,
        poseStack: PoseStack,
        collector: net.minecraft.client.renderer.SubmitNodeCollector,
        cameraState: CameraRenderState,
    ) {
        val mc = Minecraft.getInstance()
        val bufferSource = mc.renderBuffers().bufferSource()
        val buffer = bufferSource.getBuffer(RenderTypes.LINES)
        val matrix: Matrix4f = poseStack.last().pose()

        state.bounds?.let { drawBoundingBox(buffer, matrix, it, 1f, 1f, 0f, 0.8f) }

        for (entry in state.activeEntries) {
            val (r, g, b) = unpackColor(entryColor(entry))
            val pos = entry.pos
            val x1 = pos.x.toFloat()
            val y1 = pos.y.toFloat()
            val z1 = pos.z.toFloat()
            val x2 = x1 + 1f
            val y2 = y1 + 1f
            val z2 = z1 + 1f
            drawBox(buffer, matrix, x1, y1, z1, x2, y2, z2, r, g, b, 0.9f)
        }
    }

    override fun shouldRenderOffScreen(): Boolean = true

    override fun getViewDistance(): Int = 256

    private fun entryColor(entry: SpecEntry): Int = when (entry) {
        is BreakpointSpec -> 0xFF4444
        is AutoSpec -> 0xFFAA00
        else -> entry.color
    }

    private fun unpackColor(color: Int): Triple<Float, Float, Float> = Triple(
        ((color shr 16) and 0xFF) / 255f,
        ((color shr 8) and 0xFF) / 255f,
        (color and 0xFF) / 255f,
    )

    private fun drawBoundingBox(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        bounds: BoundingBox,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val x1 = bounds.minX().toFloat()
        val y1 = bounds.minY().toFloat()
        val z1 = bounds.minZ().toFloat()
        val x2 = bounds.maxX().toFloat() + 1f
        val y2 = bounds.maxY().toFloat() + 1f
        val z2 = bounds.maxZ().toFloat() + 1f
        drawBox(buffer, matrix, x1, y1, z1, x2, y2, z2, r, g, b, a)
    }

    private fun drawBox(
        buffer: VertexConsumer,
        matrix: Matrix4f,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
        r: Float, g: Float, b: Float, a: Float,
    ) {
        val nx = 0f; val ny = 1f; val nz = 0f

        fun line(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) {
            buffer.addVertex(matrix, ax, ay, az).setColor(r, g, b, a).setNormal(nx, ny, nz)
            buffer.addVertex(matrix, bx, by, bz).setColor(r, g, b, a).setNormal(nx, ny, nz)
        }

        // Bottom
        line(x1, y1, z1, x2, y1, z1); line(x2, y1, z1, x2, y1, z2)
        line(x2, y1, z2, x1, y1, z2); line(x1, y1, z2, x1, y1, z1)
        // Top
        line(x1, y2, z1, x2, y2, z1); line(x2, y2, z1, x2, y2, z2)
        line(x2, y2, z2, x1, y2, z2); line(x1, y2, z2, x1, y2, z1)
        // Verticals
        line(x1, y1, z1, x1, y2, z1); line(x2, y1, z1, x2, y2, z1)
        line(x2, y1, z2, x2, y2, z2); line(x1, y1, z2, x1, y2, z2)
    }
}
