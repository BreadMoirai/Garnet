# Recorder → Editor → Runner Client Test Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add one new `FabricClientGameTest` method, `recorderToEditorToRunnerFlow`, that exercises the full recorder→editor→runner UI flow with a lever-and-lamp circuit and asserts the runner replay passes.

**Architecture:** Single new private method on `RedstonespecsClientTests`, dispatched from `runTest`. Uses existing `SpecTestContext` helpers; relies on `context.waitFor` (inline) for the two block-class transitions. World coordinates chosen to avoid colliding with existing tests in the shared world.

**Tech Stack:** Kotlin, Fabric Client GameTest API (`net.fabricmc.fabric.api.client.gametest.v1`), Minecraft 1.21.x via Stonecutter, project's existing `SpecTestContext` test scaffolding.

---

## File Structure

- **Modify:** `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`
  - Add `recorderToEditorToRunnerFlow(ctx)` call in `runTest`.
  - Add `private fun recorderToEditorToRunnerFlow(ctx: SpecTestContext)`.
  - Add three `BlockPos` constants for the new test (recorder, lever, lamp) co-located with the existing `originPos`/`leverPos`/`lampPos` fields.

No other files change. The existing `SpecTestContext` helpers cover everything we need (`runCommand`, `waitTick`/`waitTicks`, `rightClickBlock`, `clickButton`, `waitForScreen`, `getClientBe`, raw `context.waitFor` for block-class transitions).

---

## Task 1: Add the new test method, wired into runTest

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt`

- [ ] **Step 1: Add coordinate constants for the new flow**

In `RedstonespecsClientTests`, just below the existing `originPos` / `leverPos` / `lampPos` fields, add:

```kotlin
    // Coordinates for recorderToEditorToRunnerFlow — offset to avoid collision
    // with leverLampFullFlow's blocks at x=0..2.
    private val recorderPos = BlockPos(30, 64, 0)
    private val recLampPos  = BlockPos(32, 64, 1)   // relative (2, 0, 1) — in DEFAULT_BOUNDS
    private val recLeverPos = BlockPos(32, 65, 1)   // relative (2, 1, 1) — on top of lamp
```

- [ ] **Step 2: Wire the new method into `runTest`**

In `RedstonespecsClientTests.runTest`, append the new call after the existing flows:

```kotlin
    override fun runTest(context: ClientGameTestContext) {
        SpecTestContext.createWorld(context).use { world ->
            val ctx = SpecTestContext(context, world)
            leverLampFullFlow(ctx)
            boundsScreenFlow(ctx)
            specEditorScreenFlow(ctx)
            editorTransformsToRunnerOnSave(ctx)
            discardClearsEverythingExceptIdBoundsAndMarkers(ctx)
            markerToolRejectsRunnerBlock(ctx)
            recorderToEditorToRunnerFlow(ctx)
        }
    }
```

- [ ] **Step 3: Add the new method body**

Add this method to `RedstonespecsClientTests` (placement: after `leverLampFullFlow`, since it's the closest sibling in spirit):

```kotlin
    // ── Test: Recorder → Editor → Runner via UI (Record/Stop, Save, Run) ─────
    private fun recorderToEditorToRunnerFlow(ctx: SpecTestContext) {
        // ── World setup ──────────────────────────────────────────────────────
        // Stone under the lamp for visual support (lamp doesn't strictly need it).
        ctx.runCommand("setblock ${recLampPos.x} ${recLampPos.y - 1} ${recLampPos.z} minecraft:stone")
        ctx.runCommand("setblock ${recorderPos.x} ${recorderPos.y} ${recorderPos.z} redstonespecs:redstone_spec_recorder")
        ctx.runCommand("setblock ${recLampPos.x} ${recLampPos.y} ${recLampPos.z} minecraft:redstone_lamp[lit=false]")
        ctx.runCommand("setblock ${recLeverPos.x} ${recLeverPos.y} ${recLeverPos.z} minecraft:lever[face=floor,facing=north,powered=false]")
        // Stand the player a few blocks south of the recorder so right-click hits land cleanly.
        ctx.runCommand("tp @a ${recorderPos.x} ${recorderPos.y} ${recorderPos.z - 3}")
        ctx.waitTicks(5)

        // ── Apply input marker on the lever (no UI in recorder mode) ─────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:input_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(recLeverPos)
        ctx.waitTick()

        // ── Apply output marker on the lamp (no UI in recorder mode) ─────────
        ctx.runCommand("clear @a")
        ctx.runCommand("give @a redstonespecs:output_spec_marker 1")
        ctx.waitTick()
        ctx.rightClickBlock(recLampPos)
        ctx.waitTick()

        // ── Open recorder UI and click Record ────────────────────────────────
        ctx.runCommand("clear @a")
        ctx.waitTick()
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(RecorderSetupScreen::class.java)
        ctx.clickButton("Record")
        // Record button calls onClose(); wait for screen to clear.
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)

        // ── Drive state changes during recording ─────────────────────────────
        ctx.waitTicks(2)
        ctx.runCommand("setblock ${recLeverPos.x} ${recLeverPos.y} ${recLeverPos.z} minecraft:lever[face=floor,facing=north,powered=true]")
        ctx.waitTicks(4)

        // ── Open recorder UI again and click Stop ────────────────────────────
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(RecorderSetupScreen::class.java)
        ctx.clickButton("Stop")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)

        // Server transforms recorder → editor on stop+finalize success.
        ctx.context.waitFor({ mc ->
            mc.level?.getBlockState(recorderPos)?.block is RedstoneSpecEditorBlock
        }, 100)

        // ── Open editor's overview screen and click Save → transforms to Runner ──
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Save")
        ctx.context.waitFor({ mc -> mc.screen == null }, 100)
        ctx.context.waitFor({ mc ->
            mc.level?.getBlockState(recorderPos)?.block is RedstoneSpecRunnerBlock
        }, 100)

        // ── Open runner's overview and click Run ─────────────────────────────
        ctx.rightClickBlock(recorderPos)
        ctx.waitForScreen(SpecOverviewScreen::class.java)
        ctx.clickButton("Run")

        // ── Wait for test result and assert ──────────────────────────────────
        ctx.context.waitFor({ mc ->
            (mc.level?.getBlockEntity(recorderPos) as? SpecBlockEntity)
                ?.lastTestResult != null
        }, 100)

        val be = ctx.getClientBe(recorderPos)
            ?: throw AssertionError("SpecBlockEntity not found at $recorderPos")
        val result = be.lastTestResult
            ?: throw AssertionError("lastTestResult is null after waitFor succeeded")
        val checks = result.checks
        check(checks.isNotEmpty()) { "recorderToEditorToRunnerFlow: expected at least one check in results" }
        val failed = checks.filter { !it.pass }
        check(failed.isEmpty()) {
            "recorderToEditorToRunnerFlow: failed checks: ${failed.joinToString { "${it.label}: expected=${it.expected} actual=${it.actual}" }}"
        }
    }
