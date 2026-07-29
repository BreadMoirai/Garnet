# Dock World-Session Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When the client disconnects from a world, the Compose dock closes — regions hidden, CENTER panels cleared, input focus dropped, viewport shrink released — while splitter sizes and edge panel registrations survive for the next world.

**Architecture:** Two pieces. A new pure-state `DockState.closeAll()` that changes only what is world-scoped (visibility, focus, CENTER documents) and makes no `Minecraft` calls, so it is unit-testable. Then a `ClientPlayConnectionEvents.DISCONNECT` listener that calls it, re-derives the viewport from `DockState.anyActive()`, and forces a framebuffer resize — plus an `mc.level == null` guard on the dock keybinds so the dock cannot be re-opened on the title screen.

**Tech Stack:** Kotlin, Fabric (MC 26.2), Compose Multiplatform snapshot state, Kotest (`StringSpec`) driven by the in-game `ClientTestSentinel`.

**Spec:** `docs/superpowers/specs/2026-07-28-dock-world-session-lifecycle-design.md`

## Global Constraints

- Single Minecraft slice: **26.2**. Gradle task paths are `:26.2:<task>`, never `:versions:26.2:<task>`.
- Always invoke Gradle as `cmd.exe /c "gradlew.bat ..."` from the repo root — no `./` prefix (cmd.exe cannot parse it).
- Compile verification runs all five source sets: `clientClasses classes gametestClasses clientTestClasses testClasses`.
- Kotest specs are **not** autoscanned. A new clientTest spec must be listed in `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` or it silently does not run.
- Gradle's `--tests` filter does not work with this Kotest harness. Run the task unfiltered and read the XML report under `build/reports/garnet/clientTest`.
- `git commit` messages carry **no** `Co-Authored-By` or "Generated with Claude Code" trailer. Work lands directly on `main`.
- After any source change, audit `docs/` per `CLAUDE.md` before claiming completion. Task 2 carries the doc update for this plan.

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/DockState.kt` | Dock layout single source of truth | Add `closeAll()` next to `reset()` |
| `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockLifecycleSpec.kt` | Unit test for `closeAll()` semantics | Create |
| `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt` | Explicit clientTest spec registry | Register `DockLifecycleSpec` |
| `src/client/kotlin/com/breadmoirai/garnet/client/viewport/DockKeybinds.kt` | Dock keybinds + viewport sync | Add `registerDockWorldLifecycle()`; guard keybinds on `mc.level` |
| `src/client/kotlin/com/breadmoirai/garnet/client/GarnetClient.kt` | Client entrypoint | Call `registerDockWorldLifecycle()` |
| `docs/ui/dock-framework.md` | Dock framework article | Add "World-session lifecycle" section |
| `docs/ui/INDEX.md` | UI category index | Extend the dock-framework summary |

---

### Task 1: `DockState.closeAll()` and its spec

Adds the pure-state teardown method and the test that pins its semantics. No wiring yet — after this task nothing calls `closeAll()` in production, which is intentional; Task 2 wires it.

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockLifecycleSpec.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/DockState.kt` (add a method after `reset()`, around line 155)
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt:70-86` (spec list)

**Interfaces:**
- Consumes: existing `DockState` API — `setVisible(DockRegion, Boolean)`, `setSize(DockRegion, Int)`, `isVisible(DockRegion)`, `mountEpoch(DockRegion): Int`, `anyActive(): Boolean`, `reset()`, the `leftPanels`/`rightPanels`/`bottomPanels`/`centerPanels` `SnapshotStateList<Panel>`s, and `Panel(id: String, title: String, content: @Composable (Panel) -> Unit)`.
- Produces: `fun DockState.closeAll()` — `Unit`, no parameters, idempotent. Task 2 calls it from the disconnect listener.

- [ ] **Step 1: Write the failing test**

Create `src/clientTest/kotlin/com/breadmoirai/garnet/test/DockLifecycleSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * World-session teardown of the dock. `closeAll()` touches only snapshot state and never calls into
 * `Minecraft`, so — like DockInsetsSpec — this needs no render context and is a plain StringSpec.
 */
