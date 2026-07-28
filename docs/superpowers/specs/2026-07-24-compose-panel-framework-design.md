---
title: Compose Panel Framework — design
tags: [ui, compose, dock, panels, viewport, skia, ide, hard-cut]
summary: Full-window ComposeScene dock (L/R/B/Center + tabs + drag splitters) over the proven viewport composite; DockState-driven insets shrink the live world; ProjectExplorer panel; hard-cut of the legacy MC-GUI screens.
---

# Compose Panel Framework — design

## 0. Status of prior art

This spec supersedes §6–7 of `2026-07-23-redstone-project-ide-panel-framework-design.md`
(the custom `AbstractWidget`/`Element` framework + the Flashback-style `MixinWindow`
viewport mechanism). Those are replaced by two committed, **proven** foundations:

- **Viewport spike (committed).** The live world is composited into a centered sub-rect
  while UI fills reserved edges: `viewport/ViewportState.kt`, `viewport/BlitUvPipeline.kt`,
  `viewport/CompositeTarget.kt`, `mixin/client/WindowMixin.java` (shrinks the reported
  framebuffer), `mixin/client/MinecraftPresentMixin.java` (composites shrunk world + blits
  UI). OFF by default (keybind `V`).
- **Compose GO (committed).** A real Compose Multiplatform `ComposeScene` renders into a
  Blaze3D `TextureTarget` FBO, coexists with Blaze3D across frames, and reacts to pointer
  input. See `docs/ui/compose-in-mc-feasibility.md` for the mechanism, the GL-state +
  pixel-store contract, and the build wiring. `ui/compose/ComposeSurface.kt`,
  `ComposeScenePanel.kt`, `ComposeOverlay.kt`. OFF by default (keybind `C`).

The decisions log (D1–D12) and non-goals of the prior spec still hold **except** where
this spec restates them. The shelved `2026-07-23-panel-render-foundation.md` plan and the
ImGui fallback are moot.

This spec delivers the Compose-based panel **framework** (docking) plus **one** real panel
— the **Project Explorer** — and hard-cuts the legacy MC-GUI screens.

## 1. Architecture — one full-window ComposeScene, transparent center

The spike's single left-strip `ComposeScenePanel` generalizes into a **full-window
`GarnetDock`** composable. Compose composes the whole `realW × realH` scene each frame:
panels draw in the reserved edge regions; the center content-rect is `Color.Transparent`.

**Decision (D-A, brainstormed):** one full-window scene, not one-scene-per-edge. Pointer
coordinates equal window coordinates (no per-region remap), there is a single Compose focus
tree, and overlays / tooltips / an occupied Center panel can cross the world region.

**Present pipeline change** (`MinecraftPresentMixin#garnet$compositePresent`):

1. Prime the composite with the opaque edge fill (unchanged).
2. Blit the shrunk world into the center content sub-rect, **opaque** (unchanged).
3. **Alpha-blend** the full-window Compose texture on top. Its transparent center lets the
   world show through; an occupied Center panel draws opaque there and occludes the world.

This is the **only** modification to the proven viewport pipeline. It requires a
**blend-enabled variant** of `BlitUvPipeline` (premultiplied-alpha `ONE`,
`ONE_MINUS_SRC_ALPHA` — Skia surfaces are premultiplied). The world blit stays opaque; the
`flipV=true` bottom-up convention is unchanged.

The Compose surface **grows from the left strip to the full real window**
(`ComposeSurface.renderFrame(realW, realH)`), replacing the current
`renderFrame(stripWidth, realH)` + left-strip blit in `ComposeOverlay`.

## 2. Insets are authoritative plain state — Compose never gates the shrink

**Decision (D-B):** the reserved-region geometry is authoritative **plain arithmetic**,
updated eagerly on layout events, never computed as a side effect of rendering.

