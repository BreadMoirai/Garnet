package com.breadmoirai.redstonespecs.mixin.client;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.List;

@Mixin(Screen.class)
abstract class ScreenMixin {

    @Shadow
    protected List<AbstractWidget> renderables;

    private static final Method WIDGET_TICK_METHOD;

    static {
        try {
            // Cache the tick method if it exists on AbstractWidget or EditBox
            WIDGET_TICK_METHOD = AbstractWidget.class.getDeclaredMethod("tick");
            WIDGET_TICK_METHOD.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Inject(
        method = "tick()V",
        at = @At("HEAD")
    )
    private void redstonespecs$tickWidgets(CallbackInfo ci) {
        // Tick all widgets by calling their tick() method if they have one
        if (renderables != null) {
            for (AbstractWidget widget : renderables) {
                try {
                    // Use introspection to find and call tick() on widget instances
                    Method tickMethod = widget.getClass().getMethod("tick");
                    tickMethod.invoke(widget);
                } catch (NoSuchMethodException ignored) {
                    // Widget doesn't have tick() - skip it
                } catch (Exception e) {
                    // Other exceptions - log but don't crash
                    e.printStackTrace();
                }
            }
        }
    }
}
