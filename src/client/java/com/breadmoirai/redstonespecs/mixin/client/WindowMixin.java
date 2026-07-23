package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.viewport.ViewportState;
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.WindowEventHandler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Viewport-shrink lever: when {@link ViewportState#shouldModify()} is true, this mixin makes the
 * {@link Window} lie about its framebuffer/screen size and gui-scale, so vanilla rendering and
 * gui-scale math (which all read these fields/getters) produce a smaller image than the real
 * window. Nothing here changes *where* that smaller image ends up on screen — MC's default
 * present still stretches it full-surface. Compositing the shrunk image into a sub-rect of the
 * real window is a separate concern (the blit_uv pipeline), layered on top of this lever.
 *
 * <p>Every injected method falls through untouched when {@link #redstonespecs$overrideFramebufferWidth}
 * is {@code -1} (the default / inactive state), so vanilla behavior is unaffected until the
 * viewport-shrink keybind is toggled on.</p>
 */
@Mixin(Window.class)
public abstract class WindowMixin implements WindowViewportExt {

    @Shadow
    private int framebufferWidth;

    @Shadow
    private int framebufferHeight;

    @Shadow
    private int width;

    @Shadow
    private int height;

    @Shadow
    private int guiScale;

    @Shadow
    private int guiScaledWidth;

    @Shadow
    private int guiScaledHeight;

    @Shadow
    @Final
    private WindowEventHandler eventHandler;

    /** Overridden framebuffer width in effect, or {@code -1} when the shrink lever is off. */
    @Unique
    private int redstonespecs$overrideFramebufferWidth = -1;

    /** Overridden framebuffer height in effect, or {@code -1} when the shrink lever is off. */
    @Unique
    private int redstonespecs$overrideFramebufferHeight = -1;

    @Override
    @Unique
    public void redstonespecs$updateScaledFramebuffer(boolean callResize) {
        // Snapshot the real (un-overridden) framebuffer size before touching anything, since
        // ViewportState.contentRect needs the true window size and the fields below are about
        // to start lying once the override is (re)computed.
        ViewportState.INSTANCE.setRealWidth(this.framebufferWidth);
        ViewportState.INSTANCE.setRealHeight(this.framebufferHeight);

        int previousWidth = this.redstonespecs$effectiveWidth();
        int previousHeight = this.redstonespecs$effectiveHeight();

        if (ViewportState.INSTANCE.shouldModify()) {
            ViewportState.ContentRect rect = ViewportState.INSTANCE.contentRect(this.framebufferWidth, this.framebufferHeight);
            this.redstonespecs$overrideFramebufferWidth = rect.getFrameWidth();
            this.redstonespecs$overrideFramebufferHeight = rect.getFrameHeight();
        } else {
            this.redstonespecs$overrideFramebufferWidth = -1;
            this.redstonespecs$overrideFramebufferHeight = -1;
        }

        // Keep the gui-scale fields consistent with whatever size is now effective, mirroring
        // Window#setGuiScale's math but against the override (or real size, when inactive).
        this.redstonespecs$applyGuiScale(this.guiScale);

        int newWidth = this.redstonespecs$effectiveWidth();
        int newHeight = this.redstonespecs$effectiveHeight();
        if (callResize && (newWidth != previousWidth || newHeight != previousHeight)) {
            this.eventHandler.resizeGui();
        }
    }

    @Unique
    private int redstonespecs$effectiveWidth() {
        return this.redstonespecs$overrideFramebufferWidth != -1 ? this.redstonespecs$overrideFramebufferWidth : this.framebufferWidth;
    }

    @Unique
    private int redstonespecs$effectiveHeight() {
        return this.redstonespecs$overrideFramebufferHeight != -1 ? this.redstonespecs$overrideFramebufferHeight : this.framebufferHeight;
    }

    /** Recomputes {@link #guiScaledWidth}/{@link #guiScaledHeight} for the given scale, mirroring {@code Window#setGuiScale}. */
    @Unique
    private void redstonespecs$applyGuiScale(int scale) {
        int effectiveWidth = this.redstonespecs$effectiveWidth();
        int effectiveHeight = this.redstonespecs$effectiveHeight();
        double doubleScale = scale;
        int scaledWidth = (int) (effectiveWidth / doubleScale);
        this.guiScaledWidth = effectiveWidth / doubleScale > scaledWidth ? scaledWidth + 1 : scaledWidth;
        int scaledHeight = (int) (effectiveHeight / doubleScale);
        this.guiScaledHeight = effectiveHeight / doubleScale > scaledHeight ? scaledHeight + 1 : scaledHeight;
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$getWidth(CallbackInfoReturnable<Integer> cir) {
        if (this.redstonespecs$overrideFramebufferWidth != -1) {
            cir.setReturnValue(this.redstonespecs$overrideFramebufferWidth);
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$getHeight(CallbackInfoReturnable<Integer> cir) {
        if (this.redstonespecs$overrideFramebufferHeight != -1) {
            cir.setReturnValue(this.redstonespecs$overrideFramebufferHeight);
        }
    }

    @Inject(method = "getScreenWidth", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$getScreenWidth(CallbackInfoReturnable<Integer> cir) {
        if (this.redstonespecs$overrideFramebufferWidth != -1) {
            cir.setReturnValue(this.redstonespecs$overrideFramebufferWidth);
        }
    }

    @Inject(method = "getScreenHeight", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$getScreenHeight(CallbackInfoReturnable<Integer> cir) {
        if (this.redstonespecs$overrideFramebufferHeight != -1) {
            cir.setReturnValue(this.redstonespecs$overrideFramebufferHeight);
        }
    }

    @Inject(method = "calculateScale", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$calculateScale(int maxScale, boolean enforceUnicode, CallbackInfoReturnable<Integer> cir) {
        if (this.redstonespecs$overrideFramebufferWidth == -1) {
            return;
        }
        int fbWidth = this.redstonespecs$overrideFramebufferWidth;
        int fbHeight = this.redstonespecs$overrideFramebufferHeight;
        int scale = 1;
        while (scale != maxScale && scale < fbWidth && scale < fbHeight && fbWidth / (scale + 1) >= 320 && fbHeight / (scale + 1) >= 240) {
            scale++;
        }
        if (enforceUnicode && scale % 2 != 0) {
            scale++;
        }
        cir.setReturnValue(scale);
    }

    @Inject(method = "setGuiScale", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$setGuiScale(int scale, CallbackInfo ci) {
        if (this.redstonespecs$overrideFramebufferWidth == -1) {
            return;
        }
        this.guiScale = scale;
        this.redstonespecs$applyGuiScale(scale);
        ci.cancel();
    }
}