A client-side `DockState` object is the single source of truth: per-region visibility,
sizes (splitter positions), active-tab index, and focus — held as Compose **snapshot
state** (`mutableStateOf`) so the composition recomposes on change. It is mutated by input
handlers (splitter drag, Alt/Shift+N) and read by **both** consumers:

- **`GarnetDock`** composition — recomposes to lay out panels in the current region rects.
- **`ViewportState`** — the hard-coded `RESERVED_LEFT` / `RESERVED_BOTTOM` constants are
  **replaced** by a `DockInsets` value derived from `DockState`
  (`{left, right, bottom, top}`). `contentRect(realW, realH)` becomes pure arithmetic over
  the insets, clamped to `MIN_CONTENT_SIZE`.

`WindowMixin` computes the frame's framebuffer-shrink early (at window-size query time) by
reading the current insets — a plain read of `mutableStateOf.value`, no composition needed.
Because the insets are current before present, and only change during input handling, the
world resizes correctly. A 1-frame lag while dragging a splitter is imperceptible.

**Frame-ordering guarantee:** the shrink depends only on `DockState` values (plain), never
on a Compose render pass. Rendering `GarnetDock` happens at present time and only *draws*
into the already-known region rects.

## 3. Components & package layout

```
src/client/kotlin/com/breadmoirai/garnet/client/
  ui/compose/
    ComposeSurface.kt          (generalized: hosts the full-window GarnetDock scene)
    ComposeOverlay.kt          (generalized: full-window alpha-blend into the composite)
    dock/
      DockRegion.kt            enum LEFT / RIGHT / BOTTOM / CENTER
      DockState.kt             snapshot-state source of truth (visibility, sizes, tabs, focus)
      DockInsets.kt            pure DockState -> {left,right,bottom,top} + region-rect math
      Panel.kt                 id + title + @Composable body (NOT named Component)
      GarnetDock.kt          @Composable root: region layout, tab strips, drag splitters
    input/
      DockInputRouter.kt       focus/capture state; forwards events to the scene
  ide/
    ProjectExplorerPanel.kt    @Composable tree over ProjectTreeSnapshotS2C
    ProjectTreeState.kt        mutableStateOf client state fed by ProjectClientNetworking
src/client/java/.../mixin/client/
    MouseHandlerMixin.java     route pointer/scroll into the scene when captured
    KeyboardHandlerMixin.java  route key/char into the scene when captured
```

**Naming constraint:** no base UI type is named `Component` (collides with MC's text
`Component`). Compose owns the widget vocabulary; our types are `Dock`, `DockState`,
`DockRegion`, `Panel`.

### 3.1 Dock model

- `DockRegion` — `LEFT`, `RIGHT`, `BOTTOM`, `CENTER`.
- Each region holds an ordered list of `Panel`s rendered as a **tab strip**; the active
  tab's body renders. A region with 0 visible panels reserves no space (insets exclude it).
- **Splitters** — draggable dividers between an edge region and the center. Drag writes the
  region size into `DockState` (min/max clamped; cannot invert). CENTER has no splitter of
  its own — it is whatever the edges leave.
- The world fills CENTER unless a CENTER panel is visible (then it occludes — §1).

### 3.2 Input & focus model (D8/D11)

- Default input target is the **game** (cursor grabbed, mouse-look live) even while panels
  are visible-but-unfocused.
- **Alt+1 focuses** the Explorer: sets a global `inputCaptured` flag, releases the cursor
  (`MouseHandler.releaseMouse()`), and requests Compose focus on that panel. Re-grab uses
  `setIgnoreFirstMove()` before `grabMouse()` to avoid the camera jump (mirrors
  `CursorFocusToggle`).
- **Shift+1 toggles** the Explorer's visibility (reclaims/frees its region insets). If the
  focused panel is hidden, focus is dropped and the cursor re-grabbed.
