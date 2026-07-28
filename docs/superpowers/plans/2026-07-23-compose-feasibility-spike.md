# Compose-in-Minecraft Feasibility Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development or executing-plans. This is a **go/no-go feasibility spike**, not a build-out. Task 1 is the decision gate; do the least work that answers it. Verified by a running client (screenshot + no-crash), not unit tests.

**Goal:** Decide definitively whether **Compose Multiplatform (Desktop)** can render an interactive UI embedded in Minecraft 26.2's render pipeline — Compose drawn to a texture we blit into the viewport composite, with input wired — or whether it's too fragile and we fall back to **ImGui**.

**Architecture:** The viewport spike already composites the shrunk world into a real-size `RenderTarget` (`MinecraftPresentMixin`) with `BlitUvPipeline`. This spike renders a Compose scene, via Skiko, into an FBO-backed Skia surface we own, then blits that FBO's texture on top of the composite (the "UI on top" pattern) — proving Compose pixels can coexist with MC's Blaze3D rendering without corrupting either.

**Tech Stack:** Kotlin, Compose Multiplatform (Desktop) + Skiko, MC 26.2 Blaze3D/LWJGL 3.3.x GL, JDK 25. Reuses `BlitUvPipeline`/`CompositeTarget`/`ViewportState`/`MinecraftPresentMixin`.

## Global Constraints

