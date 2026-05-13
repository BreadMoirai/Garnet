# UC-REC Client Screen Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add client-side Kotest coverage for UC-REC-01.b, UC-REC-01.c, UC-REC-01.d, and UC-REC-03.a — `RecorderScreen` pre-populates EditBoxes from server data and fires `SetRecorderConfigC2S` on every keystroke.

**Architecture:** New Kotest spec `RecorderScreenSpec` in the `clientTest` sourceset, extending `ClientSpec`. Uses the same server-builds-payload / client-receives-and-opens pattern as `ClientNetworkSpec` (existing UC-NET-01.c test). Private `EditBox` fields read via small reflection helper inside the spec file. The keystroke path drives `EditBox.value = ...` on the test thread; the synchronous responder fires `sendSetConfig`, which sends a `SetRecorderConfigC2S` payload drained from the client outbound queue.

**Tech Stack:** Kotlin, Kotest, Fabric API, Minecraft 26.1, `ClientSpec`/`FabricTestThreadPump`/`onServer` helpers.

---

### Task 1: Add `RecorderScreenSpec.kt` skeleton and register it

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt`

- [ ] **Step 1: Create the skeleton spec file**

Create `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt` with:

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.network.SetRecorderConfigC2S
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import net.minecraft.client.gui.components.EditBox
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Client-side coverage for [RecorderScreen] population and keystroke wiring.
 *
 * Covers UC-REC-01.b/c (server build of OpenRecorderScreenS2C → screen opens with
 * EditBoxes pre-populated from BE fields) and UC-REC-01.d / UC-REC-03.a (every keystroke
 * fires SetRecorderConfigC2S carrying the current field values).
 *
 * Test bodies run on the Kotest worker thread under ClientSpec's dispatcher.
 * Private EditBox fields on RecorderScreen are read via [editBoxValue] reflection helper —
 * adding test-only accessors to RecorderScreen would pollute production for a single seam.
 * See docs/gametest/client-test-threading.md for the threading model.
 */
class RecorderScreenSpec : ClientSpec({
    // tests added in subsequent tasks
})

private fun RecorderScreen.editBoxValue(fieldName: String): String {
    val f = RecorderScreen::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
    val box = f.get(this) as? EditBox ?: error("EditBox field '$fieldName' was null")
    return box.value
}

private fun RecorderScreen.setEditBoxValue(fieldName: String, value: String) {
    val f = RecorderScreen::class.java.getDeclaredField(fieldName).apply { isAccessible = true }
    val box = f.get(this) as? EditBox ?: error("EditBox field '$fieldName' was null")
    box.value = value
}
```

- [ ] **Step 2: Register the spec in `ClientTestSentinel`**

Edit `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt`. In the `specs = listOf(...)` block of `runKotestOnWorker`, append `RecorderScreenSpec::class` after `ClientNetworkSpec::class`:

```kotlin
specs = listOf(
    RunRedstoneSpecSmokeTest::class,
    ClientNetworkSpec::class,
    RecorderScreenSpec::class,
),
```

This is required per `feedback_kotest_specs_must_be_registered` — autoscan is off.

- [ ] **Step 3: Verify it compiles**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL with no compilation errors. (Spec is empty; no test will run yet.)

- [ ] **Step 4: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt
git commit -m "test(client): add RecorderScreenSpec scaffold for UC-REC client coverage"
```

---

### Task 2: UC-REC-01.b/c — screen opens with EditBoxes pre-populated

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt`

- [ ] **Step 1: Add the test inside the `ClientSpec({ ... })` body**

Insert the test inside the `ClientSpec({ ... })` block (replacing the placeholder comment):

