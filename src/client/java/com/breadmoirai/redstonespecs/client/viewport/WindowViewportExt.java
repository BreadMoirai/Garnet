package com.breadmoirai.redstonespecs.client.viewport;

/**
 * Implemented by {@code WindowMixin} so client code can reach the viewport-shrink hook without
 * depending on the mixin package directly: {@code (WindowViewportExt) Minecraft.getInstance().window}.
 */
public interface WindowViewportExt {

    /**
     * Recomputes the framebuffer-size override from the current {@link ViewportState}, applying
     * it to the window's shadowed size/gui-scale fields.
     *
     * @param callResize whether to fire the window's resize callback if the effective size changed
     *                   (pass {@code false} during construction/early init, {@code true} for a
     *                   live toggle so dependent render targets resize immediately).
     */
    void redstonespecs$updateScaledFramebuffer(boolean callResize);
}
