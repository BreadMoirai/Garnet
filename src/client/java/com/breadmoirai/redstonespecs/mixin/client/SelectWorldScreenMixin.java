package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.managed.ManagedRootListScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds a "Managed Specs..." button to the world-selection screen, opening
 * {@link ManagedRootListScreen} (T20). Selecting a root will (T21) boot the integrated
 * server pinned to that root.
 *
 * <p>Extends {@link Screen} so that {@code addRenderableWidget} (protected on the target's
 * superclass) is accessible from the injected method without resorting to reflection.</p>
 */
@Mixin(SelectWorldScreen.class)
public abstract class SelectWorldScreenMixin extends Screen {

    private SelectWorldScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void redstonespecs$addManagedButton(CallbackInfo ci) {
        SelectWorldScreen self = (SelectWorldScreen) (Object) this;
        Button button = Button.builder(Component.literal("Managed Specs..."), b ->
                Minecraft.getInstance().setScreen(new ManagedRootListScreen(self))
        ).bounds(this.width / 2 - 75, this.height - 28 - 24, 150, 20).build();
        this.addRenderableWidget(button);
    }
}
