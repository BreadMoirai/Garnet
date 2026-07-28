---
title: Compose-in-Minecraft feasibility — real ComposeScene over Blaze3D GL
tags: [ui, compose, skia, skiko, blaze3d, opengl, feasibility, spike]
summary: GO — a real Jetpack/Multiplatform ComposeScene renders into a Blaze3D TextureTarget FBO, coexists with MC's GL renderer across frames, and reacts to pointer input; the mechanism, the GL-state contract, and the build wiring.
---

# Compose-in-Minecraft feasibility — real ComposeScene over Blaze3D GL

**Verdict: GO — real Compose renders in-MC.** On MC 26.1 (LWJGL 3.4.1 / JDK 25), an actual Compose
Multiplatform `ComposeScene` — a `Box` with a background, a `Text`, and an interactive button — composes
to a Skia canvas, is uploaded into a Minecraft Blaze3D render target, coexists with Blaze3D's own
rendering frame after frame with no corruption, and **reacts to pointer input** (hover/press/click
routed through Compose's own interaction plumbing). This retires the whole risk column for the
"Compose as the IDE UI" direction: not just the Skia-over-GL crux (Task 1), but the Compose compiler
+ runtime integration under Loom/Stonecutter and a live scene driven from the render thread.

## What was proven (evidence)

A `clientTest` (`ComposeOverlaySpec`) toggles the viewport shrink on, enables the overlay, renders
several frames, drives pointer events into the scene, and captures composites (under
`versions/26.2/run/screenshots/`, git-ignored run artifacts):

- `compose_in_mc_scene.png` — the real Compose panel (a small opaque panel with white `Text`
  "Compose in MC" and a blue button "Click me • clicks=0") blitted full-window with premultiplied-alpha
  blending, so the live world composited underneath shows through everywhere Compose left transparent
  (see `docs/ui/compose-blended-overlay.md`). Compose pixels reach the screen; MC renders correctly the
  same frame.
- `compose_in_mc_stable.png` — after 20 more frames: **no GL-state drift, no flicker, no crash.**
- `compose_input_hover.png` / `compose_input_pressed.png` — after a `sendPointerEvent` Move then
  Press at the button centre, the button lightens and the label flips to "Pressed!". The test asserts
  Compose's own `clickCount` incremented (`0 → 1`) on release — **input reached Compose, not a manual
  toggle.**
- `compose_off_restored.png` — after toggling off, a clean full-screen vanilla world: **no regression.**

## The mechanism

- **Compose renders on a raster surface, not GL.** We use `androidx.compose.ui.ImageComposeScene`
  (Compose MP's self-contained scene): it composes/measures/draws into its **own**
  `Surface.makeRasterN32Premul` (pure CPU — no GL) and hands back a snapshot `Image` from
  `render(nanoTime)`. Only the final one-image upload touches Minecraft's GL context. This keeps the
  whole Compose tree off Blaze3D's context and sidesteps a second GPU surface entirely.
- **Why `ImageComposeScene` and not a raw scene factory.** In Compose 1.12 the low-level
  `CanvasLayersComposeScene(...)` factory takes a `FrameRecomposer` + `PlatformContext` the caller must
  build and drive. `ImageComposeScene` wraps all of that — recomposer, `BroadcastFrameClock`, and
  crucially its own `GlobalSnapshotManager` registration — so the `ComposeScene`↔`GlobalSnapshotManager`
  race the `VexorMC/compose` fork had to patch in a hand-rolled loop **does not arise**: we keep all
  interaction on the render thread and let `render()` apply pending snapshot changes synchronously. No
  race workaround was needed.
- **Upload into a Blaze3D `TextureTarget`.** Its color `GlTexture` yields both a raw GL FBO id (via
  `GlDevice.frameBufferCache().getFbo(dsa, listOf(glTexture), null)` — MC 26.2 removed
  `GlTexture.getFbo`; `GlTexture` implements `FrameBufferAttachment`, so it is passed directly as the
  sole color attachment) for a Skia `Surface`, and a `GpuTextureView` for our `BlitUvPipeline`. One texture, two views — the Compose panel blits into the viewport composite with
  the same pipeline the world uses. Each frame: `ImageComposeScene.render()` → draw that `Image` onto
  the FBO-backed Skia canvas → `flush` → blit.
- **`SurfaceOrigin.BOTTOM_LEFT`** matches MC render-target textures (bottom-up), so `BlitUvPipeline`'s
  `flipV=true` path presents it upright.
- **Real input.** The button is built from foundation `clickable` + `hoverable` + a
  `MutableInteractionSource`, read back via `collectIsHoveredAsState`/`collectIsPressedAsState` —
  nothing toggles the visual state by hand. GLFW-derived pointer coordinates feed
  `ComposeScene.sendPointerEvent(...)`; Compose recomposes and the button changes. Panel-local coords
  equal strip-local screen coords (Compose draws top-down; the BOTTOM_LEFT + flipV blit presents it
  upright, so hit-testing needs no Y flip).

## The GL-state coexistence contract (the load-bearing part)

Skia issues raw GL calls that bypass Blaze3D's `GlStateManager` cache. If Skia leaves the context in a
state `GlStateManager` doesn't *expect*, Blaze3D's cache-skipping rebinds silently no-op and corrupt
later frames. `ComposeSurface.renderFrame` therefore, around each Skia draw:

1. **snapshots** the real GL state (program, VAO, active-texture, 2D binding, array buffer, draw/read
   FBO, blend/depth/scissor/cull enables) — which matches Blaze3D's belief, since Blaze3D just ran;
2. lets Skia draw + upload, then `surface.flush()` + `directContext.flush()`;
3. calls **`directContext.resetAll()`** so Skia re-reads GL state next frame;
4. **restores** the exact snapshot so `GlStateManager`'s cache stays consistent with reality.

### Pixel-store (unpack) state — the CPU→GPU upload gotcha

The raster-image path added a second GL contract the plain-Skia panel never exercised: **`glTexSubImage2D`
reads the pixel buffer using the current `GL_UNPACK_*` state, and Blaze3D leaves it dirty.** In practice
MC leaves `GL_UNPACK_SKIP_PIXELS = 120` set from its own texture writes; inherited by Skia's upload it
rolls every row → the whole Compose image wraps horizontally by ~120 px. The old plain-Skia panel
uploaded nothing (it drew vector geometry straight onto the GPU surface) so it never hit this — and
because that panel was near-symmetric (full-width fill + bars) even a shift would have been invisible.
The fix: reset `GL_UNPACK_ALIGNMENT/ROW_LENGTH/SKIP_PIXELS/SKIP_ROWS` to their GL defaults right before
the upload and restore MC's values after (`ComposeSurface.saveAndResetUnpack`/`restoreUnpack`). **Any
raw CPU→GPU texture upload sharing Blaze3D's context must neutralize the unpack pixel-store state.**

## Build wiring

- `clientImplementation("org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.150.1")` — desktop-GL Skia
  native (Windows-x64 pinned to the dev/runtime host; switch to `skiko-awt` + per-OS runtimes for
  cross-platform).
- **Compose compiler plugin:** `kotlin("plugin.compose") version "2.3.20"` (versioned in lockstep with
  Kotlin 2.3.20) — the Kotlin compiler plugin only, **not** the `org.jetbrains.compose` Gradle plugin,
  to avoid fighting Loom/Stonecutter's source-set + run wiring.
- **Compose runtime:** `org.jetbrains.compose.{runtime:runtime-desktop, ui:ui-desktop,
  foundation:foundation-desktop}:1.12.0-beta02`. Pinned to 1.12.0-beta02 because its transitive
  `skiko-awt` is **0.150.1** — an exact match for the native above (a mismatch risks a skiko
  version-guard failure). The explicit `-desktop` coordinates are required: without the Compose Gradle
  plugin there are no KMP target attributes to resolve the aggregator coords. `material3` is omitted (its
  artifact version diverged from the Compose BOM — only 1.12.0-alpha03 exists, which would drag
  ui/foundation to alpha03 and a different skiko); the button uses foundation primitives instead.
- **Repos:** `google()` (androidx transitive KMP artifacts) + the JetBrains
  `maven.pkg.jetbrains.space/public/p/compose/dev` fallback.
- **The compiler-plugin quirk that bites first:** the Compose compiler plugin is applied project-wide,
  and its `VersionChecker` fails **any** compilation lacking the Compose runtime on its classpath —
  including `main` and `test`, which have no `@Composable`. So the *runtime* must sit on the **base**
  `implementation` (which every source set extends), not `clientImplementation`; only `ui`/`foundation`
  (actually used by the composables) stay client-scoped. A production setup would scope the plugin to the
  client compilation instead.
- A **`classTweaker`** (`src/main/resources/redstonespecs.classtweaker`) opens the package-private
  `com.mojang.blaze3d.opengl` GL-backend classes needed to fetch the raw GL FBO id (see Task 1).

## Guarding invariant

Every Skia/Skiko/Compose entry point is guarded: any `Throwable` — native load failure under Fabric's
Knot classloader, a Skia error, a scene/pointer failure — flips `ComposeSurface.disabled` (with a reason)
and the client falls back to the plain solid-edge composite; it must never crash startup or normal play.
`ComposeOverlay` is OFF by default (keybind `C`, only while the viewport is active).

## What remains (post-GO)

- **Panel framework:** docking via Compose layout, `ProjectExplorerPanel` as a composable over
  `ProjectTreeSnapshotS2C`, `mutableStateOf` for live debugger/timeline data.
- **Real input routing:** wire the actual GLFW cursor/mouse hooks (not just test-driven
  `sendPointerEvent`) into the scene, gated to the reserved strip, with focus handling vs MC's own input.
- **Efficiency:** the spike re-renders + re-uploads every frame; a production path would render only on
  Compose invalidation (`scene.hasInvalidations()`) and reuse the snapshot otherwise.
- **Cross-platform:** move off the Windows-x64-pinned skiko native.
