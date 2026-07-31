package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.ui.viewport.ViewportState;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Viewport-shrink cursor remap. When {@link WindowMixin} shrinks the reported framebuffer, a
 * vanilla {@link net.minecraft.client.gui.screens.Screen} renders into the content sub-rect the
 * dock leaves free, offset by {@code (frameX, frameY)}. But MC maps the raw OS cursor to GUI-scaled
 * coordinates as {@code raw * guiScaledWidth / screenWidth} without subtracting that origin offset,
 * so screen hitboxes end up shifted by {@code frameX/scale} (you'd have to hover to the left of a
 * button to click it).
 *
 * <p>This targets only the <em>instance</em> overloads {@code getScaledXPos(Window)} /
 * {@code getScaledYPos(Window)} — the ones MC uses for the absolute cursor position it feeds to
 * {@code Screen#mouseMoved/mouseClicked/mouseScrolled}. The static {@code getScaledXPos(Window,
 * double)} overload is left untouched because it is also called with movement <em>deltas</em>
 * (drag distance, camera turn accumulation), which must not be offset.</p>
 *
 * <p>When the shrink is inactive both offsets are {@code 0} and every injection falls through
 * without changing the return value — byte-for-byte vanilla coordinate mapping.</p>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerViewportMixin {

    @Shadow
    private double xpos;

    @Shadow
    private double ypos;

    @Inject(method = "getScaledXPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
    private void garnet$offsetScaledXPos(Window window, CallbackInfoReturnable<Double> cir) {
        int offset = ViewportState.INSTANCE.contentOffsetX();
        if (offset != 0) {
            cir.setReturnValue(MouseHandler.getScaledXPos(window, this.xpos - offset));
        }
    }

    @Inject(method = "getScaledYPos(Lcom/mojang/blaze3d/platform/Window;)D", at = @At("HEAD"), cancellable = true)
    private void garnet$offsetScaledYPos(Window window, CallbackInfoReturnable<Double> cir) {
        int offset = ViewportState.INSTANCE.contentOffsetY();
        if (offset != 0) {
            cir.setReturnValue(MouseHandler.getScaledYPos(window, this.ypos - offset));
        }
    }
}
