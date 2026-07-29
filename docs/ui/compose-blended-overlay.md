---
title: Full-window transparent Compose overlay via premultiplied-alpha blit
tags: [compose, skia, blaze3d, blend, rendering, overlay, gpu-api]
summary: BlitUvPipeline gained a premultiplied-alpha blend pipeline so the Compose surface can cover the whole window while the live world shows through everywhere it's transparent.
---

# Full-window transparent Compose overlay via premultiplied-alpha blit

`ComposeOverlay.renderInto` (`src/client/kotlin/.../ui/compose/ComposeOverlay.kt`) used to render
`ComposeSurface` at the width of the viewport's reserved-left strip and blit it as an **opaque**
rectangle. It now renders `ComposeSurface` at the **full real window size** and alpha-blends it over
the already-composited world with `BlitUvPipeline.blit(..., blend = true)`. Only the pixels Compose
actually paints (the dock's edge regions — panel bodies, no tab strip) end up opaque; everywhere else
the canvas is cleared to `0x00000000` (`ComposeSurface.kt`, `s.canvas.clear(...)`) and `GarnetDock`'s
root `Box` (hosted by `ComposeSceneHost`) has **no** background — its transparent CENTER leaves those
pixels untouched — so the live game world composited underneath shows through.

## The blend pipeline: MC 26.2's actual API (not what you'd guess from older MC)

`BlitUvPipeline.PIPELINE_BLEND` needed premultiplied-alpha over-compositing
(`dst = src + dst*(1-srcA)`) because Skia surfaces are premultiplied. The naive expectation —
`RenderPipeline.Builder.withBlend(new BlendFunction(GlStateManager.SourceFactor.ONE, ...))` — **does
not exist** in MC 26.2's decompiled `com.mojang.blaze3d.pipeline.RenderPipeline`:

- There is no `withBlend(...)` builder method at all. Blending is set via
  `RenderPipeline.Builder.withColorTargetState(ColorTargetState)`.
- `SourceFactor`/`DestFactor` are **top-level** enums in `com.mojang.blaze3d.platform`, not nested
  inside `GlStateManager`.
- `com.mojang.blaze3d.pipeline.BlendFunction` is a record `(sourceColor, destColor, sourceAlpha,
  destAlpha)` with a 2-arg constructor that applies the same factors to color and alpha. It already
  ships a constant for exactly this case: `BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA =
  BlendFunction(SourceFactor.ONE, DestFactor.ONE_MINUS_SRC_ALPHA, SourceFactor.ONE,
  DestFactor.ONE_MINUS_SRC_ALPHA)`.
- `com.mojang.blaze3d.pipeline.ColorTargetState` wraps an `Optional<BlendFunction>` + write mask;
  `ColorTargetState(BlendFunction)` defaults the write mask to `WRITE_ALL`.

So the pipeline is built with:

```kotlin
.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
```

Verified by extracting `com/mojang/blaze3d/pipeline/{RenderPipeline,BlendFunction,ColorTargetState}.java`
from the `minecraft-clientOnly` sources jar in the loom cache (see `docs/minecraft/INDEX.md` /
memory `reference_mc_sources` for the extraction path) — `blaze3d` ships bundled inside that jar, it
is not a separate Maven artifact.

`BlitUvPipeline.blit(...)` gained a trailing `blend: Boolean = false` parameter; `blend=true` selects
`PIPELINE_BLEND` instead of the original opaque `PIPELINE` in the `pass.setPipeline(...)` call. All
other call sites (the world-into-composite blit in `MinecraftPresentMixin`) keep the default
`blend=false` and are unaffected.

## Why this matters

Getting the factor order wrong (e.g. `SRC_ALPHA` instead of `ONE` for the source factor) would
double-apply alpha to Skia's already-premultiplied output, darkening/dimming the Compose panel instead
of compositing it cleanly. Using the ordinary (non-premultiplied) `BlendFunction.TRANSLUCENT` constant
instead would produce the same visible bug for this surface.
