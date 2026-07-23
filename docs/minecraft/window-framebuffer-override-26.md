---
title: Overriding Window framebuffer size on MC 26.2 (viewport-shrink lever)
tags: [mc-api, mixins, versions, quirks, render-state]
summary: WindowMixin overrides com.mojang.blaze3d.platform.Window's public getters to make MC render at a shrunk framebuffer size; corrects a WindowEventHandler method-name assumption and lists which fields must be recomputed manually.
---

# Overriding Window framebuffer size on MC 26.2 (viewport-shrink lever)

`WindowMixin` (`src/client/java/.../mixin/client/WindowMixin.java`) makes
`com.mojang.blaze3d.platform.Window` lie about its size when
`ViewportState.active` is on, by canceling six public getters/setters at
`@At("HEAD")`. This is the "shrink lever": MC renders the world+HUD at a
smaller resolution, but the present blit still stretches that image to fill
the real surface (see `blaze3d-custom-blit-pipeline-26.md` for the compositing
half of this).

## `WindowEventHandler` has `resizeGui()`, not `framebufferSizeChanged()`

An earlier draft of the spike plan assumed `WindowEventHandler` exposed a
`framebufferSizeChanged()` method (based on skimming an older Flashback
snapshot). The actual MC 26.2 interface
(`com.mojang.blaze3d.platform.WindowEventHandler`, confirmed by extracting
`minecraft-clientOnly-*-sources.jar`) only has:

```java
public interface WindowEventHandler {
    void resizeGui();
    void cursorEntered();
}
```

`Window.onFramebufferResize` calls `eventHandler.resizeGui()` when the real
framebuffer size changes — so `resizeGui()` is the correct call for the mixin
to invoke after changing the override, to make dependent render targets
(`Minecraft.resize` → `mainRenderTarget.resize`) pick up the new size.

## `getWidth()`/`getHeight()` return the *framebuffer* size, not the window size

Vanilla's `Window.getWidth()`/`getHeight()` return `framebufferWidth`/
`framebufferHeight` (physical pixels), while `getScreenWidth()`/
`getScreenHeight()` return `width`/`height` (OS window/screen coordinates,
which can differ under display scaling). For the spike we override all four
to the same content-rect dimensions — a simplification that treats screen
and framebuffer coordinates as equal, acceptable because the spike doesn't
yet handle HiDPI display scaling.

## `calculateScale`/`setGuiScale` read the shadowed fields directly, not the getters

Vanilla's `calculateScale(int, boolean)` and `setGuiScale(int)` compute
against the raw `framebufferWidth`/`framebufferHeight` fields, not through
`getWidth()`/`getHeight()`. Canceling only the getters would leave gui-scale
math using the *real* framebuffer size even while `getWidth()` reports the
shrunk one — so `WindowMixin` also injects into `calculateScale`/`setGuiScale`
directly, redoing their arithmetic against the override dimensions.

## `guiScaledWidth`/`guiScaledHeight` are read as fields elsewhere, not just via getters

`getGuiScaledWidth()`/`getGuiScaledHeight()` aren't in the six injected
methods (the spike doesn't need to intercept them), but the underlying
`guiScaledWidth`/`guiScaledHeight` *fields* are read directly by other MC
code. That means the override must actively **write** those shadowed fields
(mirroring `setGuiScale`'s division/ceil math) any time the effective size
changes — both when activating the override and when deactivating it back to
the real size — rather than only reacting inside an injected getter.

## Casting to a mixin-added interface from Kotlin

Java code reaches a mixin-injected interface via the standard double-cast
idiom `(WindowExt)(Object) window`, because `Window` is `final` at compile
time and the interface cast would otherwise be rejected by javac. Kotlin's
equivalent is `(window as Any) as WindowViewportExt` — a direct
`window as WindowViewportExt` compiles but the Kotlin compiler emits a
"cast can never succeed" warning since it can statically see `Window` is
`final` and unrelated to the interface.
