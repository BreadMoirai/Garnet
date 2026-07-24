# Compose Panel Framework Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full-window Compose docking framework (Left/Right/Bottom/Center regions + tab strips + drag splitters) over the proven viewport composite, ship the Project Explorer panel, and hard-cut the legacy MC-GUI screens.

**Architecture:** One full-window `ComposeScene` composes the whole window each frame; panels draw in reserved edge regions and the center is transparent so the composited live world shows through. A plain-state `DockState` (Compose snapshot state) is the single source of truth for region sizes/visibility/focus; `ViewportState` derives its shrink insets from it, so the world resizes as panels open/resize/close. GLFW pointer/key events route into the scene only while a panel is focused; everything is OFF by default until toggled.

**Tech Stack:** Kotlin 2.3.20, Compose Multiplatform 1.12.0-beta02 (compiler plugin + runtime/ui/foundation `-desktop`), Skiko 0.150.1 desktop-GL, MC 26.2 Blaze3D GPU API, Fabric Loom + Stonecutter (single node), MixinExtras.

## Global Constraints

- **Build (all 5 source sets):** `cmd.exe /c "cd /d H:\Repo\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
- **Unit tests:** `cmd.exe /c "gradlew.bat :26.1:test"` — Kotest `--tests` filter is unreliable; read `versions/26.1/build/test-results/**/*.xml`.
- **Client runtime tests:** `cmd.exe /c "gradlew.bat :26.1:runClientTest"` — runs the whole `ClientTestSentinel` suite; screenshots via `ViewportState.compositeCaptureRequest` → `versions/26.1/run/screenshots/` (controller verifies visually).
- **Gradle task prefix is `:26.1:`** (Stonecutter single node). Always invoke `gradlew.bat` via `cmd.exe /c`, never `./gradlew`.
- **Commit directly to `main`.** No feature branches/worktrees unless asked.
- **Commit attribution: NO `Co-Authored-By` / "Generated with Claude" trailer.** Attribute to the user only.
- **Dependency pins are load-bearing:** compose `1.12.0-beta02` (`-desktop` coords), skiko `0.150.1`, `kotlin("plugin.compose")` `2.3.20`. Do not bump — a skiko/compose mismatch breaks the native ABI.
- **No base UI type may be named `Component`** (collides with MC's text `Component`). Use `Panel`, `Dock`, `DockRegion`, `DockState`.
- **Everything OFF by default / byte-for-byte vanilla until toggled.** Every Skia/Compose entry point stays guarded by `ComposeSurface.disabled`; any `Throwable` disables and falls back to the plain composite, never crashing present or startup.
- **Clean-room re `../Flashback`** (all-rights-reserved): study technique only; copy no code/shaders/assets.
- **New Kotest clientTest specs MUST be registered in `ClientTestSentinel`** (autoscan is off) or they silently don't run.
- **Design spec:** `docs/superpowers/specs/2026-07-24-compose-panel-framework-design.md`.

---

## File Structure

**New (client Kotlin):**
- `client/ui/compose/dock/DockRegion.kt` — enum `LEFT/RIGHT/BOTTOM/CENTER`.
- `client/ui/compose/dock/Panel.kt` — `Panel` (id, title, `@Composable` body).
- `client/ui/compose/dock/DockState.kt` — snapshot-state source of truth.
- `client/ui/compose/dock/DockInsets.kt` — pure `DockState → insets` + region-rect math.
- `client/ui/compose/dock/RedstoneDock.kt` — `@Composable` dock root (regions, tab strips, splitters).
- `client/ui/compose/input/DockInputRouter.kt` — capture/focus state + event forwarding.
- `client/ui/compose/ComposeSceneHost.kt` — generic `ImageComposeScene` wrapper (generalizes `ComposeScenePanel`).
- `client/ide/ProjectTreeState.kt` — `mutableStateOf` Explorer client state.
- `client/ide/ProjectExplorerPanel.kt` — Explorer composable.
- `client/viewport/DockKeybinds.kt` — Alt+1/Shift+1 registration.

**New (client Java mixins):**
- `mixin/client/MouseHandlerMixin.java`, `mixin/client/KeyboardHandlerMixin.java`.

**Modified:**
- `build.gradle.kts` — Task 0 runtime scoping.
- `client/viewport/BlitUvPipeline.kt` — blended blit variant.
- `client/ui/compose/ComposeSurface.kt` — full-window, transparent clear, hosts `RedstoneDock`.
- `client/ui/compose/ComposeOverlay.kt` — full-window blended blit.
- `client/viewport/ViewportState.kt` — insets from `DockState`.
- `client/project/ProjectClientNetworking.kt` — feed `ProjectTreeState`.
- `client/project/ProjectIntegratedBoot.kt` — add `bootWorkspace()`.
- `mixin/client/TitleScreenMixin.java` — retarget button to `bootWorkspace()`.
- `client/network/ClientNetworkHandler.kt` — recorder/runner receivers → no-ops.
- `resources/redstonespecs.client.mixins.json` — register the two new mixins.
- `clientTest/.../ClientTestSentinel.kt` — register new specs, de-register deleted ones.

**Deleted:**
- `client/project/ProjectScreen.kt`, `client/project/ProjectRootListScreen.kt`,
  `client/screen/RecorderScreen.kt`, `client/screen/RunnerScreen.kt`,
  `client/screen/IntEditBox.kt`, `client/widget/TimelineSliderWidget.kt`,
  `test/.../data/IntEditBoxLogicTest.kt`,
  `clientTest/.../ProjectEntryFlowSpec.kt`, `clientTest/.../RecorderScreenSpec.kt`.

---

## Task 0: Compose runtime scoping investigation

**Files:**
- Modify: `build.gradle.kts:203` (the `implementation("org.jetbrains.compose.runtime:runtime-desktop:...")` line)
- Create (if infeasible): `docs/build/compose-runtime-scoping.md` + register in `docs/build/INDEX.md`

**Interfaces:**
- Produces: a decision — either `runtime-desktop` scoped to `clientImplementation` (build stays green), or it stays on base `implementation` with a documented rationale. No code depends on this.

This task is investigation, not TDD. The goal: try to move the Compose *runtime* off the base `implementation` (which every source set extends → server-jar bloat) onto `clientImplementation`, and see whether the project-wide Compose compiler plugin's `VersionChecker` still passes `main`/`test`/`gametest`.

- [ ] **Step 1: Move the runtime dependency to client scope**

In `build.gradle.kts`, change line 203 from:

```kotlin
    implementation("org.jetbrains.compose.runtime:runtime-desktop:1.12.0-beta02")
```
to:
```kotlin
    "clientImplementation"("org.jetbrains.compose.runtime:runtime-desktop:1.12.0-beta02")
```

- [ ] **Step 2: Build all 5 source sets and observe**

Run: `cmd.exe /c "cd /d H:\Repo\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`

Expected: **FAIL** on `compileKotlin` / `compileTestKotlin` / `compileGametestKotlin` with a Compose `VersionChecker` error ("Compose Runtime … not found on the classpath" or similar), because the compiler plugin runs on those compilations but the runtime is now client-only.

- [ ] **Step 3: Attempt to scope the compiler plugin to client compilations**

Try disabling the Compose compiler subplugin for the non-client Kotlin compile tasks. Add, near the `tasks { }` block:

```kotlin
// Compose compiler plugin is applied project-wide (plugins {}); it only needs to run on the
// compilations that contain @Composable code (client, clientTest). Attempt to strip it from the
// others so the Compose runtime need not sit on the base implementation.
listOf("compileKotlin", "compileTestKotlin", "compileGametestKotlin").forEach { name ->
    tasks.findByName(name)?.let { t ->
        (t as org.jetbrains.kotlin.gradle.tasks.KotlinCompile).pluginClasspath.setFrom(
            t.pluginClasspath.filter { !it.name.contains("compose") }
        )
    }
}
```

Run the Step 2 build again.

Expected: most likely still **FAIL** — `pluginClasspath` filtering is brittle/unsupported across Kotlin-Gradle versions, or the plugin is injected via a different mechanism. Single-module Stonecutter has no clean per-source-set compiler-plugin scope; the genuine fix is a separate Gradle UI submodule (out of scope).

- [ ] **Step 4: If it works — keep it; if not — revert and document**

If the build is green with the runtime client-scoped: remove any leftover experimental block that isn't needed, keep the `clientImplementation` line, and skip to Step 6.

If it fails: revert both edits (restore line 203 to base `implementation`, remove the Step 3 block):

```bash
git checkout build.gradle.kts
```

Then create `docs/build/compose-runtime-scoping.md`:

