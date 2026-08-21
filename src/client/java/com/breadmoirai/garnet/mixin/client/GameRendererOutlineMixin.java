package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.dock.input.DockInputRouter;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the hovered-block outline actually <em>draw</em> while the free cursor is driving the pick.
 *
 * <p>{@link MinecraftPickMixin} aims {@code Minecraft.hitResult} at the pointer, which is only half
 * the feature: MC 26.2 decides <em>whether to draw an outline at all</em> in a second, independent
 * place. {@code GameRenderer#shouldRenderBlockOutline()} returns a boolean that
 * {@code renderLevel} hands to {@code LevelRenderer#render} as its {@code renderOutline} flag, and
 * that flag is what reaches {@code submitBlockOutline}. Nothing downstream of it consults the
 * gamemode — which is why {@code LevelExtractor} looks gate-free — but the flag itself is gated:</p>
 *
 * <pre>{@code
 * boolean renderOutline = cameraEntity instanceof Player && !this.minecraft.gui.hud.isHidden();
 * if (renderOutline && !((Player)cameraEntity).getAbilities().mayBuild) {
 *     ...
 *     if (this.minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
 *         renderOutline = blockState.getMenuProvider(this.minecraft.level, pos) != null;
 *     } else { // adventure
 *         renderOutline = !itemStack.isEmpty() && (itemStack.canBreakBlockInAdventureMode(...) || ...);
 *     }
 * }
 * }</pre>
 *
 * <p>{@code GameType.SPECTATOR} is block-placing-restricted, so a spectator's {@code mayBuild} is
 * false and the restricted branch is taken; inside it, a spectator outlines <em>only</em> blocks
 * that open a container menu. Camera mode puts the player into spectator
 * ({@code OrbitCameraController.enter}), so the first pan/zoom/orbit of a dock session silently
 * killed the highlight on every ordinary block for the rest of that session — while the pick under
 * it stayed perfectly correct, which is what made the bug look like a pick bug. Hovering alone never
 * arms camera mode, so the highlight worked right up until the first gesture.</p>
 *
 * <p>Gated on {@link DockInputRouter#getHoveringWorld()} — the same single condition the pick
 * override uses — rather than forced unconditionally. That is deliberate: outside garnet mode the
 * cursor is grabbed and the crosshair genuinely is the pointer, so vanilla's rules are the right
 * ones and must keep applying in full. A player who hides the HUD, or who is spectating or in
 * adventure mode with the dock closed, sees exactly what vanilla shows them. Sharing the condition
 * also keeps the two halves from disagreeing: a frame that picks under the cursor is exactly a frame
 * that draws under the cursor.</p>
 *
 * <p>Injected at {@code RETURN} with {@code cancellable = true} rather than {@code HEAD}-and-cancel
 * so vanilla still computes its own answer first. Only the {@code false} results matter to us and
 * overriding a {@code true} to {@code true} costs nothing, so the branch is left as a plain
 * "force it on" rather than trying to identify which of vanilla's several {@code false} paths ran.
 * Forcing {@code true} on a frame where the cursor is over sky is harmless: the hit result is then a
 * {@code MISS}, and {@code LevelExtractor#extractBlockOutline} skips a miss whatever this flag says.</p>
 *
 * <p>Pinned by {@code BlockOutlineGateSpec}, which reads this very method reflectively in a client
 * that believes it is spectating.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererOutlineMixin {

    @Inject(method = "shouldRenderBlockOutline()Z", at = @At("RETURN"), cancellable = true)
    private void garnet$outlineUnderCursor(CallbackInfoReturnable<Boolean> cir) {
        if (DockInputRouter.INSTANCE.getHoveringWorld()) {
            cir.setReturnValue(true);
        }
    }
}
