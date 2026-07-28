package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.client.SpecBoundsInteractionKt;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ScrollWheelHandler;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ScrollWheelHandler.class)
abstract class ScrollWheelHandlerMixin {

    @Inject(
        method = "onMouseScroll(DD)Lorg/joml/Vector2i;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void garnet$interceptCtrlScroll(double xOffset, double yOffset, CallbackInfoReturnable<Vector2i> cir) {
        if (yOffset == 0.0) return;
        Minecraft mc = Minecraft.getInstance();
        if (!InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_LEFT_CONTROL)
                && !InputConstants.isKeyDown(mc.getWindow(), GLFW.GLFW_KEY_RIGHT_CONTROL)) return;
        if (SpecBoundsInteractionKt.getCurrentHoveredFace() == null) return;
        SpecBoundsInteractionKt.handleCtrlScroll(yOffset);
        cir.setReturnValue(new Vector2i(0, 0));
    }
}
