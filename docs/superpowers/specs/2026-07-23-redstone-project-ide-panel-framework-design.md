---
title: Redstone Project IDE — Panel Framework (sub-project 0) — design
tags: [ui, framework, panels, viewport, mixin, ide, rename]
summary: Clean-slate, retained-mode, panel-based UI framework with a live shrunk game viewport; first sub-project of the "contraption IDE" scope expansion.
---

# Redstone Project IDE — Panel Framework (sub-project 0)

## 1. Context & vision

We are expanding garnet from a redstone **testing** tool (record → emit → run →
verify) into an **integrated development environment** for building and testing Minecraft
contraptions. That vision decomposes into independent sub-projects, each with its own
spec → plan → build cycle:

- **Sub-project 0 (this spec): UI framework.** A panel-based UI substrate that every IDE
  surface hangs on. Nothing else can be built well without it.
- **Sub-project A — Workspace & Suite.** Project model, contraption+spec lists,
  batch-run, pass/fail dashboard.
- **Sub-project B — Debugger.** Per-tick redstone signal visibility, step/pause the tick
  loop, in-world probes. The first *real* panel to land after this framework.
- **Sub-project C — Authoring & Versioning.** In-world build/edit tooling and
  snapshot/diff of a contraption across iterations.

This spec delivers the framework plus **one** fully working panel — the **Project
Explorer** — as proof. The spec editor is explicitly out of scope (the spec/record
feature is deprioritized relative to project management and the debugger).

## 2. Goals

- A from-scratch, retained-mode panel framework that owns layout, scroll, scissor, input
  focus, overlays, and live updates — replacing today's stateless `rebuildWidgets()`
  screens.
- Fixed dock positions **Left / Bottom / Right / Center**, each a **tab group** holding
  ≥1 `PanelView`.
- Docked panels **reserve screen space and shrink the live game viewport into the
  center**; the world stays fully visible and interactive unless a **Center** panel
  occludes it.
- Keybind-driven focus: the game is the default input target (cursor grabbed, mouse-look
  live); **Alt+N focuses** a panel (releases the cursor), un-focus re-grabs.
- One real panel — **Project Explorer** (Alt+1 focus, Shift+1 toggle by default).
- A full `managed` → `redstone project` rename as an isolated first milestone.
- Hard-cut all legacy screens.

## 3. Non-goals (deferred)

- Debugger & timeline panels (sub-project B); spec editor (deprioritized).
- Dockable/floating panels; dragging tabs between slots.
- Syntax highlighting, find/replace, multi-cursor.
- Cross-session layout persistence (splitter positions reset on open).
- Animations/transitions.
- "Underlay" viewport mode (game drawn full-size *under* the UI). We only implement the
  **shrink-into-center** mode. (Flashback has both; we start with one.)

## 4. Decisions log

Resolved during brainstorming:

| # | Decision |
|---|---|
| D1 | Build sub-project 0 (UI framework) first — it is the substrate for A/B/C. |
| D2 | **Full UI rewrite**: discard all current screens. |
| D3 | Layout = **fixed regions + drag splitters** (not floating/dockable, not tabbed-only). |
| D4 | Build **fresh** on MC's `AbstractWidget` + extract-render pipeline; YACL is *inspiration only*, not a dependency of the framework. |
| D5 | This spec ships the framework + **one** real panel (Project Explorer). Spec editor dropped. |
| D6 | Dock positions **L/B/R/Center**, each a **tab group** (multiple panels → tabs). |
| D7 | Docked panels **shrink the live game viewport into the center**; world visible unless a Center panel is shown. |
| D8 | Explorer: **Alt+1 focus**, **Shift+1 toggle** (configurable defaults). |
| D9 | **Hard-cut** legacy screens immediately; **drop** `recording.md` + `running.md` use-case docs. |
| D10 | Rebrand **managed worlds → redstone project**, **full rename now** (~70 files) as an isolated first step. |
| D11 | Input model: **game stays live with cursor grabbed** while a panel is visible-but-unfocused; Alt+N releases the cursor. |
| D12 | Viewport mechanism follows **Flashback** (`../Flashback`, Moulberry) as prior art — see §7. |

## 5. Phase 0 — Full rename: `managed` → `redstone project`

