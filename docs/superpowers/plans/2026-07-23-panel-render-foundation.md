# Panel Render Foundation (Phase 1a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Checkbox (`- [ ]`) steps. Tasks 1 & 3 are **graphics bring-up** — verified by a running client (screenshot), not a pre-written unit test; expect iteration.

**Goal:** Establish the framework's rendering + docking foundation on top of the proven viewport spike: render a real-size **panel layer** into the reserved edges over the composited world, and make a minimal **dock** drive the world's content rect *dynamically* (replacing `ViewportState`'s hard-coded insets). Prove it with one placeholder panel and one working splitter.

**Architecture:** The viewport spike already shrinks the world into a content sub-rect of a real-size composite target (`MinecraftPresentMixin`), filling the reserved edges with a solid color. This plan replaces that solid fill with a **panel render pass** drawn at real window dimensions into the same composite, and replaces `ViewportState`'s constant `RESERVED_LEFT/BOTTOM` with a **`Dock`** that computes reservations from its `PanelSlot`s and publishes the content rect. This is Phase **1a** — the render/dock foundation. The retained-mode widget system, `TreeView`, `ProjectExplorerPanel`, Alt/Shift focus, overlay layer, and the hard-cut of legacy screens are **follow-on plans (1b, 1c)** written once this foundation is proven.

**Tech Stack:** Kotlin (dock/panel/render-layer) + Java mixin edits, MC 26.2 Blaze3D GPU API + GUI render pipeline (`GuiGraphics`/`GuiGraphicsExtractor`/`GuiRenderer`/`GuiRenderState`), the spike's `BlitUvPipeline`/`CompositeTarget`/`ViewportState`/`WindowMixin`/`MinecraftPresentMixin`.

## Global Constraints

- **Builds on the spike (commits `0061ba4..ef1ff6d`)** — reuse `ViewportState`, `CompositeTarget`, `BlitUvPipeline`, `WindowMixin`, `MinecraftPresentMixin`. Do not duplicate them.
- **OFF by default / no vanilla regression:** the panel layer and dock only take effect when the viewport is toggled active (spike keybind for now). With the mod loaded and nothing toggled, rendering + input are byte-for-byte vanilla. Every render/mixin path early-returns when `!ViewportState.shouldModify()`.
- **Panels render at REAL window dims**, not the shrunk `guiScaled*` dims (the central divergence — see spec §7.2, `docs/superpowers/specs/2026-07-23-redstone-project-ide-panel-framework-design.md`). This is Task 1's core problem to resolve.
- **Clean-room:** if any rendering technique is cross-checked against `../Flashback` (its `compositeOnTop` real-size-UI-on-top approach is the analog), study only — copy nothing. `../Flashback` is all-rights-reserved.
- **Element base is named `Element`**, never `Component` (collides with MC text `Component`).
- **Single MC version** (`:26.1:` task, MC 26.2). No version-gating.
- **Build (5 source sets):** `cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` (≤600000ms).
- **Runtime verify:** `cmd.exe /c "gradlew.bat :26.1:runClientTest"`; new `ClientSpec`s registered in `ClientTestSentinel`; visual proof via the composite-capture path (`ViewportState.compositeCaptureRequest` → PNG, as the spike does).

---

### Task 1: Real-size panel render layer (the render-integration proof)

**Goal:** Draw arbitrary MC-GUI content (a filled rect + a line of text) into the composite target at REAL window dimensions, positioned in the reserved LEFT edge — proving panels can render over the composited world. This is the load-bearing unknown; resolve the exact mechanism here before any dock work.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ui/PanelRenderLayer.kt` — `render(composite: RenderTarget, realW: Int, realH: Int)` that draws into `composite` at real dims. For this task, hard-code a single filled rect + text label in the left reserved strip.
- Modify: `src/client/java/com/breadmoirai/garnet/mixin/client/MinecraftPresentMixin.java` — after the world-texture blit into the content rect (line ~90) and before `original.call(composite)`, call `PanelRenderLayer.render(composite, realWidth, realHeight)` (only when active). The solid `clearColor` edge fill stays as the panel-layer background for now.

**Interfaces:**
- Produces: `PanelRenderLayer.render(composite, realW, realH)`.

- [ ] **Step 1 — investigate the 26.2 GUI render path.** Determine how to record + submit MC GUI draws (fill, text via `Font`) into a chosen `RenderTarget` at explicit dimensions while `Window` reports shrunk dims. Extract/read `net.minecraft.client.gui.GuiGraphics`, `GuiRenderer`, `GuiRenderState`, and `GuiGraphicsExtractor` from the sources jar (`/mnt/h/Repo/garnet/.gradle/loom-cache/.../minecraft-clientOnly-*-sources.jar`; extract with `cmd.exe /c "jar xf s.jar <path>"`). Identify how `Minecraft` normally constructs a `GuiGraphics` and renders the GUI to the main target, and whether we can (a) build a `GuiGraphics` with explicit real width/height and submit its render state to the composite target, or (b) temporarily clear the `WindowMixin` override for the duration of the pass, or (c) drive `GuiGraphicsExtractor` fill/text directly. Document the chosen mechanism in a new `docs/ui/panel-render-layer-26.md`.
- [ ] **Step 2 — implement `PanelRenderLayer.render`** using the chosen mechanism: fill a rect at real coords in the left strip (e.g. `(8, 8)`–`(RESERVED_LEFT-8, 200)`) with a panel background, and draw a text label ("Explorer" placeholder) via `Minecraft.getInstance().font`. Use `-1` (0xFFFFFFFF) for white text (0xFFFFFF is invisible — see `docs/ui/argb-color-pitfalls.md`).
- [ ] **Step 3 — wire into the composite** (mixin edit); build all 5 source sets → SUCCESSFUL.
- [ ] **Step 4 (runtime, required):** capture a composite PNG (reuse the spike's `compositeCaptureRequest` flow in a `ClientSpec`, registered in `ClientTestSentinel`) with the viewport active. **Expected:** the world in the centered sub-rect, and in the left reserved strip a filled panel rect with readable "Explorer" text — proving real-size GUI content composites over the world. Report the screenshot path.
- [ ] **Step 5 — commit** `feat(ui): real-size panel render layer over the composite`.

### Task 2: Minimal dock drives the content rect dynamically

**Goal:** Replace `ViewportState`'s hard-coded `RESERVED_LEFT/BOTTOM` with a `Dock` of `PanelSlot`s that computes reservations, so adding/sizing a panel changes the world's content rect. One placeholder panel occupies the LEFT slot.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ui/Element.kt` — minimal retained base: `var bounds: Rect`, `fun render(layer)`, no-op input for now. `data class Rect(x, y, w, h)`.
- Create: `.../ui/PanelSlot.kt` — `enum SlotPos { LEFT, RIGHT, BOTTOM, CENTER }`; a `PanelSlot(pos, var sizePx, var visible)` holding an ordered list of `Panel`s (tabs; one for now).
- Create: `.../ui/Panel.kt` — `Panel(title: String, content: Element)`.
- Create: `.../ui/Dock.kt` — holds the four slots; `contentRect(realW, realH): ContentRect` computed from visible non-CENTER slots' sizes; `layout(realW, realH)` assigns each slot/panel a `Rect`; `render(layer, realW, realH)` draws visible slots.
- Modify: `ViewportState.kt` — `contentRect` delegates to `Dock.contentRect` (keep a fallback if the dock is empty); remove the `RESERVED_LEFT/BOTTOM` constants (or keep as the LEFT slot's default size).
- Modify: `PanelRenderLayer.render` — draw `Dock.render(...)` instead of the hard-coded rect.

**Interfaces:**
- Consumes: `PanelRenderLayer` (Task 1), `ViewportState.ContentRect`.
- Produces: `Dock.contentRect(realW, realH)`, `Dock.layout(...)`, `Dock.render(layer, realW, realH)`, `PanelSlot`, `Panel`, `Element`, `Rect`.

- [ ] **Step 1 (test-first, pure logic):** unit-test `Dock.contentRect` in `src/test` — with a LEFT slot of 260 and a BOTTOM slot of 160 visible, `contentRect(1280,720)` == `(260,0,1020,560)`; hiding the BOTTOM slot reclaims its space; an empty/all-hidden dock yields the full rect. (This is pure geometry — real TDD applies here, unlike the graphics tasks.)
- [ ] **Step 2:** implement `Element`/`Rect`/`PanelSlot`/`Panel`/`Dock` to pass the test; run `:26.1:test` → green.
- [ ] **Step 3:** wire `ViewportState.contentRect` → `Dock.contentRect`; put one placeholder `Panel("Explorer", <colored Element>)` in the LEFT slot; `PanelRenderLayer` renders the dock. Build 5 source sets → SUCCESSFUL.
- [ ] **Step 4 (runtime):** capture a PNG with the viewport active — the left panel is drawn by the dock and the world content rect matches the dock's reservation. Screenshot path in report.
- [ ] **Step 5 — commit** `feat(ui): dock drives content rect; placeholder LEFT panel`.

### Task 3: One splitter resizes the slot; world resizes live

**Goal:** A draggable splitter between the LEFT slot and the content rect; dragging it changes the slot size → the world's content rect updates live. Proves the dynamic dock ↔ viewport loop under interaction.

**Files:**
- Create: `.../ui/Splitter.kt` — an `Element` on the slot's inner edge; on drag, mutates the slot's `sizePx` (clamped to min/max, not past the window), and fires `ViewportState`/Window resize so the world re-renders at the new content rect.
- Modify: input routing — add a client mouse hook (a `MouseHandler` mixin or a Fabric client mouse event) that, when the viewport is active, hit-tests the splitter at REAL coords and routes drag to it; **must not** consume input when the viewport is inactive or when the cursor is over the content rect (that goes to the game).

**Interfaces:**
- Consumes: `Dock`/`PanelSlot` (Task 2), `WindowMixin`'s `garnet$updateScaledFramebuffer`.
- Produces: `Splitter`; the drag→resize→content-rect-update loop.

- [ ] **Step 1:** implement `Splitter` drag math (unit-test the clamp: dragging past min/max/window clamps the slot size) in `src/test`; `:26.1:test` green.
- [ ] **Step 2:** implement input routing (hit-test at real coords, active-only); on drag, update slot size + call `garnet$updateScaledFramebuffer(true)` so the content rect and world resize. Build 5 source sets → SUCCESSFUL.
- [ ] **Step 3 (runtime):** with the viewport active, drag the splitter; capture before/after PNGs showing the panel widened and the world content rect shrunk to match. Report both paths.
- [ ] **Step 4 — commit** `feat(ui): splitter resizes slot and world content rect live`.

---

## Exit criteria (foundation proven)

- Real-size MC-GUI panel content renders in the reserved edges over the composited world (Task 1).
- The dock computes reservations and drives `ViewportState.contentRect` dynamically (Task 2).
- Dragging a splitter resizes the slot and the live world content rect together (Task 3).

On success, the follow-on plans build on this: **1b** — retained-mode widget set (`Label`, `Button`, `ScrollPane`, `Row/Column/Stack`, `TreeView`), the `ProjectExplorerPanel` over `ProjectTreeSnapshotS2C`, Alt+1 focus / Shift+1 toggle, the overlay layer; **1c** — hard-cut the legacy screens (`RecorderScreen`, `RunnerScreen`, `ProjectScreen`, `ProjectRootListScreen`) and rewrite `docs/ui/*`.

## Self-Review

- **Scope:** deliberately just the render/dock foundation — the framework's biggest unknown (real-size panel rendering over the composite) plus the dynamic content-rect loop. Widgets/tree/explorer/focus/hard-cut are explicitly deferred to 1b/1c (scope-check decomposition).
- **Placeholders:** Tasks 1 & 3 are graphics/interaction bring-up verified by screenshots (appropriate — pixels can't be unit-asserted meaningfully yet); Task 2's pure geometry uses real TDD. Task 1 Step 1 is genuinely investigative (the render mechanism is unproven) — this is the plan's single accepted unknown, isolated as the first step, mirroring the viewport spike.
- **No-regression:** every task reiterates OFF-by-default + active-only gating.
- **Type consistency:** `ContentRect`/`contentRect` reused from `ViewportState`; `Element`/`Rect`/`PanelSlot`/`Panel`/`Dock` defined in Task 2 and consumed in Task 3.
