package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.camera.input.OrbitCameraController;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Applies the orbit camera's pose once per rendered frame, at the exact point vanilla applies the
 * player's own mouse-look.
 *
 * <p>{@code Minecraft#runTick} runs, in order: {@code runAllTasks()} (which drains the
 * {@code minecraft.execute(...)}-wrapped GLFW callbacks, i.e. every mouse delta since the last
 * frame, into {@code DockInputRouter} and from there into {@code OrbitCameraController}), then zero
 * or more {@code tick()}s, then {@code MouseHandler#handleAccumulatedMovement()}, then
 * {@code renderFrame}. Injecting after {@code handleAccumulatedMovement} puts the camera commit on
 * the same schedule vanilla mouse-look already uses: after this frame's input, before this frame's
 * render.</p>
 *
 * <p>Why not {@code ClientTickEvents.END_CLIENT_TICK}, where this used to live: a client tick is
 * 20 Hz. Committing the pose there left the view frozen for every frame that did not contain a tick
 * and then advanced it by a whole tick's worth of accumulated mouse motion — an amount that varies
 * with how the frames fell — which is what made orbiting look jittery. See
 * {@code OrbitCameraController#applyFrame} for the other half of that fix.</p>
 *
 * <p>The controller no-ops unless a camera is armed <em>and</em> the player is already spectating,
 * so on every ordinary frame this costs one field read.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameMixin {

    @Inject(
        method = "runTick(Z)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/MouseHandler;handleAccumulatedMovement()V",
            shift = At.Shift.AFTER
        )
    )
    private void garnet$applyOrbitCamera(boolean renderLevel, CallbackInfo ci) {
        OrbitCameraController.INSTANCE.applyFrame((Minecraft) (Object) this);
    }
}