- **This is a decision, not a product.** Success = a clear GO (Compose renders + takes input, stably, over the viewport) or NO-GO (fragile/infeasible → adopt ImGui). Do not build panels/docking here.
- **ImGui is the documented fallback.** If Task 1 fails its go/no-go, stop and record why; the next plan becomes the ImGui feasibility spike.
- **Build on the viewport spike** (`0061ba4..ef1ff6d`); reuse `BlitUvPipeline`/`CompositeTarget`/`MinecraftPresentMixin`. OFF by default — nothing renders/inputs unless the viewport is toggled active.
- **No vanilla regression:** if Compose init fails or is disabled, the client must launch and play exactly as vanilla (guard all Compose calls; never let a Skiko/native failure crash startup).
- **Single MC version** (`:26.1:` task = MC 26.2), JDK 25 toolchain.
- **Build (5 source sets):** `cmd.exe /c "cd /d H:\\Repo\\garnet && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` (≤600000ms). **Runtime:** `runClientTest` (screenshot via `ViewportState.compositeCaptureRequest`) or a manual `runClient`.
- **References (study, adapt — all open-source, unlike Flashback):** JetBrains `compose-multiplatform` `lwjgl-integration` example (issue #652 / PR #5221); `VexorMC/compose` (LWJGL fork; note its `ComposeScene`↔`GlobalSnapshotManager` race fix + key-event patch); Skiko `DirectContext.makeGL()` + `Surface.makeFromBackendRenderTarget()`; `androidx.compose.ui.scene.ComposeScene`.

---

### Task 1: GO/NO-GO — Compose renders over the viewport, coexisting with MC

**Goal:** Prove (or disprove) that Compose can draw to a texture we blit into the composite, per frame, without corrupting MC's own rendering. This single task is the decision.

**Files:**
- Modify: `build.gradle.kts` — add Compose Multiplatform (Desktop) + Skiko dependencies to the `client` source set. **First resolve the version matrix**: a Compose-MP/Skiko pair whose Skiko native is a *desktop-GL* build compatible with MC's LWJGL 3.3.x GL context, runnable on JDK 25. If stock Compose-MP won't drive a `ComposeScene` in an LWJGL loop (the VexorMC fork exists precisely because of this), evaluate using that fork's coordinates or its two patches. Document the exact coordinates chosen and why in `docs/ui/compose-in-mc-feasibility.md`.
- Create: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/ComposeSurface.kt` — owns: Skiko native load (guarded, logged), a `DirectContext.makeGL()` over MC's current GL context, an FBO + `Surface.makeFromBackendRenderTarget()` sized to the real window, a `ComposeScene` whose content is a trivial composable (a `Box` with a background color + a `Text("Compose in MC")`), and `renderFrame(nanos)` that recomposes + draws the scene to the Skia surface and returns the FBO's `GpuTextureView`/texture id. Every entry point guarded so any failure logs and disables Compose rather than crashing.
- Create: `.../ui/compose/ComposeOverlay.kt` — a small integration object called from the present path.
- Modify: `MinecraftPresentMixin.java` — after the world blit into the content rect, if Compose is enabled + initialized, blit the Compose FBO texture over the composite (full-window, or into the left reserved strip) via `BlitUvPipeline`, then present. Guarded so a Compose failure falls back to the existing solid-edge composite.

**Interfaces:**
- Produces: `ComposeSurface.renderFrame(nanos)` → texture; `ComposeOverlay.renderInto(composite, realW, realH)`.

- [ ] **Step 1 — dependency + native load.** Add the deps; get the client to LAUNCH with Skiko's native loaded (or cleanly disabled on failure). Verify: `runClientTest`/`runClient` starts, log shows Skiko loaded (or the guarded-disable message) — **no startup crash, vanilla still playable.** This alone kills the biggest risk (native/classloader under Fabric's Knot loader). If it can't load without crashing after real effort, that's a NO-GO signal — record and stop.
- [ ] **Step 2 — Skia surface over MC's GL context.** Implement `ComposeSurface`: `makeGL()` + FBO + `makeFromBackendRenderTarget`. Prove a plain Skia draw (a filled rect, no Compose yet) into the FBO, blitted into the composite, appears on screen AND MC keeps rendering correctly the next frame (call `directContext.resetContext()` / restore GL state as needed so Blaze3D isn't corrupted). Screenshot: MC world + a Skia rect coexisting. **This is the crux — GL-state coexistence.**
- [ ] **Step 3 — Compose scene.** Add the `ComposeScene` with the trivial composable; render it to the same surface; blit into the composite. Screenshot: the "Compose in MC" text/box visible over the live world.
- [ ] **Step 4 — GO/NO-GO verdict.** Run several frames (not one) with the viewport active; confirm stable (no flicker/crash/GL-state drift) across frames and that toggling the viewport off restores clean vanilla rendering. Write the verdict + evidence (screenshot paths, stability notes, the exact versions) to `docs/ui/compose-in-mc-feasibility.md` and the report. Commit `spike(ui): compose-in-mc feasibility — <GO|NO-GO>`.

### Task 2: (ONLY if Task 1 = GO) one input event + resize

**Goal:** Confirm interactivity is wireable before committing to Compose for the framework.

- [ ] **Step 1:** feed a GLFW mouse-move + click (from our input hook, active-only) into `ComposeScene.sendPointerEvent`; make the trivial composable react (e.g. a button that changes color on hover/press). Screenshot the hover/press state.
- [ ] **Step 2:** handle window resize (recreate the FBO/surface at the new real size); confirm no leak/crash on resize.
- [ ] **Step 3:** commit `spike(ui): compose input + resize wired`.

---

## Exit criteria (decision made)

- **GO** = Compose renders stably over the viewport across frames, input is wireable, native/classloader/GL-state issues are surmountable at acceptable complexity. → next: write the Compose-based panel-framework plan (docking built with Compose layout, `ProjectExplorerPanel` as a composable over `ProjectTreeSnapshotS2C`, `mutableStateOf` for live data).
- **NO-GO** = native/classloader won't load, GL-state coexistence corrupts MC or Skia, or the fragility/complexity is unacceptable. → next: the ImGui feasibility spike (proven path). Record the specific blocker so the decision is defensible.

## Self-Review

- **Scope:** intentionally minimal — the goal is a defensible GO/NO-GO, not a UI. Task 1 front-loads the three real risks in order (native load → GL-state coexistence → Compose scene). Task 2 is gated on GO.
- **Placeholders:** the graphics/native steps are verified by screenshots + launch/no-crash (appropriate — you can't unit-test "Skiko coexists with Blaze3D"). The version-matrix resolution is genuinely investigative and isolated to Step 1, as intended for a spike.
- **Fallback is explicit:** NO-GO routes to ImGui, so this spike cannot dead-end the project.
- **No-regression:** every Compose entry point is guarded; a failure disables Compose, never crashes vanilla.