- While `inputCaptured`, `MouseHandlerMixin` / `KeyboardHandlerMixin` forward GLFW pointer /
  scroll / key / char events to `ComposeSurface` (coords == window coords) and **cancel**
  the vanilla handler; otherwise vanilla runs untouched.
- Keybinds are registered generically (index → panel via `DockState`); only the Explorer
  (index 1) is bound in this plan. Compose's own internal focus handles intra-scene
  focus/tab traversal.

### 3.3 Project Explorer & live data

- `ProjectExplorerPanel` renders a tree over the latest `ProjectTreeSnapshotS2C`
  (`leaves: List<ProjectLeafEntry>`, `intermediates: List<String>`, `currentSubpath`),
  held in `ProjectTreeState` (`mutableStateOf`). Expand/collapse, selection, keyboard nav.
- `ProjectClientNetworking` handlers stop poking a `Screen` and instead:
  - `ProjectTreeSnapshotS2C` → replace `ProjectTreeState.snapshot`.
  - `ProjectFolderLoadedS2C` → merge loaded ids / surface parse+layout errors into a status
    line.
  - `ProjectSaveReportS2C` / `ProjectErrorS2C` → status line.
- Explorer actions drive **existing** server receivers (`ProjectNetworkRegistry`, all
  present): on focus/refresh → `ListProjectTreeC2S`; leaf activate → `LoadProjectFolderC2S`;
  unload → `UnloadProjectFolderC2S`; (`SaveNowC2S` / `NewProjectSpecC2S` wired if trivial,
  else deferred). Server root resolution already falls back pin → `ProjectWorld` → config
  (`ProjectNetworkRegistry.rootFor`).

## 4. Build — Compose runtime scoping investigation (first task)

The known follow-up: the Compose compiler plugin is applied project-wide and its
`VersionChecker` fails **any** compilation lacking the Compose runtime on its classpath, so
`runtime-desktop` currently sits on the base `implementation` (server-jar bloat), not
`clientImplementation`.

**Task:** empirically attempt client-only scoping — move `runtime-desktop` to
`clientImplementation` and try to disable the compose compiler subplugin for `main` / `test`
/ `gametest` compilations (per-`KotlinCompile`-task, or a conditional plugin application).
Build all 5 source sets and observe the exact failure.

**Expected outcome:** single-module Stonecutter has no clean per-source-set mechanism to
scope a Kotlin compiler plugin (the genuine fix is a separate Gradle UI submodule — out of
scope here). If confirmed, **record the finding and rationale** in
`docs/build/` and keep the runtime on base `implementation`, noting the runtime jar is
modest and inert on the server. Timeboxed — do not rabbit-hole into a submodule split.

## 5. Project entry rewrite (keep the main-menu button)

`TitleScreenMixin` keeps its "Redstone Projects…" button (via `GarnetIconButton`,
**retained** — not orphaned). The click handler is **retargeted** from
`setScreen(ProjectRootListScreen(...))` to **booting a void workspace world directly**:
reuse `ProjectIntegratedBoot`'s open-or-create flat-void machinery, root-agnostic (root
resolves later via the existing config fallback). In-world, the Explorer loads/unloads
projects as needed.

`ProjectIntegratedBoot` is boot machinery, not a screen — it stays (adapted to a
root-agnostic `bootWorkspace()` entry). `ProjectRootsConfig` / `ProjectRootListScreen` roots
UI is cut; verify `ProjectRootsConfig`'s remaining users before deciding delete-vs-keep.

## 6. Hard-cut cascade (delete)

- **Screens:** `client/project/ProjectScreen`, `client/project/ProjectRootListScreen`,
  `client/screen/RecorderScreen`, `client/screen/RunnerScreen`.
- **Orphan widgets:** `client/widget/TimelineSliderWidget` (true orphan);
  `client/screen/IntEditBox` (**and** its `src/test/.../IntEditBoxLogicTest`).
  `client/screen/GarnetIconButton` is **kept** (title button).