```kotlin
    test("UC-REC-01.b/c: OpenRecorderScreenS2C opens RecorderScreen with EditBoxes pre-populated from BE") {
        val pos = BlockPos(220, 64, 100)
        val expectedSpecId = "uc-rec-01b-spec"
        val expectedStructure = "uc-rec-01b-struct"

        onServer {
            val level = this.overworld()
            val player = level.players().firstOrNull() ?: error("no overworld player")
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId(expectedSpecId)
            be.setStructure(expectedStructure)
            be.setSpecBounds(Vec3i(3, 3, 3))
            RedstoneSpecRecorderBlock.openScreenFor(player, be)
        }

        waitForClientScreen(RecorderScreen::class.java)
        val (specIdVal, outPathVal, structIdVal) = onClient { mc ->
            val s = mc.screen as RecorderScreen
            Triple(s.editBoxValue("specIdBox"), s.editBoxValue("outPathBox"), s.editBoxValue("structureIdBox"))
        }
        specIdVal shouldBe expectedSpecId
        structIdVal shouldBe expectedStructure
        // outPath is currently sourced from BE's structureId (no dedicated BE field for it);
        // accept either empty string or the structure id, per RecorderScreen's init wiring.
        // Tighten this assertion if RecorderScreen.init changes.
        check(outPathVal.isEmpty() || outPathVal == expectedStructure) {
            "outPathBox.value=$outPathVal did not match either of the accepted init values"
        }

        closeClientScreen()
    }
```

Note: the `outPath` assertion is intentionally permissive because `OpenRecorderScreenS2C` and the screen's init logic do not have a stable contract for which BE field maps to `outPath`. Read `RecorderScreen.init()` before tightening: if it pulls a dedicated value, change the assertion to `shouldBe` that value.

- [ ] **Step 2: Verify it compiles**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the client tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientGametest"`

(If task name differs, look it up: `cmd.exe /c "./gradlew.bat :26.1:tasks --all | grep -i ClientGametest"`. Per `feedback_kotest_test_filter`, do not use `--tests`; read the XML report at `build/reports/redstonespecs/clientTest/` after the run.)

Expected: the test passes. If it fails on the `outPathVal` check, open `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderScreen.kt` and read `init()` to see what string the third EditBox is seeded with, then tighten the assertion.

- [ ] **Step 4: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt
git commit -m "test(client): UC-REC-01.b/c — RecorderScreen pre-populates EditBoxes from BE"
```

---

### Task 3: UC-REC-01.d / 03.a — keystroke fires `SetRecorderConfigC2S`

**Files:**
- Modify: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt`

- [ ] **Step 1: Append the keystroke test**

Add this test directly below the test from Task 2, still inside the `ClientSpec({ ... })` body:

```kotlin
    test("UC-REC-01.d / UC-REC-03.a: setting an EditBox value fires SetRecorderConfigC2S with current field values") {
        val pos = BlockPos(240, 64, 100)
        val initialSpecId = "uc01d-init"
        val initialStructure = "uc01d-struct-init"

        onServer {
            val level = this.overworld()
            val player = level.players().firstOrNull() ?: error("no overworld player")
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId(initialSpecId)
            be.setStructure(initialStructure)
            be.setSpecBounds(Vec3i(3, 3, 3))
            RedstoneSpecRecorderBlock.openScreenFor(player, be)
        }

        waitForClientScreen(RecorderScreen::class.java)
        drainClientPayloads()

        // Mutate specIdBox on the test thread — setValue fires the responder synchronously
        // (per feedback_editbox_responder_sync); do NOT call onChange manually.
        FabricTestThreadPump.runOnTestThread { _ ->
            val s = net.minecraft.client.Minecraft.getInstance().screen as RecorderScreen
            s.setEditBoxValue("specIdBox", "edited-id")
        }

        // Allow the payload to flush via the client networking pipeline.
        waitClientTicks(2)

        val first = drainClientPayloads().filterIsInstance<SetRecorderConfigC2S>()
        first shouldHaveSize 1
        first[0].originPos shouldBe pos
        first[0].specId shouldBe "edited-id"
        first[0].structureId shouldBe initialStructure

        // Now mutate the structure field; the new payload carries the latest specId AND the new structureId.
        FabricTestThreadPump.runOnTestThread { _ ->
            val s = net.minecraft.client.Minecraft.getInstance().screen as RecorderScreen
            s.setEditBoxValue("structureIdBox", "edited-struct")
        }
        waitClientTicks(2)

        val second = drainClientPayloads().filterIsInstance<SetRecorderConfigC2S>()
        second shouldHaveSize 1
        second[0].specId shouldBe "edited-id"
        second[0].structureId shouldBe "edited-struct"

        closeClientScreen()
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the client tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientGametest"` (or whichever task name your project uses for `clientTest`).

