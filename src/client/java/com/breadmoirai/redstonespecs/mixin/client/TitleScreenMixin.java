package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.project.ProjectRootListScreen;
import com.breadmoirai.redstonespecs.client.screen.RedstoneIconButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds a small "Redstone Projects..." icon button to the title screen, placed flush to the
 * right of the "Singleplayer" button (mirroring the language icon on the left). Opens
 * {@link ProjectRootListScreen}. Placed on the title screen rather than
 * {@code SelectWorldScreen} so users with no singleplayer worlds can still reach it.
 *
 * <p>The vanilla layout puts the 200×20 Singleplayer button at {@code (width/2 - 100, topPos)},
 * with the language icon at {@code (width/2 - 124, ...)} (4 px gap). We mirror that on the
 * right: a 20×20 button at {@code (width/2 + 104, topPos)}.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {

    private TitleScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "createNormalMenuOptions", at = @At("HEAD"))
    private void redstonespecs$addProjectButton(int topPos, int spacing, CallbackInfoReturnable<Integer> cir) {
        TitleScreen self = (TitleScreen) (Object) this;
        Component label = Component.literal("Redstone Projects...");
        RedstoneIconButton button = new RedstoneIconButton(
                this.width / 2 + 104, topPos, 20, label,
                b -> this.minecraft.setScreen(new ProjectRootListScreen(self))
        );
        button.setTooltip(Tooltip.create(label));
        this.addRenderableWidget(button);
    }
}
