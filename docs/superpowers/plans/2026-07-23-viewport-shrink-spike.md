# Viewport Shrink Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax. This is a **graphics bring-up spike** — milestones are incremental and each is verified by a running client (screenshot), not by a pre-written unit test. Expect iteration within each task.

**Goal:** Prove, on MC 26.2, that we can render the live game world into a central sub-rect while our MC-GUI panel layer draws in the reserved edges at full window size — using a **clean-room** custom blit shader (no Flashback code).

**Architecture:** A client `ViewportState` publishes a hard-coded content rect. A `WindowMixin` overrides the window's reported framebuffer/gui dimensions so MC renders the world+HUD at content-rect *size*. A `MinecraftMixin` wraps the final present-blit, compositing the (shrunk) game texture into a full-size target at the frame offset via **our own** `RenderPipeline` (`redstonespecs:blit_uv`, GLSL authored in-repo). Mouse picking is remapped and the cursor is grabbed/released on a keybind.

**Tech Stack:** Kotlin (state/registration) + Java mixins (`src/client/java/.../mixin/client`), MC 26.2 Blaze3D GPU API (`RenderPipeline`, `GpuSurface`/`CommandEncoder`/`GpuTextureView`, `RenderPass`, `TextureTarget`), Fabric key-mapping-api, Gradle via `cmd.exe /c "gradlew.bat …"`.

## Global Constraints