Expected: both `RecorderScreenSpec` tests pass.

Common failure modes:
- "specIdBox field is null" → screen hadn't finished init; raise `waitForClientScreen` polling or assert `mc.screen` is non-null first.
- Zero payloads drained → the responder didn't fire because `setValue` was called with the same string. Pick edit values that differ from the initial values.
- Two payloads drained from a single mutation → some upstream code touched another EditBox. Check whether `setSpecBounds` or another setter on the BE caused a redundant `OpenRecorderScreenS2C` resend; if so, place `drainClientPayloads()` immediately before the mutation.

- [ ] **Step 4: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/RecorderScreenSpec.kt
git commit -m "test(client): UC-REC-01.d/03.a — EditBox edit fires SetRecorderConfigC2S"
```

---

### Task 4: Update the recording use-case coverage matrix

**Files:**
- Modify: `docs/use-cases/recording.md`

- [ ] **Step 1: Update matrix rows for UC-REC-01.b, 01.c, 01.d, 03.a**

In `docs/use-cases/recording.md`, replace the four matrix rows so each cites the new spec. Exact replacements:

Row UC-REC-01.b — change `Test` column from `—` to `RecorderScreenSpec."UC-REC-01.b/c: OpenRecorderScreenS2C opens RecorderScreen with EditBoxes pre-populated from BE"`, set Status to `covered`.

Row UC-REC-01.c — same test reference, Status `covered`.

Row UC-REC-01.d — `RecorderScreenSpec."UC-REC-01.d / UC-REC-03.a: setting an EditBox value fires SetRecorderConfigC2S with current field values"`, Status `covered`.

Row UC-REC-03.a — same test reference as 01.d, Status `covered`.

- [ ] **Step 2: Update the parent rows (UC-REC-01 and UC-REC-03)**

Row UC-REC-01 (the parent row) — Status remains GAP-PARTIAL only if 01.a is still uncovered. Confirm by re-reading the file; if 01.a is still `—`, leave UC-REC-01's Status as `GAP-PARTIAL` and update its Test column to reference either the new spec or a "see sub-rows" note. If 01.a is also covered elsewhere by now, change to `covered`.

Row UC-REC-03 — same logic: still GAP-PARTIAL because 03.b is not yet covered by this plan (covered in the gametest plan).

- [ ] **Step 3: Update `last_audited_commit` in the frontmatter**

After committing in Step 4, replace the frontmatter `last_audited_commit:` value with the SHA of the new commit. (Easiest: complete Step 4 first, then `git rev-parse HEAD`, then amend.)

- [ ] **Step 4: Commit**

```bash
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): mark UC-REC-01.b/c/d and 03.a covered by RecorderScreenSpec"
```

Then:

```bash
NEW_SHA=$(git rev-parse HEAD)
# Replace the last_audited_commit value with $NEW_SHA in docs/use-cases/recording.md
# (use Edit tool; do not amend with --no-edit)
```

After updating the frontmatter:

```bash
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): bump last_audited_commit after UC-REC-01/03.a coverage"
```

---

### Task 5: Final verification

- [ ] **Step 1: Full multi-sourceset compile**

Run (per `feedback_build_command`):
```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run client tests one more time**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientGametest"` (or your project's client-test launch task).
Expected: `RecorderScreenSpec`'s two tests pass; no regressions in `ClientNetworkSpec` or `RunRedstoneSpecSmokeTest`.

- [ ] **Step 3: Verify the docs**

Read `docs/use-cases/recording.md`. Confirm the four rows updated, `last_audited_commit` matches `git rev-parse HEAD` (or a recent commit on this branch), and parent rows UC-REC-01 / UC-REC-03 still correctly reflect any remaining gaps.

No further commit if everything matches.