class DockLifecycleSpec : StringSpec({

    fun seedOpenDock() {
        DockState.reset()
        DockState.leftPanels.add(Panel("test.left", "Left") {})
        DockState.centerPanels.add(Panel("test.center", "Center") {})
        DockState.setVisible(DockRegion.LEFT, true)
        DockState.setVisible(DockRegion.RIGHT, true)
        DockState.setVisible(DockRegion.BOTTOM, true)
        DockState.setSize(DockRegion.LEFT, 320)
        DockState.focusedRegion = DockRegion.LEFT
    }

    "closeAll hides every edge region, clears CENTER, and drops focus" {
        seedOpenDock()
        DockState.closeAll()
        DockState.isVisible(DockRegion.LEFT) shouldBe false
        DockState.isVisible(DockRegion.RIGHT) shouldBe false
        DockState.isVisible(DockRegion.BOTTOM) shouldBe false
        DockState.centerPanels.isEmpty() shouldBe true
        DockState.centerActiveTab shouldBe 0
        DockState.focusedRegion shouldBe null
        DockState.anyActive() shouldBe false
    }

    "closeAll keeps splitter sizes and edge panel registrations" {
        seedOpenDock()
        DockState.closeAll()
        DockState.leftWidth shouldBe 320
        DockState.leftPanels.size shouldBe 1
        DockState.leftPanels[0].id shouldBe "test.left"
    }

    "closeAll bumps the mount epoch of every region it tore down" {
        seedOpenDock()
        val before = DockRegion.entries.associateWith { DockState.mountEpoch(it) }
        DockState.closeAll()
        DockRegion.entries.forEach { region ->
            (DockState.mountEpoch(region) > before.getValue(region)) shouldBe true
        }
    }

    "closeAll is idempotent — a second call changes nothing" {
        seedOpenDock()
        DockState.closeAll()
        val epochs = DockRegion.entries.associateWith { DockState.mountEpoch(it) }
        DockState.closeAll()
        DockRegion.entries.forEach { region ->
            DockState.mountEpoch(region) shouldBe epochs.getValue(region)
        }
        DockState.anyActive() shouldBe false
    }
})
```

- [ ] **Step 2: Register the spec in the sentinel**

In `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`, add `DockLifecycleSpec::class,` to the `specs = listOf(...)` block, immediately after the `DockInputSpec::class,` line:

```kotlin
                        DockInputSpec::class,
                        DockLifecycleSpec::class,