- **`ClientNetworkHandler`:** `OpenRecorderScreenS2C` / `OpenRunnerScreenS2C` receivers
  become logged no-ops; `RunnerScreen.active` references removed. (Recorder/runner UIs
  return as panels in sub-projects A/B.)
- **clientTest specs** referencing dead screens — `ProjectEntryFlowSpec`,
  `RecorderScreenSpec`, and any `RunnerScreen` refs in `ClientNetworkSpec` — deleted or
  rewritten, and **de-registered from `ClientTestSentinel`** (autoscan is off).
- **Docs:** rewrite `docs/ui/*` for the Compose dock — absorb `dropdown-host-popup-stratum`,
  carry forward the ARGB and extract-render pitfalls where still relevant to Compose; drop
  `recording.md` / `running.md` use-cases; add an `architecture/` note on the
  shrink-viewport + full-window-Compose model.

## 7. Error handling

- Splitters clamp to min/max and cannot invert; region rects never go negative/NaN. A
  collapsed region skips its body but keeps its tab strip only while it has ≥1 visible panel.
- `ViewportState.contentRect` clamps to `MIN_CONTENT_SIZE`; never passes width/height ≤ 0.
- Focus manager guarantees the cursor is re-grabbed (`setIgnoreFirstMove`) if the focused
  panel is hidden.
- Every Skia/Compose entry point stays guarded (`ComposeSurface.disabled`) — any failure
  falls back to the plain solid-edge composite and never crashes present or startup. The
  alpha-blend blit is inside the same guard.

## 8. Testing

- **clientTest (`ClientSpec`, screenshots via `ViewportState.compositeCaptureRequest`):**
  dock renders in the edges with the world in the center; `Alt+1` focuses and a routed
  pointer click hits a tree node; drag a splitter → assert `ViewportState.contentRect`
  shrank by the expected amount; `Shift+1` toggles and reclaims insets; toggle-off restores
  vanilla present. New spec(s) registered in `ClientTestSentinel`.
- **Inset arithmetic:** verified by a fast clientTest asserting `contentRect` values after
  direct `DockState` mutations (client-source-set visibility rules out `src/test` JVM
  tests; the geometry is small and low-risk).
- Existing viewport/compose specs (`ViewportCompositeSpec`, `ViewportPickingSpec`,
  `CursorFocusToggleSpec`, `ComposeOverlaySpec`) must stay green — the Compose spec may be
  retired/renamed once the dock subsumes it.

## 9. Non-goals (this plan)

- Invalidation-gated rendering — the scene still re-renders + re-uploads every frame; a full
  full-window texture is heavier than the strip. Efficiency (`scene.hasInvalidations()`) is
  a noted follow-up.
- Cross-platform skiko (still Windows-x64-pinned).
- Cross-session layout persistence (splitter positions reset on open).
- Dragging tabs between regions; floating/dockable panels.
- Recorder / runner / debugger / timeline panels (sub-projects A/B); spec editor.
- A genuine per-source-set Compose-runtime scope split (needs a UI submodule — §4).

## 10. Task shape (for writing-plans)

0. Compose runtime scoping investigation → `docs/build/` finding.
1. `BlitUvPipeline` alpha-blend variant + transparent-center present composite.
2. `DockState` + `DockInsets`; `ViewportState` reads insets (replace constants).
3. `GarnetDock` composable: regions + tab strips + drag splitters; `ComposeSurface` hosts
   it full-window.
4. Input routing (mouse/keyboard mixins + `DockInputRouter`, Alt/Shift+N focus/toggle).
5. `ProjectExplorerPanel` + `ProjectTreeState`, wired to project networking.
6. Project-entry rewrite (void workspace boot; retarget the title button).
7. Hard-cut cascade + `ClientTestSentinel` cleanup.
8. Docs rewrite.

Each task ends green on the 5-source-set build. UI tasks (1, 3, 4, 5) get a screenshot
checkpoint the controller verifies.
