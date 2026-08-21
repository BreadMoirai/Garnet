package com.breadmoirai.garnet.mixin.client;

import com.breadmoirai.garnet.camera.input.WorldHover;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Aims {@code Minecraft.hitResult} at the <em>free cursor</em> while the dock has freed it, so the
 * block the pointer is over gets vanilla's block highlight.
 *
 * <p>MC 26.2 decides <em>which</em> block to outline entirely from that one field:
 * {@code LevelExtractor#extractBlockOutline} reads {@code minecraft.hitResult}, and neither it nor
 * {@code LevelRenderer#submitBlockOutline} consults the gamemode. So the aiming half of the feature is
 * "point the existing hit result at the pointer", with no rendering code of our own and nothing to
 * keep in step with MC's render-state pipeline across versions. Building a
 * {@code BlockOutlineRenderState} ourselves in a {@code LevelExtractor} mixin was the alternative, and
 * it would have meant re-deriving vanilla's translucency flag, high-contrast option and shape lookup —
 * logic that drifts between versions.</p>
 *
 * <p><strong>Aiming is only half of it.</strong> Whether an outline is drawn <em>at all</em> is gated
 * one level up, in {@code GameRenderer#shouldRenderBlockOutline()}, which suppresses it for a
 * spectator on every block without a container menu — and camera mode makes the player a spectator.
 * An earlier version of this comment claimed there was "no spectator gate anywhere on that path"; that
 * was wrong, and the outline died on the first pan/zoom/orbit of a dock session with a perfectly
 * correct hit result sitting underneath it. {@link GameRendererOutlineMixin} defeats that gate, under
 * the same {@code hoveringWorld} condition this mixin uses.</p>
 *
 * <p>Overwriting {@code hitResult} rather than adding a parallel field is deliberate, and not just
 * for brevity. While the cursor is free, vanilla's crosshair-cast result is not a stale answer to
 * the question "what is the user pointing at" — it is an answer to a different question nobody
 * asked, aimed at the centre of the shrunk content rect the dock leaves free. Replacing it keeps
 * everything that reads it consistent with what the user sees, the F3 targeted-block readout
 * included. The interaction paths that also read it are unreachable in this window: dock focus means
 * {@code MouseHandlerMixin} swallows the presses before vanilla's key mappings ever go down.</p>
 *
 * <p>Injected at {@code TAIL} rather than {@code HEAD}-and-cancel so vanilla still does its own pick
 * first. {@code crosshairPickEntity} is then left holding the crosshair's entity, which is correct:
 * this feature is about blocks, and nothing consults that field while the dock is focused.</p>
 *
 * <p>{@link WorldHover#pickResult} returns null unless the cursor really is free over the bare world,
 * so on every ordinary frame this costs one boolean read. It returns a {@code MISS} rather than null
 * when the cursor is over the world but pointing at sky, which is what stops the crosshair's own hit
 * from leaking back in and outlining a block nobody is pointing at.</p>
 */
@Mixin(Minecraft.class)
public abstract class MinecraftPickMixin {

    @Shadow
    public HitResult hitResult;

    @Inject(method = "pick(F)V", at = @At("TAIL"))
    private void garnet$pickUnderCursor(float partialTicks, CallbackInfo ci) {
        HitResult hovered = WorldHover.INSTANCE.pickResult((Minecraft) (Object) this);
        if (hovered != null) {
            this.hitResult = hovered;
        }
    }
}
