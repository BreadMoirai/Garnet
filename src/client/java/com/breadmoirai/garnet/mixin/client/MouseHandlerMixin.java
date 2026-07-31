package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.ui.input.DockInputRouter;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes raw cursor/button/scroll into the dock ComposeScene while a panel is focused
 * (DockInputRouter.captured), cancelling vanilla handling so the camera/hotbar do not react.
 * When not captured every injection falls through untouched — byte-for-byte vanilla input.
 *
 * <p>MC 26.1.2 GLFW-callback targets (verified against the decompiled
 * {@code net.minecraft.client.MouseHandler}):
 * <ul>
 *   <li>{@code onMove(long, double, double)} — cursor position callback.</li>
 *   <li>{@code onButton(long, net.minecraft.client.input.MouseButtonInfo, int)} — button callback;
 *       {@code action == GLFW_PRESS/RELEASE}, button index via {@code MouseButtonInfo#button()}.
 *       (Older versions named this {@code onPress(long,int,int,int)}; 26.1.2 folds the button+mods
 *       into a {@code MouseButtonInfo} record.)</li>
 *   <li>{@code onScroll(long, double, double)} — scroll callback.</li>
 * </ul>
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true)
    private void garnet$onMove(long window, double x, double y, CallbackInfo ci) {
        DockInputRouter.INSTANCE.onGlfwMove(x, y);
        if (DockInputRouter.INSTANCE.getCaptured()) ci.cancel();
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), cancellable = true)
    private void garnet$onButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        if (action == GLFW.GLFW_PRESS) DockInputRouter.INSTANCE.onGlfwPress(buttonInfo.button());
        else if (action == GLFW.GLFW_RELEASE) DockInputRouter.INSTANCE.onGlfwRelease(buttonInfo.button());
        ci.cancel();
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void garnet$onScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        DockInputRouter.INSTANCE.onGlfwScroll(dx, dy);
        ci.cancel();
    }
}