```markdown
---
title: Why the Compose runtime sits on the base `implementation`
tags: [gradle, compose, stonecutter, dependencies, scoping]
summary: The Compose compiler plugin is applied project-wide and its VersionChecker fails any compilation lacking the Compose runtime; single-module Stonecutter cannot scope a Kotlin compiler plugin per source set, so the runtime stays on base `implementation`.
---

# Why the Compose runtime sits on the base `implementation`

`kotlin("plugin.compose")` is applied in the root `plugins { }` block, so the Compose
compiler plugin runs on **every** `KotlinCompile` task in the (single) Gradle module —
`main`, `client`, `test`, `gametest`, `clientTest`. Its `VersionChecker` fails any
compilation that lacks the Compose *runtime* on its classpath, even ones with no
`@Composable`.

Consequently `org.jetbrains.compose.runtime:runtime-desktop` must sit on the base
`implementation` (which every source set extends), not `clientImplementation`. Only
`ui`/`foundation` (used by actual composables) stay client-scoped.

## What was tried (and failed)

- Moving `runtime-desktop` to `clientImplementation` → `main`/`test`/`gametest`
  `compileKotlin` fail the Compose `VersionChecker`.
- Stripping the compose plugin from non-client compile tasks by filtering
  `KotlinCompile.pluginClasspath` → unsupported/brittle; does not cleanly remove the
  subplugin.

## The real fix (deferred)

Move the Compose UI into its own Gradle submodule so the compiler plugin applies only
there. That is a build restructure orthogonal to the panel framework and is out of scope
for this plan. Cost of the status quo: the Compose runtime jar (~a few hundred KB, inert
without a GL context) ships in the server jar. Acceptable for now.
```

Register in `docs/build/INDEX.md` (append under Articles):

```markdown
- [Why the Compose runtime sits on the base `implementation`](compose-runtime-scoping.md) — the Compose compiler plugin is project-wide and its VersionChecker needs the runtime on every compilation; single-module Stonecutter can't scope a compiler plugin per source set. _[gradle, compose, stonecutter, dependencies, scoping]_
```

- [ ] **Step 5: Re-verify the reverted build is green**

Run the Step 2 build command. Expected: **BUILD SUCCESSFUL** (back to the known-good state).

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "build(compose): scope runtime client-only (or document why not)"
```

---

## Task 1: Blended blit + full-window transparent Compose overlay

**Files:**
- Modify: `client/viewport/BlitUvPipeline.kt`
- Modify: `client/ui/compose/ComposeSurface.kt:188` (the canvas clear) and `renderFrame` call sites
- Modify: `client/ui/compose/ComposeScenePanel.kt` (transparent background, to prove alpha)
- Modify: `client/ui/compose/ComposeOverlay.kt` (full-window blended blit)
- Test: `clientTest/.../ComposeOverlaySpec.kt` (assert world shows through the transparent surround)

**Interfaces:**
- Produces: `BlitUvPipeline.blit(from, to, x1, y1, x2, y2, flipV, blend)` — new trailing `blend: Boolean = false` param; `blend=true` uses a premultiplied-alpha pipeline (`ONE, ONE_MINUS_SRC_ALPHA`).
- Produces: `ComposeSurface.renderFrame(width, height)` now expects the **full** window size and returns a full-window texture with a transparent background.

- [ ] **Step 1: Add a premultiplied-alpha blended pipeline to `BlitUvPipeline`**

In `client/viewport/BlitUvPipeline.kt`, after the existing `PIPELINE` val, add a second pipeline. **Verify the blend API against MC 26.2** (`com.mojang.blaze3d.pipeline.RenderPipeline.Builder` + `com.mojang.blaze3d.pipeline.BlendFunction`; the decompiled sources are under the loom cache — see memory `reference_mc_sources`). Skia surfaces are **premultiplied**, so use source factor `ONE`:

```kotlin
    /** Blend variant for premultiplied-alpha sources (Skia/Compose): dst = src + dst*(1-srcA). */
    val PIPELINE_BLEND: RenderPipeline = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath(NAMESPACE, "pipeline/blit_uv_blend"))
        .withVertexShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withFragmentShader(Identifier.fromNamespaceAndPath(NAMESPACE, "core/blit_uv"))
        .withSampler("InSampler")
        .withVertexFormat(DefaultVertexFormat.POSITION_TEX, VertexFormat.Mode.QUADS)
        .withCull(false)
        .withBlend(
            com.mojang.blaze3d.pipeline.BlendFunction(
                com.mojang.blaze3d.platform.GlStateManager.SourceFactor.ONE,
                com.mojang.blaze3d.platform.GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            )
        )
        .build()