A dedicated, mechanical first milestone, kept isolated so its churn does not tangle with
the framework diff.

Scope (~70 files):
- Server package `com.breadmoirai.garnet.managed` → `…garnet.project`; all
  `Managed*` classes renamed (`ManagedCell`, `ManagedFolderTree`, `ManagedSession`, …).
- Network payload package `network/managed` → `network/project`; `Managed*` payloads.
- Command `/garnet managed` → `/garnet project`.
- ~15 unit/gametest/clientTest specs; registrations in `GametestSentinel` /
  `ClientTestSentinel`.
- Docs: `architecture/managed-redstone-worlds.md` → `redstone-project.md`; INDEX titles,
  tags, cross-references; use-case `managed-worlds.md` → project.

Exit criterion: the full 5-sourceset build (`clientClasses classes gametestClasses
clientTestClasses testClasses`) is green **before** Phase 1 begins.

## 6. Phase 1 — Framework architecture

### 6.1 Two-layer split

- **`client/ui/` — generic framework.** Domain-agnostic; no redstone, no networking. Its
  geometry and models are pure enough to unit-test on the JVM without an MC render
  context. Sub-packages: `ui/core` (Element, layout, slots, splitters, overlay, focus,
  the host), `ui/widget` (Label, Button, IconButton, ScrollPane, Row/Column/Stack,
  TreeView, tab strip, splitter).
- **`client/ide/` — concrete.** `IdeOverlay` assembly + `ProjectExplorerPanel`, wiring the
  framework to project state and the network.

Rationale: internals of one panel can change without touching another; layout arithmetic
is testable without booting Minecraft.

### 6.2 Panel model

- **`PanelView`** — a retained-mode titled panel. Persists across frames.
- **`PanelSlot`** — one of `LEFT`, `BOTTOM`, `RIGHT`, `CENTER`. Holds an ordered list of
  `PanelView`s rendered as a **tab strip**; the active tab's body renders.
- **Splitters** — draggable dividers between slots; resize with min/max clamping; cannot
  cross siblings.
- **`Shift+N`** toggles a panel's visibility (freeing/reclaiming its slot's reserved
  space). **`Alt+N`** focuses it.
- Slots paint as **opaque HUD** over the reserved edges, at **real window dimensions**
  (see §7 divergence).

### 6.3 Update model — retained tree + invalidation

Departure from today's `rebuildWidgets()` → re-run `init()` full rebuild (untenable for
live debugger/timeline):

- Elements persist and **pull** current values each frame — live data is free, no
  rebuild, no per-frame allocation.
- **Structural** changes (tree nodes added/removed, splitter/visibility change) call
  `invalidateLayout()`, which recomputes only dirty subtrees before the next frame.
- Network S2C handlers mutate backing state + call targeted `invalidate()` — never a full
  screen rebuild.

### 6.4 Overlay layer

`OverlayManager` on the host generalizes the existing DropdownHost popup-stratum trick:
any element pushes a popup/tooltip/context-menu that draws above all slots on
`graphics.nextStratum()` and gets input first-refusal. This structurally eliminates the
scissor-clipping bug class (see old `ui/dropdown-host-popup-stratum.md`) so no widget
re-invents it.

### 6.5 Widget set & DSL

