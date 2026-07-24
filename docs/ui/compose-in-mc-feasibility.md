---
title: Compose-in-Minecraft feasibility — Skia over Blaze3D GL
tags: [ui, compose, skia, skiko, blaze3d, opengl, feasibility, spike]
summary: GO verdict — Skiko/Skia renders into a Blaze3D TextureTarget FBO and coexists with MC 26.2's GL renderer across frames; the mechanism, the GL-state contract, and what remains.
---

# Compose-in-Minecraft feasibility — Skia over Blaze3D GL

**Verdict: GO for the crux.** On MC 26.2, Skiko's desktop-GL Skia can render into a Minecraft
Blaze3D render target and coexist with Blaze3D's own rendering, frame after frame, with no
corruption — proven by a running client. This retires the single riskiest, least-charted part of
the "Compose as the IDE UI" direction. The remaining step (rendering an actual `ComposeScene`
through the proven Skia surface, plus the Compose runtime/compiler) is comparatively low-risk.

## What was proven (evidence)

A `clientTest` (`ComposeOverlaySpec`) toggles the viewport shrink on, enables the overlay, renders
several frames, and captures the composite. Three screenshots (under
`versions/26.1/run/screenshots/`, git-ignored run artifacts):

- `compose_in_mc_step2.png` — a Skia-drawn panel (navy fill + blue accent bar + slate stripes) in
  the reserved-left strip, alongside the live world in the centered viewport. Skia pixels reach the
  screen; MC renders correctly the same frame.
- `compose_in_mc_stable.png` — pixel-identical after 20 more frames: **no GL-state drift, no
  flicker, no crash.**
- `compose_off_restored.png` — after toggling off, a clean full-screen vanilla world: **no
  regression.**

## The mechanism

- **Skiko native (`skiko-awt-runtime-windows-x64:0.150.1`)** — the desktop-GL Skia build. Loaded
  eagerly via `org.jetbrains.skiko.Library.load()` and guarded; a load failure under Fabric's Knot
  classloader is a clean logged disable, not a crash. (Windows-x64 pinned to the dev/runtime host;
  switch to `skiko-awt` + per-OS runtimes for cross-platform.)
- **`DirectContext.makeGL()`** over Minecraft's *own live GL context* — no separate context.
- **Render into a Blaze3D `TextureTarget`, not a raw GL texture.** Its color `GlTexture` yields both
  a raw GL FBO id (via `GlTexture.getFbo(DirectStateAccess, null)`) for Skia's
  `BackendRenderTarget.makeGL(...)`, and a `GpuTextureView` for our existing `BlitUvPipeline`. One
  texture, two views — so the Compose panel blits into the viewport composite with the same pipeline
  the world uses, no new blit machinery.
- **`SurfaceOrigin.BOTTOM_LEFT`** matches MC render-target textures (bottom-up), so
  `BlitUvPipeline`'s `flipV=true` path presents it upright.

## The GL-state coexistence contract (the load-bearing part)

Skia issues raw GL calls that bypass Blaze3D's `GlStateManager` cache. If Skia leaves the context in
a state `GlStateManager` doesn't *expect*, Blaze3D's cache-skipping rebinds silently no-op and
corrupt later frames. `ComposeSurface.renderFrame` therefore, around each Skia draw:

1. **snapshots** the real GL state (program, VAO, active-texture, 2D binding, array buffer, draw/read
   FBO, and the blend/depth/scissor/cull enables) — which currently matches Blaze3D's belief, since
   Blaze3D just ran;
2. lets Skia draw, then `surface.flush()` + `directContext.flush()`;
3. calls **`directContext.resetAll()`** so Skia re-reads GL state next frame (Blaze3D will have
   changed it out from under Skia);
4. **restores** the exact snapshot so `GlStateManager`'s cache stays consistent with reality.

This snapshot/reset/restore is what makes the two renderers share one context safely.

## Build wiring

- `clientImplementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")`.
- A **`classTweaker`** (`src/main/resources/redstonespecs.classtweaker`, `classTweaker v1 official`,
  referenced from `fabric.mod.json` + `loom.accessWidenerPath`) opens the package-private
  `com.mojang.blaze3d.opengl` GL-backend classes (`GlDevice.directStateAccess`, `GlTexture.getFbo`,
  `GpuDevice.backend`, `DirectStateAccess`) needed to fetch the raw GL FBO id. Reflection-by-name
  fails in dev (intermediary names), so we widen and let Loom remap.
- Kotlin 2.3.20 / JDK 25 / LWJGL 3.4.1. The `@Composable` compiler plugin
  (`org.jetbrains.kotlin.plugin.compose:2.3.20`) + Compose runtime are intentionally **not** added
  here — the spike proved the Skia-over-GL risk without dragging in the Compose compiler first.

## What remains (post-GO)

- **Step 3 — real `ComposeScene`:** add the Compose runtime + `plugin.compose:2.3.20`; render a
  `ComposeScene` (its content composables) to `ComposeSurface`'s Skia `Canvas` each frame in place of
  the plain-geometry `drawPanel`. Low-risk: `ComposeScene` draws to a Skia canvas, which is proven.
  Watch for the `ComposeScene`↔`GlobalSnapshotManager` race the `VexorMC/compose` fork patched.
- **Task 2 — input:** feed GLFW pointer/key events into `ComposeScene.sendPointerEvent` (active-only).
- **Then:** the Compose-based panel-framework plan (docking via Compose layout, `ProjectExplorerPanel`
  as a composable over `ProjectTreeSnapshotS2C`, `mutableStateOf` for live debugger/timeline data).

## Guarding invariant

Every Skia/Skiko entry point is guarded: any `Throwable` flips `ComposeSurface.disabled` (with a
reason) and the client falls back to the plain solid-edge composite — it must never crash startup or
normal play. `ComposeOverlay` is OFF by default (keybind `C`, only while the viewport is active).