```

If `BlendFunction`'s constructor/enum path differs in 26.2, adjust to the actual signature (grep the decompiled `RenderPipeline`/`BlendFunction`); the semantics needed are premultiplied over-compositing.

- [ ] **Step 2: Add the `blend` parameter to `blit()`**

Change the `blit` signature and the `setPipeline` call in `client/viewport/BlitUvPipeline.kt`:

```kotlin
    fun blit(from: GpuTextureView, to: RenderTarget, x1: Float, y1: Float, x2: Float, y2: Float, flipV: Boolean = false, blend: Boolean = false) {
```

and inside the render pass:

```kotlin
                    pass.setPipeline(if (blend) PIPELINE_BLEND else PIPELINE)
```

(Leave the rest of the method body unchanged.)

- [ ] **Step 3: Make the Compose surface full-window with a transparent clear**

In `client/ui/compose/ComposeSurface.kt`, change the canvas clear (line ~188) from the opaque slate fill to transparent so only what Compose paints is opaque:

```kotlin
                s.canvas.clear(0x00000000)   // fully transparent; Compose paints its own opaque regions
                s.canvas.drawImage(image, 0f, 0f)
```

No other `ComposeSurface` change is needed here — it already renders whatever size it is handed; Task 3 will point it at the full window and swap the content for the dock.

- [ ] **Step 4: Make the spike panel background transparent (to prove alpha)**

In `client/ui/compose/ComposeScenePanel.kt`, change the root `Box` background from the opaque fill to transparent, keeping the text and button opaque:

```kotlin
        Box(Modifier.fillMaxSize().background(Color(0x00000000))) {
```

- [ ] **Step 5: Blit the Compose panel full-window with blending**

In `client/ui/compose/ComposeOverlay.kt`, replace the left-strip logic in `renderInto` so it renders and blits the **whole** window with `blend=true`:

```kotlin
    fun renderInto(composite: RenderTarget, realW: Int, realH: Int) {
        if (!enabled || !ViewportState.active) return
        if (realW <= 0 || realH <= 0) return
        try {
            val texture = ComposeSurface.renderFrame(realW, realH) ?: run {
                if (ComposeSurface.disabled && !loggedDisabled) {
                    loggedDisabled = true
                    logger.warn("[compose] overlay inert: ComposeSurface disabled ({})", ComposeSurface.disabledReason)
                }
                return
            }
            // Full-window, alpha-blended over the composited world. flipV=true: the Skia surface
            // (BOTTOM_LEFT origin) is stored bottom-up like MC render-target textures.
            BlitUvPipeline.blit(texture, composite, 0f, 0f, 1f, 1f, /* flipV = */ true, /* blend = */ true)
        } catch (t: Throwable) {
            if (!loggedDisabled) {
                loggedDisabled = true
                logger.error("[compose] overlay blit failed; leaving plain composite", t)
            }
        }
    }
```

- [ ] **Step 6: Update the clientTest assertion to expect world-through-transparency**

In `clientTest/.../ComposeOverlaySpec.kt`, keep the existing flow (toggle viewport + overlay, capture `compose_in_mc_scene.png`, input, off-restore). The button center now lives in full-window coords — `ComposeSurface.buttonCenter` is already panel-local == window-local, so no change to the input steps. Update only the capture rationale comment and leave the click assertion. (This spec remains the proof that Compose composites + reacts to input; Task 3 supersedes its visual content.)

No code change is strictly required if the spec already only asserts `clickCount` increment and file existence — verify that is still true after the coordinate move.

- [ ] **Step 7: Build**

Run: `cmd.exe /c "cd /d H:\Repo\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: **BUILD SUCCESSFUL**.

- [ ] **Step 8: Run the client test + screenshot checkpoint**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`
Expected: `ComposeOverlaySpec` passes; `versions/26.1/run/screenshots/compose_in_mc_scene.png` shows the **full live world composited into the center**, with the small Compose panel (title + button) opaque at top-left and **the world visible through the transparent surround** (previously the whole left strip was opaque slate). **Controller verifies this screenshot before proceeding.**

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): full-window transparent Compose overlay via premultiplied-alpha blit"
```

---

## Task 2: DockState + DockInsets; ViewportState reads insets

**Files:**
- Create: `client/ui/compose/dock/DockRegion.kt`, `client/ui/compose/dock/Panel.kt`, `client/ui/compose/dock/DockState.kt`, `client/ui/compose/dock/DockInsets.kt`
- Modify: `client/viewport/ViewportState.kt`
- Test: `clientTest/.../DockInsetsSpec.kt` + register in `ClientTestSentinel`

**Interfaces:**
- Produces: `enum class DockRegion { LEFT, RIGHT, BOTTOM, CENTER }`.
- Produces: `class Panel(val id: String, val title: String, val content: @Composable (Panel) -> Unit)`.
- Produces: `object DockState` with snapshot-state fields: `leftWidth: Int`, `rightWidth: Int`, `bottomHeight: Int`, per-region `visible: Boolean`, `panels: SnapshotStateList<Panel>` per region, `activeTab: Int` per region, `focusedRegion: DockRegion?`. Getters return current values for plain reads by `WindowMixin`/`ViewportState`.
- Produces: `data class DockInsets(val left: Int, val right: Int, val bottom: Int, val top: Int)` and `fun DockState.insets(): DockInsets`.
- Consumes (Task 3+): `DockState` mutated by dock composables/input; read by `ViewportState.contentRect`.

- [ ] **Step 1: Write the failing inset-math test**

Create `clientTest/kotlin/com/breadmoirai/redstonespecs/test/DockInsetsSpec.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.ui.compose.dock.insets
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Pure geometry of the dock: region sizes -> reserved insets -> the shrunk content rect.
 * Runs in the clientTest source set (which can see `client` classes) but touches no render
 * context, so it does not extend ClientSpec.
 */
class DockInsetsSpec : StringSpec({

    "hidden regions reserve no space" {
        DockState.reset()
        DockState.insets() shouldBe com.breadmoirai.redstonespecs.client.ui.compose.dock.DockInsets(0, 0, 0, 0)
    }

    "a visible left region reserves its width" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true)
        DockState.setSize(DockRegion.LEFT, 260)
        DockState.insets() shouldBe com.breadmoirai.redstonespecs.client.ui.compose.dock.DockInsets(260, 0, 0, 0)
    }

    "insets drive the content rect, clamped to the minimum" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 260)
        DockState.setVisible(DockRegion.BOTTOM, true); DockState.setSize(DockRegion.BOTTOM, 160)
        val rect = ViewportState.contentRect(1000, 600)
        rect.frameX shouldBe 260
        rect.frameY shouldBe 0
        rect.frameWidth shouldBe 740
        rect.frameHeight shouldBe 440
    }

    "an over-wide reservation clamps content to MIN_CONTENT_SIZE, never negative" {
        DockState.reset()
        DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 900)
        DockState.setVisible(DockRegion.RIGHT, true); DockState.setSize(DockRegion.RIGHT, 900)
        val rect = ViewportState.contentRect(1000, 600)
        (rect.frameWidth >= 64) shouldBe true
    }
})
```

- [ ] **Step 2: Register the spec and run it to confirm it fails**

Add `DockInsetsSpec::class` to the `specs = listOf(...)` in `clientTest/.../ClientTestSentinel.kt` (after `ComposeOverlaySpec::class`).

Run: `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`
Expected: **FAIL** — `DockRegion`, `DockState`, `DockInsets`, `insets` unresolved.

- [ ] **Step 3: Create `DockRegion`**

`client/ui/compose/dock/DockRegion.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.dock

/** The four dock positions. LEFT/RIGHT/BOTTOM reserve edge space; CENTER holds the live world (or an occluding panel). */
enum class DockRegion { LEFT, RIGHT, BOTTOM, CENTER }
```

- [ ] **Step 4: Create `Panel`**

`client/ui/compose/dock/Panel.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.dock

import androidx.compose.runtime.Composable

/**
 * One titled tab in a [DockRegion]. Retained across frames; its [content] pulls live state each
 * recomposition. Named `Panel` (never `Component`) to avoid colliding with MC's text Component.
 */
class Panel(
    val id: String,
    val title: String,
    val content: @Composable (Panel) -> Unit,
)
```

- [ ] **Step 5: Create `DockState`**

`client/ui/compose/dock/DockState.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.dock

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Single source of truth for the dock layout: which edge regions are visible, how big they are
 * (splitter positions), which tab is active, and which region has input focus.
 *
 * Fields are Compose **snapshot state** so [RedstoneDock] recomposes when they change, yet plain
 * reads (`.value` via the getters below) are cheap and thread-safe for [ViewportState]/`WindowMixin`
 * to consult when computing the framebuffer shrink. The geometry is authoritative *plain arithmetic*
 * updated eagerly by input handlers — never a side effect of rendering — so the shrink never waits
 * on a compose pass. See docs/superpowers/specs/2026-07-24-compose-panel-framework-design.md §2.
 */
object DockState {

    /** Default reserved sizes (px) when a region is first shown. */
    const val DEFAULT_LEFT = 260
    const val DEFAULT_RIGHT = 220
    const val DEFAULT_BOTTOM = 160

    /** Splitter clamps. */
    const val MIN_EDGE = 120
    const val MAX_EDGE = 640

    var leftVisible by mutableStateOf(false)
        private set
    var rightVisible by mutableStateOf(false)
        private set
    var bottomVisible by mutableStateOf(false)
        private set

    var leftWidth by mutableIntStateOf(DEFAULT_LEFT)
        private set
    var rightWidth by mutableIntStateOf(DEFAULT_RIGHT)
        private set
    var bottomHeight by mutableIntStateOf(DEFAULT_BOTTOM)
        private set

    val leftPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val rightPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val bottomPanels: SnapshotStateList<Panel> = mutableStateListOf()
    val centerPanels: SnapshotStateList<Panel> = mutableStateListOf()

    var leftActiveTab by mutableIntStateOf(0)
    var rightActiveTab by mutableIntStateOf(0)
    var bottomActiveTab by mutableIntStateOf(0)
    var centerActiveTab by mutableIntStateOf(0)

    /** Which region currently owns keyboard/pointer focus, or null when the game does. */
    var focusedRegion by mutableStateOf<DockRegion?>(null)

    fun panelsFor(region: DockRegion): SnapshotStateList<Panel> = when (region) {
        DockRegion.LEFT -> leftPanels
        DockRegion.RIGHT -> rightPanels
        DockRegion.BOTTOM -> bottomPanels
        DockRegion.CENTER -> centerPanels
    }

    fun isVisible(region: DockRegion): Boolean = when (region) {
        DockRegion.LEFT -> leftVisible
        DockRegion.RIGHT -> rightVisible
        DockRegion.BOTTOM -> bottomVisible
        DockRegion.CENTER -> centerPanels.isNotEmpty()
    }

    fun setVisible(region: DockRegion, visible: Boolean) {
        when (region) {
            DockRegion.LEFT -> leftVisible = visible
            DockRegion.RIGHT -> rightVisible = visible
            DockRegion.BOTTOM -> bottomVisible = visible
            DockRegion.CENTER -> {}
        }
    }

    /** Set an edge region's reserved size (width for L/R, height for B), clamped. */
    fun setSize(region: DockRegion, size: Int) {
        val clamped = size.coerceIn(MIN_EDGE, MAX_EDGE)
        when (region) {
            DockRegion.LEFT -> leftWidth = clamped
            DockRegion.RIGHT -> rightWidth = clamped
            DockRegion.BOTTOM -> bottomHeight = clamped
            DockRegion.CENTER -> {}
        }
    }

    fun toggleVisible(region: DockRegion) = setVisible(region, !isVisible(region))

    /** Test/reset hook: clears panels, hides all edges, restores default sizes and focus. */
    fun reset() {
        leftVisible = false; rightVisible = false; bottomVisible = false
        leftWidth = DEFAULT_LEFT; rightWidth = DEFAULT_RIGHT; bottomHeight = DEFAULT_BOTTOM
        leftPanels.clear(); rightPanels.clear(); bottomPanels.clear(); centerPanels.clear()
        leftActiveTab = 0; rightActiveTab = 0; bottomActiveTab = 0; centerActiveTab = 0
        focusedRegion = null
    }
}
```

- [ ] **Step 6: Create `DockInsets`**

`client/ui/compose/dock/DockInsets.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.dock

/** Reserved edge strips (real framebuffer px) the shrunk world must avoid. */
data class DockInsets(val left: Int, val right: Int, val bottom: Int, val top: Int)

/**
 * Current reserved insets derived purely from [DockState]. A hidden region reserves nothing.
 * CENTER reserves nothing here (an occupying CENTER panel occludes the world at composite time,
 * it does not shrink it).
 */
fun DockState.insets(): DockInsets = DockInsets(
    left = if (isVisible(DockRegion.LEFT)) leftWidth else 0,
    right = if (isVisible(DockRegion.RIGHT)) rightWidth else 0,
    bottom = if (isVisible(DockRegion.BOTTOM)) bottomHeight else 0,
    top = 0,
)
```

- [ ] **Step 7: Point `ViewportState.contentRect` at the insets**

In `client/viewport/ViewportState.kt`, remove the `RESERVED_LEFT`/`RESERVED_BOTTOM` constants and rewrite `contentRect` to read `DockState.insets()`:

```kotlin
    /** Content rect dimensions never shrink below this, even if the real window is tiny. */
    private const val MIN_CONTENT_SIZE = 64

    // ... realWidth / realHeight / compositeCaptureRequest unchanged ...

    fun contentRect(realW: Int, realH: Int): ContentRect {
        val insets = com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState.insets()
        val frameX = insets.left
        val frameY = insets.top
        val frameWidth = (realW - insets.left - insets.right).coerceAtLeast(MIN_CONTENT_SIZE)
        val frameHeight = (realH - insets.top - insets.bottom).coerceAtLeast(MIN_CONTENT_SIZE)
        return ContentRect(frameX, frameY, frameWidth, frameHeight)
    }
```

Keep `active`, `shouldModify()`, `realWidth`, `realHeight`, `compositeCaptureRequest`, and `ContentRect` unchanged. Update the class KDoc line that mentions "fixed strips" to "strips reserved by the dock (`DockState`)".

- [ ] **Step 8: Build + run the spec**

Run: `cmd.exe /c "cd /d H:\Repo\RedstoneSpecs && gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: **BUILD SUCCESSFUL**.

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"` and read `versions/26.1/build/test-results` — `DockInsetsSpec` passes (4 tests).

Note: existing viewport specs assumed the old 260/160 constants with the viewport merely `active`. With insets now gated on `DockState` visibility, a viewport that is `active` but has no visible regions yields a **full-size** content rect. If `ViewportCompositeSpec`/`ViewportPickingSpec` asserted a shrunk rect from `active` alone, update them to also show a region: `DockState.setVisible(DockRegion.LEFT, true); DockState.setSize(DockRegion.LEFT, 260)` (and BOTTOM 160) before the assertion, and `DockState.reset()` after. Make that edit if those specs fail.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(ui): DockState/DockInsets drive ViewportState shrink (replace fixed reserved constants)"
```

---

## Task 3: RedstoneDock composable + full-window hosting

**Files:**
- Create: `client/ui/compose/ComposeSceneHost.kt`
- Create: `client/ui/compose/dock/RedstoneDock.kt`
- Modify: `client/ui/compose/ComposeSurface.kt` (host `RedstoneDock` full-window; use `ComposeSceneHost`)
- Test: `clientTest/.../DockRenderSpec.kt` + register in `ClientTestSentinel`

**Interfaces:**
- Produces: `class ComposeSceneHost(width, height, content: @Composable () -> Unit) : AutoCloseable` with `render(nanos): Image`, `pointerMove/Press/Release(Offset)`, `sendKey(...)`, `sendChar(...)`.
- Produces: `@Composable fun RedstoneDock(realW: Int, realH: Int)` — lays out the four regions from `DockState` at real pixel sizes (Density(1f) ⇒ dp==px), transparent center.
- Consumes: `DockState`, `DockInsets` (Task 2), premultiplied blit (Task 1).

- [ ] **Step 1: Write the failing render test**

Create `clientTest/kotlin/com/breadmoirai/redstonespecs/test/DockRenderSpec.kt`:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeSurface
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.ui.compose.dock.Panel
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path

class DockRenderSpec : ClientSpec({

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue()
        return p
    }

    test("dock renders Left + Bottom regions with the world composited in the center") {
        closeClientScreen()
        waitClientTicks(2)

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("demo.left", "Left") { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF1B2433))) {
                    BasicText("LEFT PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            })
            DockState.bottomPanels.add(Panel("demo.bottom", "Bottom") { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF243044))) {
                    BasicText("BOTTOM PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setVisible(DockRegion.BOTTOM, true)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)

        val shot = capture("dock_left_bottom.png")
        // Controller verifies: LEFT strip + BOTTOM strip painted by Compose, world composited in
        // the inset center, transparent gaps show the world. (disabled={} logged for NO-GO safety.)

        runOnClient { mc ->
            ComposeOverlay.enabled = false
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
        ViewportState.active.shouldBeFalse()
    }
})
```

- [ ] **Step 2: Register + run to confirm failure**

Add `DockRenderSpec::class` to `ClientTestSentinel`. Run: `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`.
Expected: **FAIL** — `RedstoneDock`/`ComposeSceneHost` not yet wired; also `ComposeSurface` still hosts the old panel.

- [ ] **Step 3: Create the generic `ComposeSceneHost`**

`client/ui/compose/ComposeSceneHost.kt` (generalizes `ComposeScenePanel` — content is now a parameter, and it exposes key/char forwarding for Task 4):

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image

/**
 * Generic self-contained [ImageComposeScene] wrapper: composes [content] into its own raster surface
 * (no GL) at Density(1f) so dp == px, and hands back a snapshot [Image] each frame. [ComposeSurface]
 * uploads that image onto the Blaze3D FBO. See docs/ui/compose-in-mc-feasibility.md for why
 * ImageComposeScene (self-registers with GlobalSnapshotManager, avoids the scene<->snapshot race).
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeSceneHost(
    val width: Int,
    val height: Int,
    content: @Composable () -> Unit,
) : AutoCloseable {

    private val scene = ImageComposeScene(width, height, Density(1f), content = content)

    fun render(nanos: Long): Image = scene.render(nanos)

    fun pointerMove(pos: Offset) = scene.sendPointerEvent(PointerEventType.Move, pos)
    fun pointerPress(pos: Offset) = scene.sendPointerEvent(PointerEventType.Press, pos)
    fun pointerRelease(pos: Offset) = scene.sendPointerEvent(PointerEventType.Release, pos)
    fun scroll(pos: Offset, delta: Offset) = scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = delta)
    fun sendKey(event: KeyEvent): Boolean = scene.sendKeyEvent(event)

    override fun close() = scene.close()
}
```

Verify `sendPointerEvent`'s `scrollDelta` parameter name and `sendKeyEvent`'s presence against Compose 1.12 `ImageComposeScene`/`ComposeScene` (adjust if the API differs).

- [ ] **Step 4: Create `RedstoneDock`**

`client/ui/compose/dock/RedstoneDock.kt`. Uses absolute pixel offsets/sizes (Density(1f)); regions laid out around a transparent center. Splitters are thin draggable bars that write `DockState` sizes:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.dock

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val SPLITTER = 4
private val TAB_H = 18
private val PANEL_BG = Color(0xF01B2433)   // ~94% opaque slate; center stays transparent
private val TAB_BG = Color(0xFF2D6DA3)
private val TAB_BG_INACTIVE = Color(0xFF243044)
private val TEXT = Color(0xFFFFFFFF)
private val SPLITTER_COLOR = Color(0xFF10161F)

/**
 * Full-window dock. Draws the visible LEFT/RIGHT/BOTTOM regions (with tab strips and draggable
 * splitters) and any CENTER panel; everything else is transparent so the composited world shows
 * through. Sizes come from [DockState] in real pixels (the scene runs at Density(1f)).
 */
@Composable
fun RedstoneDock(realW: Int, realH: Int) {
    Box(Modifier.fillMaxSize()) {
        val left = if (DockState.isVisible(DockRegion.LEFT)) DockState.leftWidth else 0
        val right = if (DockState.isVisible(DockRegion.RIGHT)) DockState.rightWidth else 0
        val bottom = if (DockState.isVisible(DockRegion.BOTTOM)) DockState.bottomHeight else 0

        if (DockState.isVisible(DockRegion.LEFT)) {
            RegionColumn(DockRegion.LEFT, Modifier.offset(0.dp, 0.dp).width(left.dp).height((realH - bottom).dp))
            Splitter(Modifier.offset((left - SPLITTER).dp, 0.dp).width(SPLITTER.dp).height((realH - bottom).dp)) { dx ->
                DockState.setSize(DockRegion.LEFT, DockState.leftWidth + dx)
            }
        }
        if (DockState.isVisible(DockRegion.RIGHT)) {
            RegionColumn(DockRegion.RIGHT, Modifier.offset((realW - right).dp, 0.dp).width(right.dp).height((realH - bottom).dp))
            Splitter(Modifier.offset((realW - right).dp, 0.dp).width(SPLITTER.dp).height((realH - bottom).dp)) { dx ->
                DockState.setSize(DockRegion.RIGHT, DockState.rightWidth - dx)
            }
        }
        if (DockState.isVisible(DockRegion.BOTTOM)) {
            RegionColumn(DockRegion.BOTTOM, Modifier.offset(0.dp, (realH - bottom).dp).width(realW.dp).height(bottom.dp))
            Splitter(Modifier.offset(0.dp, (realH - bottom).dp).width(realW.dp).height(SPLITTER.dp)) { _, dy ->
                DockState.setSize(DockRegion.BOTTOM, DockState.bottomHeight - dy)
            }
        }
        // CENTER: only render a panel if one exists (else transparent → world shows).
        if (DockState.centerPanels.isNotEmpty()) {
            RegionColumn(DockRegion.CENTER, Modifier.offset(left.dp, 0.dp).width((realW - left - right).dp).height((realH - bottom).dp))
        }
    }
}

/** A region = a tab strip over its panels + the active panel's body. */
@Composable
private fun RegionColumn(region: DockRegion, modifier: Modifier) {
    val panels = DockState.panelsFor(region)
    if (panels.isEmpty()) return
    val active = activeTabFor(region).coerceIn(0, panels.lastIndex)
    Column(modifier.background(PANEL_BG)) {
        Row(Modifier.fillMaxWidth().height(TAB_H.dp)) {
            panels.forEachIndexed { i, p ->
                Box(
                    Modifier
                        .height(TAB_H.dp)
                        .background(if (i == active) TAB_BG else TAB_BG_INACTIVE)
                        .pointerInput(region, i) {
                            detectTapOrDown { setActiveTab(region, i) }
                        }
                        .padding(horizontal = 6.dp),
                ) {
                    BasicText(p.title, style = TextStyle(color = TEXT, fontSize = androidx.compose.ui.unit.TextUnit.Unspecified))
                }
            }
        }
        Box(Modifier.fillMaxSize()) { panels[active].content(panels[active]) }
    }
}

@Composable
private fun Splitter(modifier: Modifier, onDrag: (dx: Int, dy: Int) -> Unit) =
    Box(modifier.background(SPLITTER_COLOR).pointerInput(Unit) {
        detectDragGestures { change, drag ->
            change.consume()
            onDrag(drag.x.toInt(), drag.y.toInt())
        }
    })

// Horizontal-only splitter convenience.
@Composable
private fun Splitter(modifier: Modifier, onDragX: (dx: Int) -> Unit) =
    Splitter(modifier) { dx, _ -> onDragX(dx) }

private fun activeTabFor(region: DockRegion) = when (region) {
    DockRegion.LEFT -> DockState.leftActiveTab
    DockRegion.RIGHT -> DockState.rightActiveTab
    DockRegion.BOTTOM -> DockState.bottomActiveTab
    DockRegion.CENTER -> DockState.centerActiveTab
}

private fun setActiveTab(region: DockRegion, i: Int) {
    when (region) {
        DockRegion.LEFT -> DockState.leftActiveTab = i
        DockRegion.RIGHT -> DockState.rightActiveTab = i
        DockRegion.BOTTOM -> DockState.bottomActiveTab = i
        DockRegion.CENTER -> DockState.centerActiveTab = i
    }
}

// Minimal tap detector (foundation `clickable` also works; kept explicit for Density(1f) hit-testing).
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapOrDown(onTap: () -> Unit) {
    androidx.compose.foundation.gestures.detectTapGestures(onTap = { onTap() })
}
```

Note: the two `Splitter` overloads may collide by JVM signature; if the compiler complains, rename the horizontal-only one to `SplitterX`. Verify `detectDragGestures`/`detectTapGestures` import paths against foundation 1.12.

- [ ] **Step 5: Host `RedstoneDock` in `ComposeSurface`**

In `client/ui/compose/ComposeSurface.kt`, replace the `panel: ComposeScenePanel?` field and `ensurePanel` with a `ComposeSceneHost` that renders `RedstoneDock`. Replace the field:

```kotlin
    /** The full-window dock scene, recreated when the window size changes. */
    private var host: ComposeSceneHost? = null
```

Replace `ensurePanel` and its `renderFrame` usage:

```kotlin
    private fun ensureHost(width: Int, height: Int): ComposeSceneHost {
        host?.let { if (it.width == width && it.height == height) return it }
        host?.close()
        val h = ComposeSceneHost(width, height) {
            com.breadmoirai.redstonespecs.client.ui.compose.dock.RedstoneDock(width, height)
        }
        host = h
        logger.info("[compose] RedstoneDock scene ({}x{}) created", width, height)
        return h
    }
```

In `renderFrame`, change `val p = ensurePanel(width, height)` → `val h = ensureHost(width, height)` and `val image = p.render(...)` → `val image = h.render(System.nanoTime())`. In `releaseSurfaceOnly()` replace `panel?.close(); panel = null` with `host?.close(); host = null`. Replace the input methods (`buttonCenter`, `clickCount`, `sendPointerMove/Press/Release`, `guardedPointer`) with forwarders that Task 4 will use:

```kotlin
    fun sendPointerMove(pos: Offset) = guardedInput { host?.pointerMove(pos) }
    fun sendPointerPress(pos: Offset) = guardedInput { host?.pointerPress(pos) }
    fun sendPointerRelease(pos: Offset) = guardedInput { host?.pointerRelease(pos) }
    fun sendScroll(pos: Offset, delta: Offset) = guardedInput { host?.scroll(pos, delta) }
    fun sendKey(event: androidx.compose.ui.input.key.KeyEvent) = guardedInput { host?.sendKey(event) }

    private inline fun guardedInput(block: () -> Unit) {
        if (disabled) return
        try { block() } catch (t: Throwable) { kill("ComposeScene input dispatch failed", t) }
    }
```

Delete `ComposeScenePanel.kt` now that `ComposeSceneHost` + `RedstoneDock` replace it, and remove the `panel`-based references. (`ComposeOverlaySpec` referenced `ComposeSurface.buttonCenter`/`clickCount`; that spec is retired in Task 7 — for now, temporarily keep a no-op `buttonCenter: Offset? get() = null` and `clickCount: Int get() = 0` on `ComposeSurface` so the old spec still compiles, and delete them in Task 7.)

- [ ] **Step 6: Build**

Run the 5-source-set build. Expected: **BUILD SUCCESSFUL**. Fix any Compose import/signature drift flagged by the compiler (the layout API is the likeliest source).

- [ ] **Step 7: Run the dock render test + screenshot checkpoint**

Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"`.
Expected: `DockRenderSpec` passes; `versions/26.1/run/screenshots/dock_left_bottom.png` shows a **LEFT strip and a BOTTOM strip** painted by Compose (each with a one-tab strip + labelled body), the **live world composited into the inset center**, and the world visible in the transparent corner gap. **Controller verifies before proceeding.**

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(ui): RedstoneDock — L/R/B/Center regions, tab strips, drag splitters, full-window scene"
```

---

## Task 4: Input routing — mouse/keyboard mixins + focus keybinds

**Files:**
- Create: `client/ui/compose/input/DockInputRouter.kt`
- Create: `mixin/client/MouseHandlerMixin.java`, `mixin/client/KeyboardHandlerMixin.java`
- Create: `client/viewport/DockKeybinds.kt`
- Modify: `resources/redstonespecs.client.mixins.json` (register mixins)
- Modify: the client entrypoint that calls `registerViewportToggle()` (register `DockKeybinds`)
- Test: `clientTest/.../DockInputSpec.kt` + register in `ClientTestSentinel`

**Interfaces:**
- Produces: `object DockInputRouter { val captured: Boolean; fun focus(region: DockRegion); fun clearFocus(); fun onGlfwMove/Press/Release/Scroll(...); fun onGlfwKey/Char(...) }`.
- Consumes: `ComposeSurface.sendPointer*/sendScroll/sendKey` (Task 3), `DockState.focusedRegion` (Task 2), `Minecraft.mouseHandler` grab/release (see `CursorFocusToggle`).

- [ ] **Step 1: Write the failing input test**

Create `clientTest/.../DockInputSpec.kt`. It shows a Left panel with a Compose button whose click increments a test-visible counter, focuses via `DockInputRouter.focus(LEFT)`, drives a GLFW-style press/release through the router at the button's window coords, and asserts the counter incremented:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeSurface
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.ui.compose.dock.Panel
import com.breadmoirai.redstonespecs.client.ui.compose.input.DockInputRouter
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger

class DockInputSpec : ClientSpec({
    test("focused Left panel receives routed pointer clicks") {
        closeClientScreen(); waitClientTicks(2)
        val clicks = AtomicInteger(0)

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("demo.left", "Left") {
                Box(Modifier.size(400.dp)) {
                    Box(Modifier.offset(40.dp, 40.dp).size(80.dp).background(Color(0xFF2D6DA3))
                        .clickable { clicks.incrementAndGet() })
                }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)

        // Focus the left region, then route a click at the button centre (window coords == 80,80).
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        val before = clicks.get()
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 80.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(3)

        if (!ComposeSurface.disabled) (clicks.get() > before).shouldBeTrue()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
```

- [ ] **Step 2: Register + run to confirm failure**

Add `DockInputSpec::class` to `ClientTestSentinel`. Run `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`. Expected: **FAIL** — `DockInputRouter` unresolved.

- [ ] **Step 3: Create `DockInputRouter`**

`client/ui/compose/input/DockInputRouter.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ui.compose.input

import androidx.compose.ui.geometry.Offset
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeSurface
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import net.minecraft.client.Minecraft

/**
 * Bridges raw GLFW input (forwarded by MouseHandlerMixin/KeyboardHandlerMixin) into the full-window
 * ComposeScene, but only while a panel is [captured]. On focus we release the cursor so the OS
 * pointer is free over the panel; on unfocus we re-grab (with setIgnoreFirstMove to avoid a camera
 * jump, mirroring CursorFocusToggle). Window coords == scene coords (the scene is full-window).
 */
object DockInputRouter {

    @Volatile private var lastX = 0.0
    @Volatile private var lastY = 0.0

    /** True while the dock is eating input; the mixins consult this to cancel vanilla handling. */
    val captured: Boolean get() = DockState.focusedRegion != null

    fun focus(region: DockRegion) {
        if (DockState.focusedRegion == region) return
        DockState.focusedRegion = region
        val mc = Minecraft.getInstance()
        if (mc.screen == null) mc.mouseHandler.releaseMouse()
    }

    fun clearFocus() {
        if (DockState.focusedRegion == null) return
        DockState.focusedRegion = null
        val mc = Minecraft.getInstance()
        if (mc.screen == null) {
            mc.mouseHandler.setIgnoreFirstMove()
            mc.mouseHandler.grabMouse()
        }
    }

    fun onGlfwMove(x: Double, y: Double) {
        lastX = x; lastY = y
        if (captured) ComposeSurface.sendPointerMove(Offset(x.toFloat(), y.toFloat()))
    }

    fun onGlfwPress(button: Int) {
        if (captured) ComposeSurface.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()))
    }

    fun onGlfwRelease(button: Int) {
        if (captured) ComposeSurface.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()))
    }

    fun onGlfwScroll(dx: Double, dy: Double) {
        if (captured) ComposeSurface.sendScroll(Offset(lastX.toFloat(), lastY.toFloat()), Offset(dx.toFloat(), dy.toFloat()))
    }
}
```

- [ ] **Step 4: Build + run the router test (mixins not yet needed for this spec)**

The spec drives `DockInputRouter` directly, so it passes without the mixins. Run the 5-source-set build (expect **SUCCESS**), then `runClientTest` and confirm `DockInputSpec` passes (`clicks` incremented). If Compose reports `disabled`, the assertion is skipped — inspect logs for the NO-GO reason.

- [ ] **Step 5: Add the GLFW mouse mixin**

`mixin/client/MouseHandlerMixin.java`. **Verify method names against the decompiled `net.minecraft.client.MouseHandler` for 26.2** (`onMove(long,double,double)`, `onPress(long,int,int,int)`, `onScroll(long,double,double)` are the historical names; confirm via `javap`/sources — memory `reference_mc_sources`). Inject at HEAD, cancellable, and forward+cancel when captured:

```java
package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.ui.compose.input.DockInputRouter;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes raw cursor/button/scroll into the dock ComposeScene while a panel is focused
 * (DockInputRouter.captured), cancelling vanilla handling so the camera/hotbar do not react.
 * When not captured every injection falls through untouched — byte-for-byte vanilla input.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onMove(JDD)V", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$onMove(long window, double x, double y, CallbackInfo ci) {
        DockInputRouter.INSTANCE.onGlfwMove(x, y);
        if (DockInputRouter.INSTANCE.getCaptured()) ci.cancel();
    }

    @Inject(method = "onPress(JIII)V", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$onPress(long window, int button, int action, int mods, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        if (action == org.lwjgl.glfw.GLFW.GLFW_PRESS) DockInputRouter.INSTANCE.onGlfwPress(button);
        else if (action == org.lwjgl.glfw.GLFW.GLFW_RELEASE) DockInputRouter.INSTANCE.onGlfwRelease(button);
        ci.cancel();
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$onScroll(long window, double dx, double dy, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        DockInputRouter.INSTANCE.onGlfwScroll(dx, dy);
        ci.cancel();
    }
}
```

`DockInputRouter.INSTANCE.getCaptured()` is Kotlin's synthesized getter for the `captured` val — confirm the name (`getCaptured`) after compiling.

- [ ] **Step 6: Add the GLFW keyboard mixin**

`mixin/client/KeyboardHandlerMixin.java`. Inject into `net.minecraft.client.KeyboardHandler#keyPress(long,int,int,int,int)` and `charTyped`/`onCharEvent` (verify names). Forward to Compose and cancel when captured, **except** never swallow ESC (so the user can always drop focus):

```java
package com.breadmoirai.redstonespecs.mixin.client;

import com.breadmoirai.redstonespecs.client.ui.compose.input.DockInputRouter;
import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a dock panel is focused, cancels vanilla key handling so keystrokes do not leak into the
 * game (movement, hotbar). Actual key delivery into Compose is forwarded via DockInputRouter; ESC
 * is deliberately not swallowed so focus can always be dropped by higher-level handling.
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JIIII)V", at = @At("HEAD"), cancellable = true)
    private void redstonespecs$keyPress(long window, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (!DockInputRouter.INSTANCE.getCaptured()) return;
        if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) return; // let focus be dropped
        ci.cancel();
    }
}
```

(Full key→Compose `KeyEvent` translation is deferred; the Explorer's interactions in this plan are pointer-driven. Cancelling game keys while focused is the load-bearing behavior. Note this limitation in the docs task.)

- [ ] **Step 7: Register the mixins**

In `src/client/resources/redstonespecs.client.mixins.json` (confirm exact filename), add to the `"client"` mixin list:

```json
    "MouseHandlerMixin",
    "KeyboardHandlerMixin",
```

- [ ] **Step 8: Create the focus/toggle keybinds**

`client/viewport/DockKeybinds.kt` — Alt+1 focuses the Explorer (index 0 of LEFT), Shift+1 toggles LEFT visibility. Modifier state is read from GLFW; keybind is the `1` key:

```kotlin
package com.breadmoirai.redstonespecs.client.viewport

import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.ui.compose.input.DockInputRouter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

private const val GLFW_KEY_1 = 49

private val keyExplorerFocus = KeyMappingHelper.registerKeyMapping(
    KeyMapping("key.redstonespecs.dock_explorer_focus", GLFW_KEY_1, KeyMapping.Category.MISC)
)

/**
 * Alt+1 focuses the Explorer (releases the cursor, routes input to Compose); Shift+1 toggles the
 * LEFT region's visibility (freeing/reclaiming its inset, which resizes the world). Bound to a
 * single mapping on `1`; the Alt/Shift distinction is read from live GLFW modifier state on click.
 */
fun registerDockKeybinds() {
    ClientTickEvents.END_CLIENT_TICK.register { mc ->
        while (keyExplorerFocus.consumeClick()) {
            val handle = mc.window.window
            val shift = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS
            val alt = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS ||
                GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS
            when {
                shift -> {
                    DockState.toggleVisible(DockRegion.LEFT)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) DockInputRouter.clearFocus()
                    else { DockState.setVisible(DockRegion.LEFT, true); DockInputRouter.focus(DockRegion.LEFT) }
                }
                else -> {} // bare "1" is the vanilla hotbar slot; do nothing here
            }
        }
    }
}
```

Register it next to `registerViewportToggle()` — find that call site (grep `registerViewportToggle`) in the client entrypoint and add `registerDockKeybinds()` beside it.

- [ ] **Step 9: Build + run + screenshot checkpoint**

Run the 5-source-set build (expect **SUCCESS**). Run `runClientTest`; `DockInputSpec` passes. Controller may additionally launch `runClient` manually to eyeball Alt+1 focus/cursor-release and Shift+1 world-resize, but the automated gate is `DockInputSpec`.

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(ui): route GLFW pointer/key into the dock while focused; Alt+1 focus / Shift+1 toggle"
```

---

## Task 5: ProjectExplorerPanel + ProjectTreeState

**Files:**
- Create: `client/ide/ProjectTreeState.kt`, `client/ide/ProjectExplorerPanel.kt`
- Modify: `client/project/ProjectClientNetworking.kt` (feed `ProjectTreeState`, not `ProjectScreen`)
- Modify: the client init that seeds panels (register the Explorer `Panel` into `DockState.leftPanels`)
- Test: `clientTest/.../ProjectExplorerSpec.kt` + register in `ClientTestSentinel`

**Interfaces:**
- Produces: `object ProjectTreeState` with `snapshot: ProjectTreeSnapshotS2C?` (snapshot state), `status: String`, `fun onSnapshot(...)`, `fun onFolderLoaded(...)`, `fun onSaveReport(...)`, `fun onError(...)`.
- Produces: `fun explorerPanel(): Panel` — the Explorer tab for `DockState.leftPanels`.
- Consumes: `ProjectTreeSnapshotS2C`/`ProjectFolderLoadedS2C`/`ProjectSaveReportS2C`/`ProjectErrorS2C` (existing payloads), `ListProjectTreeC2S`/`LoadProjectFolderC2S`/`UnloadProjectFolderC2S` (existing C2S), `ClientPlayNetworking`.

- [ ] **Step 1: Write the failing Explorer test**

Create `clientTest/.../ProjectExplorerSpec.kt`: pushes a synthetic `ProjectTreeSnapshotS2C` into `ProjectTreeState`, shows the Explorer panel in LEFT, renders, and asserts the snapshot is retained + a screenshot is produced (tree rows visible):

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.client.ide.explorerPanel
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.network.project.ProjectLeafEntry
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path

class ProjectExplorerSpec : ClientSpec({
    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue(); return p
    }

    test("Explorer renders a project tree snapshot") {
        closeClientScreen(); waitClientTicks(2)
        runOnClient { mc ->
            DockState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
                leaves = listOf(ProjectLeafEntry("adders/full-adder", 3), ProjectLeafEntry("clocks/ring", 1)),
                intermediates = listOf("adders", "clocks"),
                currentSubpath = "adders/full-adder",
            ))
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true; ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        ProjectTreeState.snapshot shouldNotBe null
        capture("explorer_tree.png")   // controller verifies: tree rows for adders/, clocks/, leaves

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
```

- [ ] **Step 2: Register + run to confirm failure**

Add `ProjectExplorerSpec::class` to `ClientTestSentinel`. Run `cmd.exe /c "gradlew.bat :26.1:clientTestClasses"`. Expected: **FAIL** — `ProjectTreeState`/`explorerPanel` unresolved.

- [ ] **Step 3: Create `ProjectTreeState`**

`client/ide/ProjectTreeState.kt`:

```kotlin
package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C

/**
 * Client-side, Compose-observable state for the Project Explorer. The networking layer mutates it
 * from the client thread; [ProjectExplorerPanel] reads it during composition and recomposes on
 * change. Replaces the old ProjectScreen-as-state-holder model (hard-cut).
 */
object ProjectTreeState {
    var snapshot by mutableStateOf<ProjectTreeSnapshotS2C?>(null)
        private set
    var status by mutableStateOf("")
        private set
    /** Subpaths the user has expanded in the tree. */
    val expanded = androidx.compose.runtime.mutableStateListOf<String>()

    fun onSnapshot(s: ProjectTreeSnapshotS2C) { snapshot = s }

    fun onFolderLoaded(p: ProjectFolderLoadedS2C) {
        val errs = p.parseErrors.size + p.layoutErrors.size
        status = if (errs == 0) "loaded ${p.subpath} (${p.loadedSpecIds.size} specs)"
                 else "loaded ${p.subpath} with $errs error(s)"
    }

    fun onSaveReport(r: ProjectSaveReportS2C) { status = "saved ${r.perSpec.size} spec(s)" }
    fun onError(e: ProjectErrorS2C) { status = "error: ${e.reason}" }

    fun toggleExpanded(subpath: String) {
        if (!expanded.remove(subpath)) expanded.add(subpath)
    }
}
```

- [ ] **Step 4: Create `ProjectExplorerPanel`**

`client/ide/ProjectExplorerPanel.kt` — a composable tree over the snapshot; clicking a leaf sends `LoadProjectFolderC2S`; a refresh row sends `ListProjectTreeC2S`:

```kotlin
package com.breadmoirai.redstonespecs.client.ide

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.breadmoirai.redstonespecs.client.ui.compose.dock.Panel
import com.breadmoirai.redstonespecs.network.project.ListProjectTreeC2S
import com.breadmoirai.redstonespecs.network.project.LoadProjectFolderC2S
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

private val TEXT = Color(0xFFDDE3EC)
private val TEXT_DIM = Color(0xFF8FA0B5)

/** The Explorer tab for DockState.leftPanels. */
fun explorerPanel(): Panel = Panel("redstonespecs.explorer", "Explorer") { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    Column(Modifier.fillMaxSize().padding(4.dp)) {
        Row2("↻ Refresh", TEXT_DIM) { ClientPlayNetworking.send(ListProjectTreeC2S.INSTANCE) }
        val snap = ProjectTreeState.snapshot
        if (snap == null) {
            Row2("(no project loaded — Refresh)", TEXT_DIM) {}
        } else {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                snap.intermediates.sorted().forEach { dir -> Row2("▸ $dir", TEXT_DIM) { ProjectTreeState.toggleExpanded(dir) } }
                snap.leaves.sortedBy { it.subpath }.forEach { leaf ->
                    val marker = if (leaf.subpath == snap.currentSubpath) "● " else "  "
                    Row2("$marker${leaf.subpath}  (${leaf.specCount})", TEXT) {
                        ClientPlayNetworking.send(LoadProjectFolderC2S(leaf.subpath))
                    }
                }
            }
        }
        val status = ProjectTreeState.status
        if (status.isNotEmpty()) BasicText(status, Modifier.padding(top = 4.dp), style = TextStyle(color = TEXT_DIM))
    }
}

@Composable
private fun Row2(label: String, color: Color, onClick: () -> Unit) =
    Box(Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 2.dp)) {
        BasicText(label, style = TextStyle(color = color))
    }
```

Verify `verticalScroll`/`rememberScrollState` import paths against foundation 1.12.

- [ ] **Step 5: Feed `ProjectTreeState` from networking**

Rewrite `client/project/ProjectClientNetworking.kt` to update `ProjectTreeState` instead of `ProjectScreen`:

```kotlin
package com.breadmoirai.redstonespecs.client.project

import com.breadmoirai.redstonespecs.client.ide.ProjectTreeState
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object ProjectClientNetworking {
    fun register() {
        ClientPlayNetworking.registerGlobalReceiver(ProjectTreeSnapshotS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onSnapshot(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectFolderLoadedS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onFolderLoaded(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectSaveReportS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onSaveReport(payload) }
        }
        ClientPlayNetworking.registerGlobalReceiver(ProjectErrorS2C.TYPE) { payload, ctx ->
            ctx.client().execute { ProjectTreeState.onError(payload) }
        }
    }
}
```

- [ ] **Step 6: Seed the Explorer panel at client init**

Find the client entrypoint (grep for `ProjectClientNetworking.register()` / `registerViewportToggle()`), and after it, seed the Explorer into the dock once:

```kotlin
        com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState.leftPanels
            .add(com.breadmoirai.redstonespecs.client.ide.explorerPanel())
```

Leave LEFT hidden by default (Shift+1 reveals it) so nothing renders until toggled.

- [ ] **Step 7: Build + run + screenshot checkpoint**

Run the 5-source-set build (**SUCCESS**). Run `runClientTest`; `ProjectExplorerSpec` passes and `explorer_tree.png` shows the tree rows (`adders`, `clocks`, the two leaves with counts, the current-subpath marker). **Controller verifies.**

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(ide): ProjectExplorer panel over ProjectTreeSnapshot; networking feeds ProjectTreeState"
```

---

## Task 6: Project entry — void workspace boot from the title button

**Files:**
- Modify: `client/project/ProjectIntegratedBoot.kt` (add `bootWorkspace()`)
- Modify: `mixin/client/TitleScreenMixin.java` (retarget the button)

**Interfaces:**
- Produces: `ProjectIntegratedBoot.bootWorkspace()` — opens/creates a fixed-name flat-void singleplayer save, root-agnostic.
- Consumes: existing `openOrCreateWorld` machinery.

- [ ] **Step 1: Add `bootWorkspace()` to `ProjectIntegratedBoot`**

In `client/project/ProjectIntegratedBoot.kt`, add a root-agnostic entry that reuses the existing world open/create path with a fixed save name (do not pin a root — in-world load/unload resolves the root via config fallback in `ProjectNetworkRegistry.rootFor`):

```kotlin
    /** Fixed workspace save the main-menu button boots into; project folders are loaded/unloaded in-world. */
    private const val WORKSPACE_SAVE = "redstonespecs-workspace"

    /** Boots (opens or creates) the shared flat-void workspace world, without pinning a project root. */
    fun bootWorkspace() {
        ensureListenersRegistered()
        openOrCreateWorld(WORKSPACE_SAVE)
    }
```

(`openOrCreateWorld` is already private in this object; `ensureListenersRegistered` stays a no-op harmlessly since no root is pending.)

- [ ] **Step 2: Retarget the title-screen button**

In `mixin/client/TitleScreenMixin.java`, change the button's click handler from opening `ProjectRootListScreen` to `bootWorkspace()`, and drop the `ProjectRootListScreen` import:

```java
        RedstoneIconButton button = new RedstoneIconButton(
                this.width / 2 + 104, topPos, 20, label,
                b -> com.breadmoirai.redstonespecs.client.project.ProjectIntegratedBoot.INSTANCE.bootWorkspace()
        );
```

Update the class Javadoc to say the button "boots the shared flat-void workspace world" instead of "opens ProjectRootListScreen".

- [ ] **Step 3: Build**

Run the 5-source-set build. Expected: **SUCCESS** (`ProjectRootListScreen` is still present and compiles; it becomes unreferenced from `main`/`client` runtime except the clientTest deleted in Task 7).

- [ ] **Step 4: Manual smoke (controller)**

Controller runs `cmd.exe /c "gradlew.bat :26.1:runClient"`, clicks "Redstone Projects…" on the title screen, and confirms it boots into a flat-void world (no `ProjectRootListScreen`). Automated coverage is indirect; this is a controller eyeball.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(project): title button boots the flat-void workspace world directly"
```

---

## Task 7: Hard-cut legacy screens + test cleanup

**Files:**
- Delete: `client/project/ProjectScreen.kt`, `client/project/ProjectRootListScreen.kt`, `client/screen/RecorderScreen.kt`, `client/screen/RunnerScreen.kt`, `client/screen/IntEditBox.kt`, `client/widget/TimelineSliderWidget.kt`, `test/.../data/IntEditBoxLogicTest.kt`, `clientTest/.../ProjectEntryFlowSpec.kt`, `clientTest/.../RecorderScreenSpec.kt`
- Modify: `client/network/ClientNetworkHandler.kt`, `clientTest/.../ClientTestSentinel.kt`, `clientTest/.../ClientNetworkSpec.kt` (if it references `RunnerScreen`), `client/ui/compose/ComposeSurface.kt` (drop the temporary `buttonCenter`/`clickCount` shims)
- Delete or retire: `clientTest/.../ComposeOverlaySpec.kt` (superseded by `DockRenderSpec`)

**Interfaces:** none produced; this removes dead surface.

- [ ] **Step 1: Neutralize recorder/runner receivers**

In `client/network/ClientNetworkHandler.kt`, replace the `OpenRecorderScreenS2C`, `OpenRunnerScreenS2C`, and `RunnerStatusS2C` receiver bodies with logged no-ops, and remove the `RecorderScreen`/`RunnerScreen` imports:

```kotlin
    ClientPlayNetworking.registerGlobalReceiver(OpenRecorderScreenS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.info("[ClientNetworkHandler] recorder UI removed (returns as a panel in sub-project A/B); ignoring open for {}", payload.originPos)
        }
    }
    ClientPlayNetworking.registerGlobalReceiver(OpenRunnerScreenS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.info("[ClientNetworkHandler] runner UI removed (returns as a panel in sub-project A/B); ignoring open for {}", payload.originPos)
        }
    }
    ClientPlayNetworking.registerGlobalReceiver(RunnerStatusS2C.TYPE) { payload, context ->
        context.client().execute {
            LOGGER.debug("[ClientNetworkHandler] runner status (no UI): state={} summary={}", payload.state, payload.summary)
        }
    }
```

Keep the `OverwritePromptS2CPayload` receiver as-is (it uses vanilla `ConfirmScreen`, not a cut screen).

- [ ] **Step 2: Drop the temporary ComposeSurface shims**

In `client/ui/compose/ComposeSurface.kt`, delete the temporary `buttonCenter`/`clickCount` no-op getters added in Task 3 (nothing references them once `ComposeOverlaySpec` is retired).

- [ ] **Step 3: Delete the dead files**

```bash
git rm src/client/kotlin/com/breadmoirai/redstonespecs/client/project/ProjectScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/project/ProjectRootListScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/TimelineSliderWidget.kt \
       src/test/kotlin/com/breadmoirai/redstonespecs/data/IntEditBoxLogicTest.kt \
       src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ProjectEntryFlowSpec.kt \
       src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt \
       src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ComposeOverlaySpec.kt
```

(If `RedstoneIconButton` is now used only by `TitleScreenMixin`, keep it — it is not dead.)

- [ ] **Step 4: De-register deleted specs; keep new ones**

In `clientTest/.../ClientTestSentinel.kt`, remove `RecorderScreenSpec::class`, `ProjectEntryFlowSpec::class`, and `ComposeOverlaySpec::class` from the `specs = listOf(...)`, and confirm `DockInsetsSpec`, `DockRenderSpec`, `DockInputSpec`, `ProjectExplorerSpec` are present.

- [ ] **Step 5: Scrub remaining references**

Run:
```bash
grep -rn "RunnerScreen\|RecorderScreen\|ProjectScreen\|ProjectRootListScreen\|TimelineSliderWidget\|IntEditBox" src/client src/clientTest src/main
```
Expected: **zero** hits except `RedstoneIconButton` (kept) and any comment mentions. If `ClientNetworkSpec.kt` references `RunnerScreen`/`RunnerScreen.active`, rewrite that portion to assert on the no-op path (e.g. that receiving `OpenRunnerScreenS2C` does not open a screen) or delete the obsolete assertions.

- [ ] **Step 6: Build all 5 source sets**

Run the 5-source-set build. Expected: **BUILD SUCCESSFUL** with no unresolved references.

- [ ] **Step 7: Run unit + client tests**

Run: `cmd.exe /c "gradlew.bat :26.1:test"` — read `versions/26.1/build/test-results`; expect green (the deleted `IntEditBoxLogicTest` no longer runs; nothing else should regress).
Run: `cmd.exe /c "gradlew.bat :26.1:runClientTest"` — the dock/explorer specs pass; no spec references a deleted screen.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: hard-cut legacy MC-GUI screens (recorder/runner/project) + orphan widgets + dead specs"
```

---

## Task 8: Documentation rewrite

**Files:**
- Rewrite: `docs/ui/INDEX.md`
- Create: `docs/ui/compose-dock-framework.md`
- Modify/absorb: `docs/ui/dropdown-host-popup-stratum.md` (fold its lesson into the overlay note or delete if fully obsolete), keep `docs/ui/argb-color-pitfalls.md` (still applies to Compose `Color` alpha) and `docs/ui/compose-in-mc-feasibility.md`
- Create: `docs/architecture/shrink-viewport-compose-model.md` + register in `docs/architecture/INDEX.md`
- Modify: `docs/use-cases/INDEX.md` (drop `recording.md`/`running.md` entries), delete those two use-case files if present; update `docs/use-cases/command.md` and `cross-cutting.md` references
- Modify: `docs/minecraft/blaze3d-custom-blit-pipeline-26.md` (document the blended variant)

**Interfaces:** none (docs only).

- [ ] **Step 1: Write the dock framework article**

Create `docs/ui/compose-dock-framework.md` with frontmatter and sections covering: the full-window transparent-center scene; `DockState` as authoritative plain state driving both recomposition and `ViewportState` insets; regions/tabs/splitters; input routing (mouse/keyboard mixins gated on `DockInputRouter.captured`, Alt+1 focus / Shift+1 toggle, and the **current limitation** that key→Compose delivery is deferred — only game-key cancellation is wired); the guard/OFF-by-default invariant. Cite files by role, not line number.

- [ ] **Step 2: Write the architecture note**

Create `docs/architecture/shrink-viewport-compose-model.md`: how `WindowMixin` shrink + `MinecraftPresentMixin` composite + full-window blended Compose overlay compose one frame, and the frame-ordering guarantee (insets are plain state, never gated on a compose pass). Register it in `docs/architecture/INDEX.md`.

- [ ] **Step 3: Update the blit pipeline doc**

In `docs/minecraft/blaze3d-custom-blit-pipeline-26.md`, add a short section on the `PIPELINE_BLEND` premultiplied-alpha variant and why the Compose overlay needs it (transparent center over the composited world).

- [ ] **Step 4: Rewrite the UI INDEX**

Update `docs/ui/INDEX.md`: replace the "two live screens (Recorder/Runner)" preamble with the Compose dock model; remove entries for deleted widgets (`intstepper-vs-inteditbox.md` if it only documented the now-deleted `IntEditBox` — delete that article too, or generalize its extract-render lesson into the dock article); add the `compose-dock-framework.md` entry; keep the ARGB and compose-feasibility entries.

- [ ] **Step 5: Drop recording/running use-cases**

In `docs/use-cases/`, delete `recording.md` and `running.md` if they exist; remove their `INDEX.md` entries; grep `docs/` for links to them and fix (`grep -rn "recording.md\|running.md" docs/`). Update `command.md` (the `/redstonespecs project` entry now boots the workspace world + in-world Explorer) and any `cross-cutting.md` references to the dropped journeys.

- [ ] **Step 6: Verify doc cross-references resolve**

Run:
```bash
grep -rn "RecorderScreen\|RunnerScreen\|ProjectScreen\|ProjectRootListScreen\|RESERVED_LEFT\|RESERVED_BOTTOM" docs/
```
Expected: no hits that describe these as current behavior (historical `specs/`/`plans/` snapshots are exempt per CLAUDE.md). Fix any stale citation. Confirm every `INDEX.md` link resolves to a real file.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "docs(ui): document the Compose dock framework; drop recorder/runner UI docs; blended blit note"
```

---

## Self-Review Notes (for the executor)

- **Spec coverage:** Task 0 → §4 scoping; Task 1 → §1 blended full-window overlay; Task 2 → §2 insets; Task 3 → §3.1 dock; Task 4 → §3.2 input; Task 5 → §3.3 Explorer; Task 6 → §5 entry; Task 7 → §6 hard-cut; Task 8 → §6 docs. §7 error-handling is folded into Tasks 2 (clamp), 3 (guards), 4 (focus re-grab). §8 testing is the per-task clientTest gates. §9 non-goals are respected (no invalidation-gating, no cross-platform, no key→Compose translation).
- **Version-sensitive APIs to verify while implementing** (not placeholders — confirm the exact signature against MC 26.2 decompiled sources / Compose 1.12 artifacts before trusting the code as written): `RenderPipeline.Builder.withBlend` + `BlendFunction` (Task 1); `MouseHandler.onMove/onPress/onScroll` and `KeyboardHandler.keyPress` descriptors (Task 4); Compose `ImageComposeScene.sendPointerEvent(scrollDelta=)`/`sendKeyEvent` and foundation `detectDragGestures/detectTapGestures/verticalScroll` import paths (Tasks 3–5). Each such task's build step will surface a mismatch immediately.
- **Type consistency:** `DockState.setSize/setVisible/isVisible/panelsFor`, `DockInsets(left,right,bottom,top)`, `Panel(id,title,content)`, `ComposeSceneHost.render/pointer*/scroll/sendKey`, `DockInputRouter.captured/focus/clearFocus/onGlfw*`, `ProjectTreeState.onSnapshot/onFolderLoaded/onSaveReport/onError/snapshot/status`, `explorerPanel()`, `ProjectIntegratedBoot.bootWorkspace()` — used identically across tasks.
- **Fix-ups the plan flags inline:** the two `Splitter` overloads (rename to `SplitterX` if signatures clash); the temporary `ComposeSurface.buttonCenter/clickCount` shims (added Task 3, removed Task 7).