```

- [ ] **Step 4: Add the missing imports**

At the top of `RedstonespecsClientTests.kt`, add (alongside the existing imports — keep the file's import ordering consistent with what's already there):

```kotlin
import com.breadmoirai.redstonespecs.block.RedstoneSpecEditorBlock
import com.breadmoirai.redstonespecs.block.RedstoneSpecRunnerBlock
import com.breadmoirai.redstonespecs.client.screen.RecorderSetupScreen
```

`SpecOverviewScreen` and `SpecBlockEntity` are already imported. `RedstoneSpecRunnerBlock` may already be imported via the `editorTransformsToRunnerOnSave` test (verify when editing — if present, don't add a duplicate).

- [ ] **Step 5: Compile to verify**

Run from the project root:

```bash
cmd.exe /c "./gradlew.bat :26.1:compileGametestKotlin"
```

Expected: BUILD SUCCESSFUL with no errors. If a Gametest source-set task name differs in this project, fall back to the full build verification command:

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gameTestClasses :26.1:testClasses"
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit the test scaffolding**

```bash
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt
git commit -m "test: client gametest for recorder→editor→runner UI flow

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Run the gametest end-to-end and tune wait counts if flaky

**Files:**
- Possibly modify: `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt` (only if flaky)

- [ ] **Step 1: Run the client gametest suite**

Run from the project root:

```bash
cmd.exe /c "./gradlew.bat :26.1:runClientGameTest"
```

Expected: the suite (including the new flow) completes without `AssertionError`s. Look in the console output for `recorderToEditorToRunnerFlow` — there should be no thrown exceptions referencing it.

- [ ] **Step 2: If the new flow fails, diagnose**

Common failure modes and fixes (apply only the one that matches; do not pre-emptively pad waits):

- **`waitForScreen(RecorderSetupScreen)` timeout after the first right-click on the recorder.** The recorder's spec is auto-set on placement (see `RedstoneSpecRecorderBlock.setPlacedBy`), but `setblock` does not call `setPlacedBy`. If the screen never opens because `spec == null`, that path is still covered by `useWithoutItem` which sets a default spec before sending the open packet — so this should work. If it still doesn't, replace the `setblock` for the recorder with a `/give` + manual placement, or call `be.setSpec(...)` via `runOnServer` before the right-click.
- **`waitFor` for the editor block class times out after Stop.** Indicates `stopRecordingAndFinalize()` returned false (e.g., recorder never captured anything). Increase `waitTicks(4)` after the lever toggle to `waitTicks(8)`.
- **`failed.isEmpty()` assertion fires.** The recorded spec doesn't match replay. Print `failed.joinToString { ... }` is already in the assertion message — read which check failed (input vs output, which tick) and adjust. Most likely fix: increase `waitTicks(2)` after Record to give the recorder more baseline ticks before the lever toggle.

- [ ] **Step 3: If you tuned waits, commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsClientTests.kt
git commit -m "test: tune wait counts in recorderToEditorToRunnerFlow

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

If no tuning was needed, skip this step.

---

## Self-Review

**Spec coverage:**
- Spec step 1 (drive recording via UI) → Task 1 Step 3 (Record button click).
- Spec step 2 (recorder → editor on stop) → Task 1 Step 3 (Stop button + waitFor editor block).
- Spec step 3 (editor Save → runner) → Task 1 Step 3 (Save click + waitFor runner block).
- Spec step 4 (Run produces passing result) → Task 1 Step 3 (Run click + lastTestResult assertion).
- World layout (recorder/lamp/lever positions, lever-on-lamp attachment) → Task 1 Steps 1 & 3.
- Recording determinism risk handling → Task 2 Step 2 (tuning guidance, no retry logic).
- Out-of-scope items (intermediate UI assertions, alternative modes, discard path) → not in plan, as intended.

**Placeholder scan:** No TBD/TODO/"add error handling"/"similar to" markers in the plan. Each step has either exact code or an exact command.

**Type / signature consistency:** `RedstoneSpecEditorBlock`, `RedstoneSpecRunnerBlock`, `RecorderSetupScreen`, `SpecOverviewScreen`, `SpecBlockEntity`, and `clickButton("Record" / "Stop" / "Save" / "Run")` all match the names used in `RecorderSetupScreen.kt`, `SpecOverviewScreen.kt`, and `RedstonespecsClientTests.kt` as inspected.