`Element` base (named to avoid colliding with MC's text `Component`), `Label`, `Button`,
`IconButton`, `ScrollPane`, `Row`/`Column`/`Stack` containers (YACL-inspired
fill/fixed/weighted sizing, plain arithmetic — no constraint solver), `TreeView<T>`, tab
strip, splitter. Assembly via a Kotlin DSL matching the existing `garnetSpec { }` idiom:

```kotlin
ideOverlay {
    slot(LEFT, minWidth = 120) {
        panel("Explorer", focusKey = ALT_1, toggleKey = SHIFT_1) {
            projectTree(treeModel) { onSelect { … } }
        }
    }
    slot(BOTTOM) { /* future: timeline */ }
    slot(RIGHT)  { /* future: debugger */ }
    // CENTER left empty ⇒ game viewport fills the center
}
```

### 6.6 Project Explorer panel

`ProjectExplorerPanel` = `TreeView` over the project folder/spec tree, reusing the
existing tree-snapshot payload (post-rename). Expand/collapse, keyboard nav, selection.
Alt+1 / Shift+1 bound by default (configurable). Selection wiring to future
editor/debugger panels is **stubbed**, not built.

## 7. Live shrunk viewport — the top risk (Flashback-grounded)

Prior art: **`../Flashback`** (Moulberry). Its mechanism is reusable almost verbatim;
we adapt it for a multi-version Stonecutter mod and an MC-GUI (not ImGui) panel layer.

### 7.1 The mechanism

1. **`MixinWindow`** maintains an `overrideFramebufferWidth/Height` = the **content rect**
   (screen minus reserved slots). It overrides `getWidth`/`getHeight`,
   `getScreenWidth`/`getScreenHeight`, `calculateScale`, and `setGuiScale` to report the
   content-rect size. MC's whole render + GUI-scale + block-picking pipeline then behaves
   as if the window *is* the sub-rect (correct projection aspect, correct gui scale,
   correct raycast). `flashback$updateScaledFramebuffer(callResize)` recomputes the
   override each frame and fires `framebufferSizeChanged()` when it changes (RenderTarget
   resize). *(Flashback: `mixin/MixinWindow.java`.)*
2. **GL viewport offset**: after computing the override, position the actual GL viewport at
   the content-rect offset within the real window —
   `GlStateManager._viewport(frameX·s, frameBottom·s, frameWidth·s, frameHeight·s)` where
   `frameBottom = realHeight - (frameY + frameHeight)` for GL's bottom-left origin, and
   `s` scales framebuffer↔screen. *(Flashback: `ReplayUI.setupMainViewport`.)*
3. **Frame-loop hooks** (`MixinMinecraft`): recompute the override in `framebufferSizeChanged`
   and after `GameRenderer.render` inside `renderFrame`; draw our panel overlay there.
   *(Flashback: `MixinMinecraft.afterMainRender`, `framebufferSizeChanged`.)*
4. **Mouse remap** (`MixinMouseHandler`): world picking / camera turning use frame-relative
   coords — `newMouseX = rawX - frameX`, `newMouseY = rawY - frameY`; normalize into the
   frame rect for NDC. Panel input uses real window coords. *(Flashback:
   `getNewMouseX/Y`, `getMouseViewportFraction`, `mixin/MixinMouseHandler.java`.)*
5. **Cursor/focus**: toggle GLFW cursor mode on focus change and call
   `mouseHandler.setIgnoreFirstMove()` on re-grab to avoid a camera jump. This *is* our
   Alt+N-release / un-focus-re-grab model. *(Flashback: `ReplayUI.transitionActiveState`.)*

### 7.2 The divergence we must design around

Flashback draws its editor UI in **ImGui / native GL at real window coords** — a layer
entirely separate from MC's GUI. That is *why* it can safely shrink MC's
`guiScaledWidth/Height` (the vanilla HUD shrinks into the content rect too).

Our panels render with **MC's own `GuiGraphics`/extract pipeline**, which keys off
`guiScaledWidth/Height`. If we shrink `guiScaled*` the way Flashback does, our panels get
confined to the content rect instead of drawing in the reserved edges.

**Resolution (spike decides the exact mechanism):** render the panel layer at
**real-window dimensions**, in a pass *parallel to* the shrunk world+HUD pass — e.g.,
draw panels while the `Window` override is temporarily disabled (or read the real
framebuffer size directly), then re-enable the override for the world/HUD pass. Our
framework being a bespoke top-level layer (rather than a vanilla `Screen`) is what makes
this clean: a vanilla `Screen` would inherit the shrunk `guiScaled*` dims.

### 7.3 Multi-version note

Flashback targets a single MC version. We are multi-version Stonecutter (26.1 + older),
and `Window` / `Minecraft` / `MouseHandler` internals drift across versions. The mixins
must be **version-gated** (Stonecutter conditions / versioned source) — adapt, do not
copy. The spike targets the newest version first, then backports.

### 7.4 Spike (plan step 1, before any panel work)

Prove, on the newest MC version: (a) world renders into a hard-coded central sub-rect with
correct aspect and gui scale; (b) block-picking crosshair hits the right block through the
shrunk viewport; (c) our MC-GUI panel layer draws in the reserved edges at full window
size simultaneously; (d) cursor grab/ungrab on a keybind with no camera jump. Only then
build the dock on top.

## 8. Hard-cut & documentation changes

**Delete (client screens):** `RecorderScreen`, `RunnerScreen`, `ManagedScreen`,
`ManagedRootListScreen`, plus their orphaned widgets (`IntEditBox`, `GarnetIconButton`,
`TimelineSliderWidget` — each verified unused before deletion). Server-side recording /
running / project engines remain; their UIs return later as panels (A/B).

**Use-case docs:** drop `recording.md` + `running.md` (deprioritized, now UI-less);
rebrand `managed-worlds.md` → project; update `command.md`; edit `cross-cutting.md`
references to the dropped journeys.

**UI docs:** rewrite `docs/ui/*` for the new framework — absorb
`dropdown-host-popup-stratum.md` into an "overlay layer" article; carry forward the ARGB
and extract-render pitfalls (`argb-color-pitfalls.md`, `intstepper-vs-inteditbox.md` →
generalized). Add an `architecture/` note on the shrunk-viewport + retained-mode model.
Add UI-framework use-cases.

## 9. Error handling

- Splitters clamp to min/max and cannot cross siblings — rects never go negative/NaN;
  collapsed slots skip body render but keep their tab strip.
- Viewport: guard against a zero-area content rect (all slots maxed) — clamp to a minimum
  central size; never pass width/height ≤ 0 to `_viewport`.
- Focus manager guarantees the cursor is **re-grabbed** if the focused panel is hidden
  (Shift+N while focused), with `setIgnoreFirstMove`.
- One modal overlay chain at a time; an outside click closes it.

## 10. Testing

- **JUnit (`src/test`):** slot-rect math across splitter/visibility states + clamping;
  content-rect computation; `TreeView` flatten/expand/selection; invalidation
  propagation; overlay hit-testing. Kept unit-testable by keeping render-touching code
  thin.
- **clientTest (`ClientSpec` / `FabricClientGameTest`):** open the overlay, assert
  slots/tabs; drag a splitter → content rect shrinks; Shift+1 toggles and reclaims space;
  Alt+1 routes input; tree-select fires; screenshot. Register new specs in
  `GametestSentinel` (autoscan is off).
- **Viewport spike verification:** a manual/clientTest check that a known block under the
  crosshair is picked correctly with a shrunk viewport.

## 11. Package layout

```
src/client/kotlin/com/breadmoirai/garnet/client/
  ui/core/      Element, Layout, PanelSlot, PanelView, Splitter, OverlayManager, FocusManager, IdeHost
  ui/widget/    Label, Button, IconButton, ScrollPane, Row/Column/Stack, TreeView, TabStrip
  ide/          IdeOverlay, ProjectExplorerPanel
src/main/…/mixin(client)/   MixinWindow, MixinMinecraft, MixinMouseHandler  (version-gated)
```

Retired: `client/screen/`, `client/managed/*Screen`, `client/widget/TimelineSliderWidget`.

## 12. Plan shape (milestones)

1. **Phase 0 rename** (isolated; 5-sourceset build green).
2. **Viewport/input spike** (§7.4) — hard-coded rect, prove the mechanism + panel-layer
   coexistence + cursor grab.
3. **Framework core** — Element, layout, slots, splitters, overlay, focus, host; content
   rect published to the viewport mechanism.
4. **Widget set + `TreeView`.**
5. **`ProjectExplorerPanel`** + Alt+1/Shift+1 keybinds, wired to the project tree payload.
6. **Hard-cut** legacy screens.
7. **Docs** — rewrite `docs/ui/*`, drop/rebrand use-cases, add architecture + framework
   use-case notes.

## 13. Open risks

- **Viewport across versions** — the highest risk; the mixins are version-sensitive. The
  spike targets newest-first; backport is a separate effort if internals diverge sharply.
- **Panel-layer vs shrunk-GUI coexistence** (§7.2) — the spike must land this cleanly; if
  it can't, fall back to *not* shrinking `guiScaled*` and manually offsetting the vanilla
  HUD.
- **Functionality gap** — hard-cutting recorder/runner UIs removes those flows until
  re-homed in A/B. Accepted per D9.
- **Rename churn** (D10) — large mechanical diff; mitigated by isolating it as Phase 0
  with a green-build gate.
