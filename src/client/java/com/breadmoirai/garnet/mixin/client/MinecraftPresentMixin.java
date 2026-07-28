package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay;
import com.breadmoirai.garnet.client.viewport.BlitUvPipeline;
import com.breadmoirai.garnet.client.viewport.CompositeTarget;
import com.breadmoirai.garnet.client.viewport.ViewportState;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Composite half of the viewport-shrink spike (the mate of {@link WindowMixin}).
 *
 * <p>{@code WindowMixin} makes the game render into a smaller {@code mainRenderTarget}; but MC's
 * default present ({@code Minecraft#renderFrame} → {@code GpuSurface#blitFromTexture(CommandEncoder,
 * GpuTextureView)}) <em>stretches</em> whatever texture it is handed to fill the whole window
 * surface. Left alone that yields a lower-res <em>full-screen</em> world, not a centered sub-rect.</p>
 *
 * <p>This mixin wraps the {@code GpuSurface#blitFromTexture} present call (MC 26.2 replaced the old
 * {@code RenderTarget#blitToScreen()} path). When {@link ViewportState#shouldModify()}
 * is on it builds a full-real-size off-screen composite, fills it with an opaque edge color, blits
 * the shrunk game texture into the centered content sub-rect ({@link ViewportState#contentRect}),
 * and presents the composite instead. When off it forwards the original call untouched, so vanilla
 * present is byte-for-byte unchanged.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPresentMixin {

    /**
     * Opaque fill for the reserved edge strips (a spike stand-in for future editor panels).
     * ARGB; alpha must be {@code 0xFF} or the strips present as transparent/black. A muted slate
     * blue so it is obviously "our" fill and not a vanilla clear color.
     */
    @Unique
    private static final int garnet_EDGE_FILL_ARGB = 0xFF243044;

    /** The off-screen composite we present in place of the shrunk main target. Lazily (re)sized. */
    @Unique
    private RenderTarget garnet$composite = null;

    @WrapOperation(
        method = "renderFrame",
        at = @At(
            value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"
        )
    )
    private void garnet$compositePresent(GpuSurface surface, CommandEncoder encoder, GpuTextureView gameTexture, Operation<Void> original) {
        if (!ViewportState.INSTANCE.shouldModify()) {
            original.call(surface, encoder, gameTexture);
            return;
        }

        int realWidth = ViewportState.INSTANCE.getRealWidth();
        int realHeight = ViewportState.INSTANCE.getRealHeight();

        // MC 26.2 hands the present call the main target's color texture view directly (the arg),
        // so we no longer fetch it off a RenderTarget. Guard against a not-yet-initialized real size
        // or a missing texture; fall back to vanilla present rather than a null/zero-size composite.
        if (realWidth <= 0 || realHeight <= 0 || gameTexture == null) {
            original.call(surface, encoder, gameTexture);
            return;
        }

        ViewportState.ContentRect rect = ViewportState.INSTANCE.contentRect(realWidth, realHeight);

        RenderTarget composite = CompositeTarget.INSTANCE.resizeOrCreate(this.garnet$composite, realWidth, realHeight);
        this.garnet$composite = composite;

        // Prime the whole composite with the opaque edge color, then draw the game texture on top
        // into the content sub-rect. The region the blit does not cover keeps the fill color, so
        // the reserved left/bottom strips are solid for free (no separate edge draw needed).
        CompositeTarget.INSTANCE.clearColor(composite, garnet_EDGE_FILL_ARGB);

        // Map the content rect to normalized [0,1] destination coordinates, top-left origin
        // (BlitUvPipeline flips to NDC internally). frameY is measured from the top, so y1 is the
        // top edge of the content and y2 the bottom edge — the reserved strip falls below it.
        float x1 = (float) rect.getFrameX() / realWidth;
        float y1 = (float) rect.getFrameY() / realHeight;
        float x2 = (float) (rect.getFrameX() + rect.getFrameWidth()) / realWidth;
        float y2 = (float) (rect.getFrameY() + rect.getFrameHeight()) / realHeight;

        // flipV=true: the game texture is a Blaze3D render-target color texture (stored bottom-up),
        // so it must be mirrored vertically to present upright — same flip vanilla's screenshot
        // readback applies. Without it the world renders upside-down in the sub-rect.
        BlitUvPipeline.INSTANCE.blit(gameTexture, composite, x1, y1, x2, y2, true, false);

        // Compose-in-MC feasibility spike: after the world blit, alpha-blend the full-window
        // Skia/Compose panel over the composite, so the world blitted above shows through the
        // transparent surround. Fully guarded inside ComposeOverlay (and again here) so any
        // Skia/Skiko failure falls back to the plain solid-edge composite rather than breaking present.
        try {
            ComposeOverlay.INSTANCE.renderInto(composite, realWidth, realHeight);
        } catch (Throwable composeFailure) {
            // ComposeOverlay already logs+disables internally; this is a last-resort backstop.
        }

        // Diagnostic: if a capture was requested, dump the just-composited frame to a PNG. This is
        // the only way to see the composite in an image, since the normal screenshot path captures
        // the (upstream) main target, not this composite.
        java.nio.file.Path capturePath = ViewportState.INSTANCE.getCompositeCaptureRequest();
        if (capturePath != null) {
            ViewportState.INSTANCE.setCompositeCaptureRequest(null);
            CompositeTarget.INSTANCE.captureToPng(composite, capturePath);
        }

        // Present the composite (full real size) instead of the shrunk game texture. MC 26.2 presents
        // by blitting a GpuTextureView onto the window GpuSurface, so we substitute the composite's
        // color texture view — it maps 1:1 to the window surface, landing the content in its sub-rect.
        GpuTextureView compositeView = composite.getColorTextureView();
        if (compositeView == null) {
            original.call(surface, encoder, gameTexture);
            return;
        }
        original.call(surface, encoder, compositeView);
    }
}