- **Clean-room:** Flashback (`../Flashback`) is licensed "All rights reserved, do not redistribute." Study it for *technique/approach only*. **Copy no code, no shader source, no asset.** Every line here is ours. The `WindowMixin` overrides public MC API (a generic technique) — acceptable; the shader GLSL and pipeline must be original.
- **Single MC version:** Stonecutter has one node (`:26.1:` task prefix, `minecraft_version=26.2`). No version-gating needed now; if a second node is added later, the mixins/pipeline become backport candidates.
- **Mixins:** Java, `package com.breadmoirai.redstonespecs.mixin.client`, registered in `src/client/resources/redstonespecs.client.mixins.json`, `compatibilityLevel JAVA_25`, `abstract` + package-private, `@Unique` fields prefixed `redstonespecs$`.
- **Do not regress existing behavior.** The viewport effect is OFF unless toggled by the spike keybind; when off, every `WindowMixin`/`MinecraftMixin` inject must early-return so vanilla behavior is byte-for-byte unchanged.
- **Build (5 source sets):** `cmd.exe /c "cd /d H:\\Repo\\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`.
- **Runtime verify:** `cmd.exe /c "gradlew.bat :26.1:runClientTest"` — client game tests can drive input and capture screenshots (see `docs/gametest/` client-test helpers, `ClientSpec`). New client specs must be registered in `ClientTestSentinel`. A manual `runClient` pass is an acceptable fallback for visual confirmation.
- Reference (read, don't copy): Flashback `mixin/MixinWindow.java`, `mixin/MixinMinecraft.java` (`renderFrame` `@WrapOperation` on `GpuSurface.blitFromTexture`), `FramebufferUtils.java`, `editor/ui/ReplayUI.java` (`transitionActiveState`, `getMouseViewportFraction`).

## The corrected 26.2 mechanism (why these tasks)

MC's default present **stretches** the game texture to fill the surface, so shrinking the `Window` alone yields a lower-res *full-screen* world, not a centered sub-rect. The visible sub-rect requires intercepting the present blit and compositing the shrunk texture into a sub-rect ourselves — hence Task 1 (our blit pipeline) precedes Task 3 (composite). `setupMainViewport`/`GlStateManager._viewport` (old approach) is dead code in 26.2 — do not use it.

---

### Task 1: Clean-room `blit_uv` RenderPipeline + shaders

**Goal:** Draw an arbitrary `GpuTextureView` into an arbitrary sub-rect of a `RenderTarget`, using our own pipeline. Prove it in isolation before any Window shrink.

**Files:**
- Create: `src/client/resources/assets/redstonespecs/shaders/core/blit_uv.vsh` / `.fsh` (original GLSL: position + UV attributes, samples `InSampler`, no post-processing).
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/viewport/BlitUvPipeline.kt` — builds a `RenderPipeline` via `RenderPipeline.builder(...)` referencing our shaders + `DefaultVertexFormat.POSITION_TEX`; a `blit(from: GpuTextureView, to: RenderTarget, x1,y1,x2,y2: Float)` that records a `RenderPass` drawing a quad (NDC from normalized rect) sampling `from`.
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/viewport/CompositeTarget.kt` — `resizeOrCreate(RenderTarget?, w, h): TextureTarget` + `clearTransparent(RenderTarget)` (our own, modeled on the MC API not on Flashback).

**Interfaces:**
- Produces: `BlitUvPipeline.blit(from, to, x1, y1, x2, y2)`; `CompositeTarget.resizeOrCreate(...)`, `CompositeTarget.clearTransparent(...)`.

- [ ] **Step 1:** Write `blit_uv.vsh`/`.fsh` (original). Model *conceptually* on vanilla `shaders/core/blit_screen.fsh` (extract from the MC jar to learn the uniform/sampler conventions) but author fresh GLSL.
- [ ] **Step 2:** Implement `BlitUvPipeline` + `CompositeTarget` against the 26.2 API (`RenderPipeline.builder`, `RenderSystem.getDevice().createCommandEncoder().createRenderPass(...)`, `TextureTarget`). Confirm the exact `RenderPipeline.Builder` method names against the resolved jar via `javap` (`with*Shader`, `withVertexFormat`, `withLocation`, blend/depth state).
- [ ] **Step 3:** Build all 5 source sets → BUILD SUCCESSFUL.
- [ ] **Step 4 (runtime):** Temporary probe — in a client-tick or HUD hook, blit an obvious texture (e.g. the crosshair/GUI atlas or a solid color target) into a `(0.25,0.25)-(0.75,0.75)` sub-rect of the main target and screenshot. **Expected:** the texture appears in the central quarter, edges untouched. Remove the probe after confirming.
- [ ] **Step 5:** Commit `feat(viewport): clean-room blit_uv pipeline + composite target`.

### Task 2: `ViewportState` + `WindowMixin` (shrink the game render)

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/viewport/ViewportState.kt` — `var active`, hard-coded insets (e.g. left=260, bottom=160), `contentRect(realW, realH)` → `frameX/frameY/frameWidth/frameHeight`, `realWidth/realHeight` (cached from the un-overridden window at resize), `shouldModify()`.
- Create: `src/client/java/com/breadmoirai/redstonespecs/mixin/client/WindowMixin.java` — `@Shadow` `framebufferWidth/Height`, `width/height`, `guiScale/guiScaledWidth/guiScaledHeight`, `eventHandler`; `@Unique` override dims + `redstonespecs$updateScaledFramebuffer(bool)`; `@Inject(HEAD, cancellable)` into `getWidth/getHeight/getScreenWidth/getScreenHeight/calculateScale/setGuiScale` returning content-rect values when `shouldModify()`.
- Modify: `src/client/resources/redstonespecs.client.mixins.json` (add `WindowMixin`).
- Modify: `RedstonespecsClient.kt` (register a `ViewportState` keybind toggle via `ClientTickEvents`, calling `updateScaledFramebuffer(true)` on change).

**Decision baked in:** For the spike, override `setGuiScale`/`guiScaled*` too (full shrink, like the reference). The MC-GUI-panel-in-reserved-edges coexistence is resolved in Task 3 by drawing panels into the *composite* pass at real size.

- [ ] **Step 1:** Implement `ViewportState`. Capture `realWidth/Height` in `updateScaledFramebuffer` from the shadowed `framebufferWidth/Height` *before* overriding (the tracker the composite needs, since the window will start lying).
- [ ] **Step 2:** Implement `WindowMixin` (mirror the confirmed public-API overrides; `@Unique` prefix `redstonespecs$`).
- [ ] **Step 3:** Register mixin + keybind. Build 5 source sets → SUCCESSFUL; launch client, toggle on → confirm no mixin-apply errors in log and no crash.
- [ ] **Step 4 (runtime):** With a **non-16:9** content rect (e.g. a square), toggle on and screenshot. **Expected:** the world visibly changes aspect (stretched) / resolution — proving projection + gui scale follow the Window override. (Still full-screen; centering comes in Task 3.)
- [ ] **Step 5:** Commit `feat(viewport): ViewportState + WindowMixin shrink lever`.

### Task 3: Composite the shrunk world into the center + panels in the edges

**Files:**
- Create: `src/client/java/com/breadmoirai/redstonespecs/mixin/client/MinecraftPresentMixin.java` — `@WrapOperation` on the present blit in `Minecraft.renderFrame` (`GpuSurface.blitFromTexture(CommandEncoder, GpuTextureView)`; confirm the exact target via `javap`/bytecode of `renderFrame`). When `ViewportState.shouldModify()`: build a full-size composite via `CompositeTarget`, clear it, `BlitUvPipeline.blit` the game `textureView` into the normalized frame rect, draw a solid fill in the reserved edges (spike stand-in for panels) via a second blit/HUD pass at real size, then `original.call(..., composite.getColorTextureView())`; else `original.call` untouched. Also `@Inject` `framebufferSizeChanged` → `updateScaledFramebuffer(false)`.

- [ ] **Step 1:** Implement the wrap; confirm the exact `blitFromTexture` descriptor and that `renderFrame` invokes it once.
- [ ] **Step 2:** Build → SUCCESSFUL.
- [ ] **Step 3 (runtime — the spike's core proof):** toggle on, screenshot. **Expected:** the live world renders in the central sub-rect at correct aspect; the reserved left/bottom edges show the solid fill; toggling off restores full-screen vanilla exactly.
- [ ] **Step 4:** Commit `feat(viewport): composite shrunk world into center`.

### Task 4: Mouse-pick remap + cursor grab/focus keybind

**Files:**
- Create: `src/client/java/com/breadmoirai/redstonespecs/mixin/client/MouseHandlerViewportMixin.java` — remap for world picking. First **test whether it's needed**: because `WindowMixin` overrides the window dims and 26.2 routes raw→gui through `MouseHandler.getScaledXPos/YPos(Window, …)`, picking may already be correct within the shrunk framebuffer. If the crosshair mis-picks, remap `xpos/ypos` (or the scaled-pos results) by the frame offset.
- Modify: `ViewportState` + client init — a focus keybind: on focus, GLFW `CURSOR_NORMAL` + on un-focus re-grab via `mouseHandler.grabMouse()` and `setIgnoreFirstMove()` (confirm method name in 26.2) to avoid a camera jump.

- [ ] **Step 1 (runtime):** With the viewport shrunk, aim the crosshair at a known block; verify `Minecraft.hitResult` targets the correct block (F3 or a debug HUD line). If correct, skip the remap mixin.
- [ ] **Step 2:** If needed, implement `MouseHandlerViewportMixin`; re-verify picking.
- [ ] **Step 3 (runtime):** Bind a focus key; confirm cursor releases (can move over the reserved edges) and re-grabs on un-focus with no camera snap.
- [ ] **Step 4:** Commit `feat(viewport): picking remap + cursor focus`.

---

## Exit criteria (spike answered)

- (a) world renders into a central sub-rect with correct aspect + gui scale — Task 3 Step 3.
- (b) block-picking hits the right block through the shrunk viewport — Task 4 Step 1.
- (c) our render layer draws in the reserved edges at full window size simultaneously — Task 3 Step 3.
- (d) cursor grab/ungrab on a keybind with no camera jump — Task 4 Step 3.

On success, fold the confirmed mechanism into the Phase 1 framework plan (the dock publishes the real content rect to `ViewportState`; the reserved-edge fill becomes the real panel layer). On failure at Task 3 (compositing infeasible/too costly), fall back to **underlay mode** (panels overlay a full-size world; no Window/present mixins) and proceed with the framework unblocked.

## Self-Review

- **Coverage:** each spike exit criterion (a-d) maps to a task step. The clean-room constraint, the corrected mechanism, and the OFF-when-untoggled no-regression rule are in Global Constraints.
- **Ordering:** blit pipeline (Task 1) before composite (Task 3) because the composite depends on it; Window shrink (Task 2) is independently buildable.
- **Placeholders:** none — steps name exact files/APIs; graphics steps specify the screenshot expectation as the pass condition (appropriate for a visual spike; unit tests can't assert "looks right").
- **Fallback:** Task 3's failure path (underlay mode) is explicit, so the spike can't hard-block the framework.
