package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.dock.input.DockInputRouter;
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
 * When not captured, move/scroll fall through untouched and {@code onButton} consults two
 * click-to-focus helpers that decline unless the dock is on screen with a vanilla Screen open — so
 * with the dock closed input is byte-for-byte vanilla.
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
        if (!DockInputRouter.INSTANCE.getCaptured()) {
            // Uncaptured is no longer an unconditional fall-through: click-to-focus works inbound too.
            // A click on a dock region while a vanilla Screen is open focuses that region and lands on
            // the widget (onGlfwPressUncaptured); and a release completing a press that already
            // consumed itself as a focus gesture must not reach vanilla as an unmatched button-up.
            // Both helpers return false unless they actually handled the event, so with the dock closed
            // this branch is a plain field read and input stays byte-for-byte vanilla.
            if (action == GLFW.GLFW_PRESS) {
                if (DockInputRouter.INSTANCE.onGlfwPressUncaptured(buttonInfo.button())) ci.cancel();
            } else if (action == GLFW.GLFW_RELEASE) {
                if (DockInputRouter.INSTANCE.consumeSwallowedRelease(buttonInfo.button())) ci.cancel();
            }
            return;
        }
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
