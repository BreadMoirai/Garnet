---
title: Shrink + composite + Compose overlay — how one frame is built
tags: [compose, viewport, rendering, frame-ordering, architecture]
summary: How WindowMixin's framebuffer shrink, MinecraftPresentMixin's composite blit, and the full-window blended Compose overlay stack into one presented frame, and why the shrink is never gated on a Compose pass.
---

# Shrink + composite + Compose overlay — how one frame is built

The full-window Compose dock (see [ui/dock-framework.md](../ui/dock-framework.md)) sits on
top of two older pieces of machinery — the framebuffer-shrink lever and the present-time composite —
that predate it. This article is the frame-ordering picture: what runs when, and why the pieces don't
fight each other.

## Three layers, one presented frame

1. **`WindowMixin`** (`src/client/java/.../mixin/client/WindowMixin.java`) — when
   `ViewportState.shouldModify()` is true, makes `Window.getWidth/getHeight/getScreenWidth/
   getScreenHeight/calculateScale` **lie**: they report a shrunk size (`ViewportState.contentRect`)
   instead of the real framebuffer size. Every piece of vanilla rendering and gui-scale math that reads
   those getters — including `GameRenderer#extractWindow`, which resizes the main render target —
   therefore renders the *game world* at the smaller effective size. `WindowMixin` does not decide
   *where* the smaller image ends up on screen; it only shrinks what gets rendered.
2. **`MinecraftPresentMixin`** (`@WrapOperation` on `RenderTarget.blitToScreen()` inside
   `Minecraft.renderFrame`) — composites that shrunk game texture into a centered sub-rect of a
   full-real-size `CompositeTarget`, clears the rest to an opaque edge color, and presents the
   composite instead of the raw shrunk texture. This is the step that turns "smaller game image" into
   "smaller game image inside reserved-edge borders at the real window size." See
   [minecraft/blaze3d-custom-blit-pipeline-26.md](../minecraft/blaze3d-custom-blit-pipeline-26.md) for
   the GPU-API mechanics of both blits.
3. **The Compose overlay** (`ComposeOverlay.renderInto` → `ComposeSurface.renderFrame` →
   `BlitUvPipeline.blit(..., blend = true)`) — runs *after* the composite is presented into the frame
   sequence, rendering the full-window `RedstoneDock` scene and alpha-blending it over the composite
   with the premultiplied-alpha `PIPELINE_BLEND`. Only the pixels the dock actually paints (edge
   regions with visible panels) become opaque; the transparent CENTER lets the composited world show
   through untouched. See [ui/compose-blended-overlay.md](../ui/compose-blended-overlay.md).

Layer 1 shrinks *what is rendered*. Layer 2 places that render inside a bordered composite at real
window size. Layer 3 paints UI on top of the whole real-size composite. Each layer operates on the
output of the one before it, at real framebuffer pixels throughout — there is no separate "UI-space"
coordinate system to convert between.

## The frame-ordering guarantee: insets are plain state, never gated on a compose pass

`DockState`'s region sizes and visibility (`leftWidth`, `rightVisible`, etc.) are plain Compose
snapshot-state fields, mutated eagerly by input handlers (`DockKeybinds`, splitter drag) — **not**
as a side effect of a `RedstoneDock` recomposition. `ViewportState`/`WindowMixin` read those fields
directly to compute `DockInsets` and the shrink rect. This ordering is deliberate: the shrink must be
correct on the very first frame after a panel is shown or resized, even if the Compose scene hasn't
rendered yet that frame (e.g. `ComposeSurface.disabled`, or the scene is mid-recreate on a resize).
If the inset computation instead depended on reading back something Compose painted, a panel toggle
would show a one-frame flash of the old (unshrunk) game viewport underneath the new panel — or worse,
permanently desync if a compose pass is ever skipped. Because `DockState` is authoritative arithmetic
that both `RedstoneDock`'s layout and the shrink read independently, a splitter drag moves the panel
edge and the world content rect in lockstep with no cross-system round-trip.

## Guard and fallback

Every Compose entry point (`ComposeSurface.ensureNativeLoaded`, `renderFrame`, the input dispatchers)
is wrapped so any `Throwable` — a native-load failure, a GL state mismatch, a Skia error — sets
`ComposeSurface.disabled = true` and logs once. `ComposeOverlay` and `DockInputRouter` no-op when
disabled. `WindowMixin`'s shrink and `MinecraftPresentMixin`'s composite are independent of the dock
being enabled: a disabled Compose surface still renders `MinecraftPresentMixin`'s composite (or, when
the viewport-shrink keybind itself is off, byte-for-byte vanilla presentation) — only the panel UI on
top is missing. Nothing about the dock being unavailable can corrupt the base game render.

## Where the docking insets are consumed

`ViewportState.contentRect` (read by `WindowMixin` every `redstonespecs$updateScaledFramebuffer` call)
folds `DockInsets(left, right, bottom, top)` — computed from `DockState.leftWidth`/`rightWidth`/
`bottomHeight` gated on `isVisible` — into the shrink rect the same way it already folded the older,
independent viewport-shrink-keybind reservation. A dock resize or visibility toggle calls
`redstonespecs$updateScaledFramebuffer(true)` explicitly (see `DockKeybinds`'s Shift+1 handler) so the
world inset updates immediately rather than waiting for the next incidental window resize.
