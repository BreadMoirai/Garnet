package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a dock panel is focused, cancels vanilla key handling so keystrokes do not leak into the
 * game (movement, hotbar). Actual key delivery into Compose is forwarded via DockInputRouter. ESC
 * is special-cased: {@link DockInputRouter#onGlfwKey(int, int)} drops focus and reports the key as
 * consumed, so vanilla ESC (pause menu) never runs on top of a dropped dock focus.
 *
 * <p>MC 26.1.2 target (verified against the decompiled {@code net.minecraft.client.KeyboardHandler}):
 * {@code keyPress(long, int action, net.minecraft.client.input.KeyEvent event)} — the GLFW key
 * callback. (Older versions had {@code keyPress(long,int,int,int,int)}; 26.1.2 folds key/scancode/
 * mods into the {@code KeyEvent} record, so the GLFW key code is read via {@code KeyEvent#key()}.)
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), cancellable = true)
    private void garnet$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        DockInputRouter.INSTANCE.onGlfwKey(event.key(), action); // drops focus on ESC-press
        ci.cancel();
    }
}