```

- [ ] **Step 3: Run compilation to verify the test fails to compile**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"`
Expected: FAIL — `Unresolved reference: closeAll` in `DockLifecycleSpec.kt`. (A compile failure is this harness's version of a red test: `closeAll` does not exist yet.)

- [ ] **Step 4: Write the implementation**

In `src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/DockState.kt`, add this method immediately after `reset()` (before the closing brace of `object DockState`):

```kotlin
    /**
     * Ends the dock's **world session**: hides every edge region, clears the CENTER documents, and
     * drops input focus. Called when the client disconnects (see `registerDockWorldLifecycle` in
     * `viewport/DockKeybinds.kt`).
     *
     * Deliberately narrower than [reset]. The panel lists and splitter sizes are user *layout*, not
     * world state — and the Project Explorer is only ever added at `onInitializeClient`, so a full
     * [reset] here would leave LEFT permanently empty for the rest of the process. CENTER *is*
     * cleared: its panels are per-world documents that mean nothing without the session that opened
     * them. [setVisible] already bumps a hidden region's mount epoch; CENTER never goes through it,
     * so its epoch is bumped here (see [mountEpochs] for the ghost-popup failure mode).
     *
     * [focusedRegion] is cleared directly instead of via `DockInputRouter.clearFocus()`: that helper
     * re-grabs the mouse when no [net.minecraft.client.gui.screens.Screen] is open, and at disconnect
     * time the title screen is not reliably installed yet, so it would capture the cursor on the
     * title screen. `DockInputRouter.captured` reads through to this field, so clearing it is enough
     * to stop the input mixins. Keeping this method free of `Minecraft` calls also keeps it testable.
     *
     * Idempotent: calling it on an already-closed dock changes nothing.
     */
    fun closeAll() {
        setVisible(DockRegion.LEFT, false)
        setVisible(DockRegion.RIGHT, false)
        setVisible(DockRegion.BOTTOM, false)
        if (centerPanels.isNotEmpty()) {
            centerPanels.clear()
            bumpMountEpoch(DockRegion.CENTER)
        }
        centerActiveTab = 0
        focusedRegion = null
    }
```

- [ ] **Step 5: Run the tests**

Run: `cmd.exe /c "gradlew.bat :26.2:clientTestClasses"` then `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: compile PASSES; the client-test run completes and `build/reports/garnet/clientTest` contains a `DockLifecycleSpec` XML report with 4 tests, 0 failures. Do not use `--tests` — the filter does not work with this harness and reports a false "No tests found".

Note on the epoch assertion in test 3: `seedOpenDock` makes LEFT/RIGHT/BOTTOM visible so `setVisible(false)` bumps each, and puts a panel in CENTER so the explicit CENTER bump fires. All four regions therefore advance.

- [ ] **Step 6: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/ui/compose/dock/DockState.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/DockLifecycleSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "feat(dock): add DockState.closeAll() for world-session teardown"
```

---

### Task 2: Disconnect listener, title-screen keybind guard, and docs

Wires `closeAll()` to the real world-close event and stops the dock being re-opened without a world. Ships with the doc update, since this is the task that makes the behavior observable.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/viewport/DockKeybinds.kt` (add `registerDockWorldLifecycle()`; guard the `when` in `registerDockKeybinds`, lines 45-61)
- Modify: `src/client/kotlin/com/breadmoirai/garnet/client/GarnetClient.kt:27` (call the new registrar)
- Modify: `docs/ui/dock-framework.md` (new section)
- Modify: `docs/ui/INDEX.md` (dock-framework summary line)

**Interfaces:**
- Consumes: `DockState.closeAll()` from Task 1; existing `syncDockViewport()` (`Unit`, no args, in this same file) and `WindowViewportExt.garnet$updateScaledFramebuffer(Boolean)`.
- Produces: `fun registerDockWorldLifecycle()` — `Unit`, no parameters, called once from `GarnetClient.onInitializeClient`.

- [ ] **Step 1: Add the disconnect listener**

In `src/client/kotlin/com/breadmoirai/garnet/client/viewport/DockKeybinds.kt`, add the import:

```kotlin
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
```

and append this function at the end of the file:

```kotlin
/**
 * Closes the dock when the client leaves a world. `DISCONNECT` covers every exit path that matters:
 * quit-to-title from singleplayer, a multiplayer disconnect, and a server kick. Without this the
 * dock keeps painting over the title screen and the viewport stays shrunk, because [DockState] is a
 * client-lifetime singleton with no notion of a world.
 *
 * The `garnet$updateScaledFramebuffer(true)` follow-up mirrors both keybind branches above: without
 * it the shrink survives until something else resizes the framebuffer.
 */
fun registerDockWorldLifecycle() {
    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        DockState.closeAll()
        syncDockViewport()
        (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
    }
}
```

- [ ] **Step 2: Guard the keybinds on a live world**

In the same file, replace the `when` block inside `registerDockKeybinds` (currently lines 45-61) with:

```kotlin
            when {
                // No world: the dock's panels describe a session that does not exist. The click is
                // still consumed above so presses do not stack up and fire on the next world join.
                mc.level == null -> {}
                shift -> {
                    DockState.toggleVisible(DockRegion.LEFT)
                    if (!DockState.isVisible(DockRegion.LEFT) && DockState.focusedRegion == DockRegion.LEFT) {
                        DockInputRouter.clearFocus()
                    }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
                alt -> {
                    if (DockState.focusedRegion == DockRegion.LEFT) DockInputRouter.clearFocus()
                    else { DockState.setVisible(DockRegion.LEFT, true); DockInputRouter.focus(DockRegion.LEFT) }
                    syncDockViewport()
                    (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                }
                else -> {} // bare "1" is the vanilla hotbar slot; do nothing here
            }
```

- [ ] **Step 3: Register it from the client entrypoint**

In `src/client/kotlin/com/breadmoirai/garnet/client/GarnetClient.kt`, add the import beside the existing viewport imports:

```kotlin
import com.breadmoirai.garnet.client.viewport.registerDockWorldLifecycle
```

and add the call immediately after `registerDockKeybinds()`:

```kotlin
        registerDockKeybinds()
        registerDockWorldLifecycle()
```

- [ ] **Step 4: Compile all five source sets**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
Expected: BUILD SUCCESSFUL. If `ClientPlayConnectionEvents` does not resolve, the import is wrong — it lives in `net.fabricmc.fabric.api.client.networking.v1`; check how `ProjectClientNetworking` imports Fabric networking API classes and match that package root.

- [ ] **Step 5: Re-run the client tests**

Run: `cmd.exe /c "gradlew.bat :26.2:runClientTest"`
Expected: the run completes; `build/reports/garnet/clientTest` shows 0 failures across every registered spec — in particular `DockLifecycleSpec`, `DockInputSpec`, and `DockRenderSpec`, which share `DockState`.

- [ ] **Step 6: Update the docs**

In `docs/ui/dock-framework.md`, add a section (place it after the panel-lifecycle / mount-epoch discussion it builds on):

```markdown
## World-session lifecycle

`DockState` is a client-lifetime singleton — the Project Explorer is seeded once in
`GarnetClient.onInitializeClient` and never re-added — but its *visibility* is world-scoped.
`registerDockWorldLifecycle()` (`viewport/DockKeybinds.kt`) hooks
`ClientPlayConnectionEvents.DISCONNECT` and calls `DockState.closeAll()`, then `syncDockViewport()`
and `garnet$updateScaledFramebuffer(true)`. Without it the dock keeps painting over the title screen,
the viewport stays shrunk, and a focused region keeps eating GLFW input through the mixins.

`closeAll()` is deliberately narrower than `reset()`:

| Dropped on disconnect | Kept |
|---|---|
| Region visibility (LEFT/RIGHT/BOTTOM hidden) | Splitter sizes (`leftWidth`, …) |
| CENTER panels — per-world documents | LEFT/RIGHT/BOTTOM panel registrations |
| Input focus (`focusedRegion`) | |

A full `reset()` would clear `leftPanels`, and since the Explorer is only added at client init that
would leave LEFT permanently empty for the rest of the process.

Two non-obvious details. `closeAll()` bumps CENTER's mount epoch by hand — `setVisible` covers the
edges, but CENTER's visibility is derived from `centerPanels.isNotEmpty()` and never goes through it,
so without the explicit bump a popup opened in a center panel could outlive the world. And it clears
`focusedRegion` directly rather than calling `DockInputRouter.clearFocus()`: that helper re-grabs the
mouse when no `Screen` is open, and at `DISCONNECT` time the title screen is not reliably installed
yet, so it would capture the cursor on the title screen. `DockInputRouter.captured` reads through to
the field, so clearing it is sufficient.

The `Alt+1` / `Shift+1` keybinds are likewise no-ops while `mc.level == null`, so the dock cannot be
re-opened from the title screen. The click is still consumed so presses do not fire on the next join.
```

Then in `docs/ui/INDEX.md`, extend the dock-framework entry's summary — append this sentence before the closing `_[tags]_`, and add `lifecycle` to its tag list:

```
Closes itself on world disconnect via `DockState.closeAll()`, keeping layout but dropping per-world state.
```

- [ ] **Step 7: Verify no stale doc references**

Run: `cmd.exe /c "gradlew.bat :26.2:clientClasses"` one final time, and `grep -rn "closeAll\|registerDockWorldLifecycle" docs/ src/`
Expected: the grep hits are the new code, the new `DockLifecycleSpec`, and the two doc files — no dangling references to names that do not exist.

- [ ] **Step 8: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/client/viewport/DockKeybinds.kt \
        src/client/kotlin/com/breadmoirai/garnet/client/GarnetClient.kt \
        docs/ui/dock-framework.md docs/ui/INDEX.md
git commit -m "feat(dock): close panels when the world closes; guard keybinds on the title screen"
```

---

## Manual verification (after Task 2)

The `DISCONNECT` registration and the `mc.level == null` guard need a live client, which the test
harness cannot drive. Verify by hand:

1. Launch the client, open a singleplayer world, press `Shift+1` — the LEFT region appears and the
   world view shrinks.
2. Quit to title. Expected: no dock, no shrink, the title screen renders full-window and its buttons
   respond at their normal positions.
3. Press `Shift+1` and `Alt+1` on the title screen. Expected: nothing happens.
4. Re-enter the world and press `Shift+1`. Expected: the Explorer returns at the width you left it,
   with a fresh tree (no ghost popup or stale selection from the previous session).
