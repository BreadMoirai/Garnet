package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.ui.viewport.ViewportState;
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt;
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
 * <p>Every injected method falls through untouched when {@link #garnet$overrideFramebufferWidth}
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
    private int garnet$overrideFramebufferWidth = -1;

    /** Overridden framebuffer height in effect, or {@code -1} when the shrink lever is off. */
    @Unique
    private int garnet$overrideFramebufferHeight = -1;

    @Override
    @Unique
    public void garnet$updateScaledFramebuffer(boolean callResize) {
        // Snapshot the real (un-overridden) framebuffer size before touching anything, since
        // ViewportState.contentRect needs the true window size and the fields below are about
        // to start lying once the override is (re)computed.
        ViewportState.INSTANCE.setRealWidth(this.framebufferWidth);
        ViewportState.INSTANCE.setRealHeight(this.framebufferHeight);

        int previousWidth = this.garnet$effectiveWidth();
        int previousHeight = this.garnet$effectiveHeight();

        if (ViewportState.INSTANCE.shouldModify()) {
            ViewportState.ContentRect rect = ViewportState.INSTANCE.contentRect(this.framebufferWidth, this.framebufferHeight);
            this.garnet$overrideFramebufferWidth = rect.getFrameWidth();
            this.garnet$overrideFramebufferHeight = rect.getFrameHeight();
        } else {
            this.garnet$overrideFramebufferWidth = -1;
            this.garnet$overrideFramebufferHeight = -1;
        }

        // Keep the gui-scale fields consistent with whatever size is now effective, mirroring
        // Window#setGuiScale's math but against the override (or real size, when inactive).
        this.garnet$applyGuiScale(this.guiScale);

        int newWidth = this.garnet$effectiveWidth();
        int newHeight = this.garnet$effectiveHeight();
        if (newWidth != previousWidth || newHeight != previousHeight) {
            // MC 26.2 dropped the Window#isResized flag: GameRenderer#render now resizes the main
            // render target automatically whenever the extracted window size (window.getWidth()/
            // getHeight(), which our injections above already override) differs from the target's
            // current size. So changing the effective size is enough to trigger the resize next
            // frame; we only still fire resizeGui() to refresh the gui-scale/layout immediately.
            if (callResize) {
                this.eventHandler.resizeGui();
            }
        }
    }

    @Unique
    private int garnet$effectiveWidth() {
        return this.garnet$overrideFramebufferWidth != -1 ? this.garnet$overrideFramebufferWidth : this.framebufferWidth;
    }

    @Unique
    private int garnet$effectiveHeight() {
        return this.garnet$overrideFramebufferHeight != -1 ? this.garnet$overrideFramebufferHeight : this.framebufferHeight;
    }

    /** Recomputes {@link #guiScaledWidth}/{@link #guiScaledHeight} for the given scale, mirroring {@code Window#setGuiScale}. */
    @Unique
    private void garnet$applyGuiScale(int scale) {
        int effectiveWidth = this.garnet$effectiveWidth();
        int effectiveHeight = this.garnet$effectiveHeight();
        double doubleScale = scale;
        int scaledWidth = (int) (effectiveWidth / doubleScale);
        this.guiScaledWidth = effectiveWidth / doubleScale > scaledWidth ? scaledWidth + 1 : scaledWidth;
        int scaledHeight = (int) (effectiveHeight / doubleScale);
        this.guiScaledHeight = effectiveHeight / doubleScale > scaledHeight ? scaledHeight + 1 : scaledHeight;
    }

    @Inject(method = "getWidth", at = @At("HEAD"), cancellable = true)
    private void garnet$getWidth(CallbackInfoReturnable<Integer> cir) {
        if (this.garnet$overrideFramebufferWidth != -1) {
            cir.setReturnValue(this.garnet$overrideFramebufferWidth);
        }
    }

    @Inject(method = "getHeight", at = @At("HEAD"), cancellable = true)
    private void garnet$getHeight(CallbackInfoReturnable<Integer> cir) {
        if (this.garnet$overrideFramebufferHeight != -1) {
            cir.setReturnValue(this.garnet$overrideFramebufferHeight);
        }
    }

    @Inject(method = "getScreenWidth", at = @At("HEAD"), cancellable = true)
    private void garnet$getScreenWidth(CallbackInfoReturnable<Integer> cir) {
        if (this.garnet$overrideFramebufferWidth != -1) {
            cir.setReturnValue(this.garnet$overrideFramebufferWidth);
        }
    }

    @Inject(method = "getScreenHeight", at = @At("HEAD"), cancellable = true)
    private void garnet$getScreenHeight(CallbackInfoReturnable<Integer> cir) {
        if (this.garnet$overrideFramebufferHeight != -1) {
            cir.setReturnValue(this.garnet$overrideFramebufferHeight);
        }
    }

    @Inject(method = "calculateScale", at = @At("HEAD"), cancellable = true)
    private void garnet$calculateScale(int maxScale, boolean enforceUnicode, CallbackInfoReturnable<Integer> cir) {
        if (this.garnet$overrideFramebufferWidth == -1) {
            return;
        }
        int fbWidth = this.garnet$overrideFramebufferWidth;
        int fbHeight = this.garnet$overrideFramebufferHeight;
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
    private void garnet$setGuiScale(int scale, CallbackInfo ci) {
        if (this.garnet$overrideFramebufferWidth == -1) {
            return;
        }
        this.guiScale = scale;
        this.garnet$applyGuiScale(scale);
        ci.cancel();
    }

    /**
     * On a real OS window resize, {@code onFramebufferResize} has just written the new real size to
     * {@link #framebufferWidth}/{@link #framebufferHeight} and is about to fire the handler callback
     * (MC 26.2 renamed it {@code resizeGui()} → {@code framebufferSizeChanged()}). Recompute the
     * override from the fresh real size first (with {@code callResize=false} to avoid recursing into
     * the resize we are already inside), so the shrink tracks the new window size instead of leaving
     * a stale override. No-op when the effect is off.
     */
    @Inject(
        method = "onFramebufferResize",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/WindowEventHandler;framebufferSizeChanged()V")
    )
    private void garnet$onFramebufferResize(long handle, int newWidth, int newHeight, CallbackInfo ci) {
        if (ViewportState.INSTANCE.shouldModify()) {
            this.garnet$updateScaledFramebuffer(false);
        }
    }
}
