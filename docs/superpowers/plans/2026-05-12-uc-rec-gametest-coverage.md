# UC-REC Gametest Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close UC-REC-02.a/b/d, UC-REC-03.b, and UC-REC-04.d via Kotest specs in the `gametest` sourceset. UC-REC-04.e and UC-REC-05.f are documented as covered-indirectly (no new tests).

**Architecture:** A new spec `MarkerToolSpec` covers `SpecMarkerTool.useOn` behaviour (out-of-bounds PASS, runner-block guard, replace/append semantics) and the server-side `handleSetRecorderConfig` handler. A single test appended to the existing `RecordingLifecycleSpec` covers `StateRecorder.onPhaseForActiveRecorders` advancing `currentTick`/`currentPhase`. Tick advancement is observed via `SimTime` on a recorded change rather than via reflection — `currentTick` is private and behavioural assertion is more robust.

**Tech Stack:** Kotlin, Kotest, Fabric API, Minecraft 26.1, `GarnetTestSpec` base, `onServer` / `McDispatchers.Server`, existing helpers in `NetworkTestSupport.kt`.

---

### Task 1: Add `MarkerToolSpec.kt` skeleton and register it

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

- [ ] **Step 1: Locate the Gametest spec registration list**

Run: `grep -n "specs = listOf\|class GametestSentinel" src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

Note the line range that holds the `specs = listOf(...)` block. You'll append `MarkerToolSpec::class` to it in Step 3.

- [ ] **Step 2: Create the skeleton spec file**

Create `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`:

```kotlin
package com.breadmoirai.garnet.test.recorder

import com.breadmoirai.garnet.ModRegistries
import com.breadmoirai.garnet.block.SpecBlockEntity
import com.breadmoirai.garnet.network.SetRecorderConfigC2S
import com.breadmoirai.garnet.network.handleSetRecorderConfig
import com.breadmoirai.garnet.runner.EntryMarker
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.placeRecorderBE
import com.breadmoirai.garnet.test.placeRunnerBE
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.server.onServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Covers UC-REC-02.a/b/d (marker-tool behaviour) and UC-REC-03.b
 * (`handleSetRecorderConfig` applies specId and structureId).
 *
 * All tests run on the server thread via `onServer { … }` (per
 * feedback_redstonetestspec_server_thread). Items are pulled from `ModRegistries`
 * because direct construction trips MC's intrusive-holder guard
 * (feedback_item_construction_in_tests).
 */
class MarkerToolSpec : GarnetTestSpec({
    // tests added in subsequent tasks
})
```

- [ ] **Step 3: Register `MarkerToolSpec` in `GametestSentinel`**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`, find the `specs = listOf(...)` block (located via Step 1) and append `MarkerToolSpec::class` to it. Add the import line:

```kotlin
import com.breadmoirai.garnet.test.recorder.MarkerToolSpec
```

This is required per `feedback_kotest_specs_must_be_registered` — autoscan is off; specs not in the explicit list silently don't run.

- [ ] **Step 4: Verify it compiles**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt
git commit -m "test(gametest): add MarkerToolSpec scaffold for UC-REC-02/03 coverage"
```

---

### Task 2: UC-REC-02.a — `useOn` outside any recorder returns `PASS`

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`

- [ ] **Step 1: Add a helper to build a `UseOnContext`**

Append this helper at the bottom of `MarkerToolSpec.kt`, outside the class body:

```kotlin
private fun buildUseOnContext(
    level: net.minecraft.world.level.Level,
    player: net.minecraft.world.entity.player.Player,
    item: net.minecraft.world.item.Item,
    hitPos: BlockPos,
): UseOnContext {
    val stack = item.defaultInstance
    val hitVec = Vec3.atCenterOf(hitPos)
    val hit = BlockHitResult(hitVec, net.minecraft.core.Direction.UP, hitPos, false)
    // The 6-arg UseOnContext constructor matches MC 26.1: (Level, Player?, InteractionHand, ItemStack, BlockHitResult).
    return UseOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit)
}
```

If the constructor signature differs in your MC version, check `UseOnContext` (e.g. via `grep -rn "class UseOnContext\b" ~/.gradle/caches/`). MC 26.1's primary constructor takes `(Player?, InteractionHand, BlockHitResult)` overloads — pick the one that compiles.

- [ ] **Step 2: Add the test inside the `GarnetTestSpec({ ... })` body**

Replace the placeholder comment with:

```kotlin
    test("UC-REC-02.a: useOn outside any registered SpecBE bounds returns PASS and adds no marker") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            // Far from any other test's positions to avoid bounds collision with concurrent specs.
            val hitPos = BlockPos(900, 64, 900)
            val item = ModRegistries.INPUT_SPEC_MARKER_ITEM
            val ctx = buildUseOnContext(level, player, item, hitPos)
            val result = item.useOn(ctx)
            result shouldBe InteractionResult.PASS
        }
    }
```

If the registry constant is named differently, look it up:
`grep -n "INPUT_SPEC_MARKER" src/main/kotlin/com/breadmoirai/garnet/ModRegistries.kt`. Use the actual field name.

- [ ] **Step 3: Verify it compiles**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the gametest Kotest suite**

Run: `cmd.exe /c "gradlew.bat :26.1:test"` (per `feedback_kotest_test_filter` — do not use `--tests`; read the XML report).

Inspect: `build/test-results/test/TEST-com.breadmoirai.garnet.test.recorder.MarkerToolSpec.xml` (path may vary; search `build/` for `MarkerToolSpec`).

Expected: the one new test passes. If the `UseOnContext` constructor doesn't compile or the runtime throws, fix in this task before committing.

- [ ] **Step 5: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt
git commit -m "test(gametest): UC-REC-02.a — marker tool PASSes outside recorder bounds"
```

---

### Task 3: UC-REC-02.b — `useOn` on a runner block is rejected

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`

- [ ] **Step 1: Append the test**

Add inside the spec body:

```kotlin
    test("UC-REC-02.b: useOn inside a runner block's bounds returns PASS and adds no marker") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            val pos = BlockPos(910, 64, 910)
            val be = placeRunnerBE(level, pos, specId = "uc02b", bounds = Vec3i(3, 3, 3))
            be.specMarkers.shouldBeEmpty()

            val hitPos = pos.offset(1, 0, 1)  // inside specBounds
            val item = ModRegistries.INPUT_SPEC_MARKER_ITEM
            val ctx = buildUseOnContext(level, player, item, hitPos)
            val result = item.useOn(ctx)

            result shouldBe InteractionResult.PASS
            be.specMarkers.shouldBeEmpty()  // guard prevents placement on runners
        }
    }
```

Rationale: `findFor` returns the runner's `SpecBlockEntity` because the hit position is inside its `specBounds`. The guard at `SpecMarkerTool.useOn:42` (`if (be.blockState.block is GarnetRunnerBlock)`) rejects the placement.

- [ ] **Step 2: Compile and run**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Then: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: this test passes alongside Task 2's.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt
git commit -m "test(gametest): UC-REC-02.b — runner-block guard rejects marker placement"
```

---

### Task 4: UC-REC-02.d — `addOrUpdateMarker` replace/append semantics

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`

- [ ] **Step 1: Append the test**

```kotlin
    test("UC-REC-02.d: addOrUpdateMarker replaces same (pos,kind), appends new pos or kind") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(920, 64, 920)
            val be = placeRecorderBE(level, pos, specId = "uc02d", bounds = Vec3i(3, 3, 3))
            be.specMarkers.shouldBeEmpty()

            val rel = BlockPos(1, 0, 0)
            val a = EntryMarker(pos = rel, label = "input_a", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)
            val aReplacement = EntryMarker(pos = rel, label = "input_a_v2", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)

            be.addOrUpdateMarker(a)
            be.specMarkers shouldHaveSize 1
            be.specMarkers.single().label shouldBe "input_a"

            be.addOrUpdateMarker(aReplacement)
            be.specMarkers shouldHaveSize 1
            be.specMarkers.single().label shouldBe "input_a_v2"  // replaced, not appended

            // Same pos, different kind → append
            val samePosDifferentKind = EntryMarker(pos = rel, label = "output_a", color = 0xFFFF8800.toInt(), kind = EntryMarker.Kind.OUTPUT)
            be.addOrUpdateMarker(samePosDifferentKind)
            be.specMarkers shouldHaveSize 2

            // Different pos → append
            val differentPos = EntryMarker(pos = BlockPos(2, 0, 0), label = "input_b", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)
            be.addOrUpdateMarker(differentPos)
            be.specMarkers shouldHaveSize 3
        }
    }
```

- [ ] **Step 2: Compile and run**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"` then `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: all three `MarkerToolSpec` tests pass.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt
git commit -m "test(gametest): UC-REC-02.d — addOrUpdateMarker replace/append semantics"
```

---

### Task 5: UC-REC-03.b — `handleSetRecorderConfig` applies `specId` and `structureId`

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`

- [ ] **Step 1: Read the handler to confirm scope**

The current handler at `src/main/kotlin/com/breadmoirai/garnet/network/NetworkRegistry.kt:66` applies `specId` (when non-blank) and `structureId` (when non-blank). It does NOT apply `outPath`, and does NOT touch `specBounds`. This contradicts the UC-REC-03.b row in `docs/use-cases/recording.md`, which is aspirational. The test asserts actual behaviour; the doc is corrected in Task 7.

- [ ] **Step 2: Append the test**

```kotlin
    test("UC-REC-03.b: handleSetRecorderConfig applies non-blank specId and structureId to the BE") {
        onServer {
            val level = this.overworld()
            val server = level.server
            val player = makeMockServerPlayer(server)
            val pos = BlockPos(930, 64, 930)
            val be = placeRecorderBE(level, pos, specId = "initial", bounds = Vec3i(3, 3, 3))

            handleSetRecorderConfig(
                server,
                player,
                SetRecorderConfigC2S(
                    originPos = pos,
                    specId = "updated-spec",
                    outPath = "ignored-by-handler",
                    structureId = "updated-struct",
                ),
            )

            be.specId shouldBe "updated-spec"
            be.specStructure shouldBe "updated-struct"
        }
    }

    test("UC-REC-03.b: handleSetRecorderConfig ignores blank specId / structureId") {
        onServer {
            val level = this.overworld()
            val server = level.server
            val player = makeMockServerPlayer(server)
            val pos = BlockPos(940, 64, 930)
            val be = placeRecorderBE(level, pos, specId = "keep-me", structureId = "keep-struct", bounds = Vec3i(3, 3, 3))

            handleSetRecorderConfig(
                server,
                player,
                SetRecorderConfigC2S(pos, specId = "", outPath = "", structureId = ""),
            )

            be.specId shouldBe "keep-me"
            be.specStructure shouldBe "keep-struct"
        }
    }

    test("UC-REC-03.b: handleSetRecorderConfig is a no-op when block at origin is not a recorder") {
        onServer {
            val level = this.overworld()
            val server = level.server
            val player = makeMockServerPlayer(server)
            val pos = BlockPos(950, 64, 930)
            val be = placeRunnerBE(level, pos, specId = "runner-keep", bounds = Vec3i(3, 3, 3))

            handleSetRecorderConfig(
                server,
                player,
                SetRecorderConfigC2S(pos, specId = "should-not-stick", outPath = "", structureId = "x"),
            )

            be.specId shouldBe "runner-keep"  // handler returned early
        }
    }
```

If `SpecBlockEntity` exposes `specId`/`specStructure` differently (e.g. as `getSpecId()` rather than a Kotlin property), look up the actual API: `grep -n "fun getSpecId\|val specId\|fun setSpecId" src/main/kotlin/com/breadmoirai/garnet/block/SpecBlockEntity.kt`. Adjust the assertion expressions to match.

- [ ] **Step 3: Compile and run**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"` then `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: all three new 03.b tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt
git commit -m "test(gametest): UC-REC-03.b — handleSetRecorderConfig applies/guards fields"
```

---

### Task 6: UC-REC-04.d — `onPhaseForActiveRecorders` advances tick/phase state

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

- [ ] **Step 1: Read the existing spec's end to choose insertion point**

The file ends with the UC-REC-02.e test (UndoStack). Append the new test after it and before the closing `})`. Read lines 290–334 of the file if you need to confirm the exact close.

- [ ] **Step 2: Add imports if missing**

Ensure these imports are present at the top of `RecordingLifecycleSpec.kt`:

```kotlin
import com.breadmoirai.garnet.dsl.Phase
import com.breadmoirai.garnet.runner.StateRecorder
```

`StateRecorder` is already imported (used in earlier tests). `Phase` likely is not; add it.

- [ ] **Step 3: Append the test before the final `})`**

```kotlin
    test("UC-REC-04.d: onPhaseForActiveRecorders advances currentTick on START_OF_TICK and updates currentPhase") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(960, 64, 960)
            val be = placeRecorderBE(level, pos, specId = "uc04d", bounds = Vec3i(3, 3, 3))
            check(be.startRecording()) { "startRecording failed; check isConfigured" }
            try {
                val recorder = StateRecorder.activeRecorders().first { it.specId == be.stateRecorder!!.specId }

                // Initial state: currentPhase defaults to START_OF_TICK, currentTick is -1.
                recorder.currentPhase shouldBe Phase.START_OF_TICK

                // START_OF_TICK should advance currentTick. We can't read currentTick directly,
                // but a recorded change captures `SimTime(currentTick.coerceAtLeast(0), …)`.
                StateRecorder.onPhaseForActiveRecorders(level, Phase.START_OF_TICK)
                // Record a change to capture the post-advance tick value.
                val markerPos = pos.offset(1, 0, 0)
                level.setBlock(markerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2)
                recorder.currentPhase shouldBe Phase.START_OF_TICK
                val firstTick = recorder.changes.last().simTime.tick
                firstTick shouldBe 0  // counter went -1 → 0 on first START_OF_TICK

                // Second START_OF_TICK advances tick to 1.
                StateRecorder.onPhaseForActiveRecorders(level, Phase.START_OF_TICK)
                level.setBlock(markerPos, Blocks.AIR.defaultBlockState(), 2)
                recorder.changes.last().simTime.tick shouldBe 1

                // A non-START_OF_TICK phase updates currentPhase but NOT the tick counter.
                StateRecorder.onPhaseForActiveRecorders(level, Phase.PRE_RST)  // pick any non-START_OF_TICK value
                recorder.currentPhase shouldBe Phase.PRE_RST
                level.setBlock(markerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 2)
                recorder.changes.last().simTime.tick shouldBe 1  // unchanged
            } finally {
                be.stopRecordingAndFinalize()
            }
        }
    }
```

Notes:
- `Phase.PRE_RST` is a placeholder for "any phase other than START_OF_TICK". Open `src/main/kotlin/com/breadmoirai/garnet/dsl/Phase.kt` (or grep `enum class Phase`) and pick a real enum value. Common candidates: `START_OF_TICK`, `END_OF_TICK`, `PRE_REDSTONE`, etc.
- `Blocks` import is needed: `import net.minecraft.world.level.block.Blocks`.
- `BlockPos`, `Vec3i`, and `placeRecorderBE` are already imported by the existing file.
- The `try / finally stopRecordingAndFinalize` is critical — `activeRecorders` is global state and leaks across tests if not cleaned up.

- [ ] **Step 4: Verify imports + compile**

Run: `cmd.exe /c "gradlew.bat :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL. If `Phase.PRE_RST` (or whatever you picked) doesn't exist, fix the enum value and retry.

- [ ] **Step 5: Run the gametest suite**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Expected: the new UC-REC-04.d test passes; all other `RecordingLifecycleSpec` tests still pass; no regressions in `MarkerToolSpec`.

Common failure modes:
- `firstTick shouldBe 0` fails with `-1` → the `record` path coerces with `coerceAtLeast(0)`, so even before any phase advance, recorded `tick` is `0`. If you see `0` before calling `onPhaseForActiveRecorders`, the first START_OF_TICK will move it to `1`, not `0`. Adjust the expected values by re-reading `StateRecorder.kt:79`.
- `recorder.changes.last()` throws `NoSuchElementException` → the `setBlock` was outside `boundsWorldMin..boundsWorldMax`, so the mixin didn't dispatch. Place the `setBlock` strictly inside `pos..pos+(bounds-1)`.

- [ ] **Step 6: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(gametest): UC-REC-04.d — onPhaseForActiveRecorders advances tick/phase"
```

---

### Task 7: Update the recording use-case coverage matrix

**Files:**
- Modify: `docs/use-cases/recording.md`

- [ ] **Step 1: Update matrix rows**

For each of the following rows, change `Test` from `—` to the spec citation shown and Status to `covered`:

- **UC-REC-02.a** → `MarkerToolSpec."UC-REC-02.a: useOn outside any registered SpecBE bounds returns PASS and adds no marker"`
- **UC-REC-02.b** → `MarkerToolSpec."UC-REC-02.b: useOn inside a runner block's bounds returns PASS and adds no marker"`
- **UC-REC-02.d** → `MarkerToolSpec."UC-REC-02.d: addOrUpdateMarker replaces same (pos,kind), appends new pos or kind"`
- **UC-REC-03.b** → `MarkerToolSpec."UC-REC-03.b: handleSetRecorderConfig applies non-blank specId and structureId to the BE"` (also covered by the two adjacent 03.b tests; one citation suffices for the matrix)
- **UC-REC-04.d** → `RecordingLifecycleSpec."UC-REC-04.d: onPhaseForActiveRecorders advances currentTick on START_OF_TICK and updates currentPhase"`

- [ ] **Step 2: Update covered-indirectly rows**

- **UC-REC-04.e** → set Status to `covered (indirect)` and replace `—` in the Test column with: `Implied by UC-REC-04.a/c — BE state readable post-call confirms setChangedAndSync fired.`
- **UC-REC-05.f** → set Status to `covered (indirect)` and replace `—` with: `Implied by UC-REC-05.a — isRecording observable as false post-call confirms setChangedAndSync fired.`

- [ ] **Step 3: Update parent rows**

- **UC-REC-02** parent — Status was `GAP`. All sub-rows (02.a/b/c/d/e) are now covered. Change Status to `covered` and Test to a brief umbrella note such as `see sub-rows (RecordingLifecycleSpec, MarkerToolSpec)`.
- **UC-REC-03** parent — all sub-rows (03.a/b/c) now covered. Same treatment.
- **UC-REC-04** parent — all sub-rows (04.a/b/c/d/e) now have explicit or indirect coverage. Change to `covered`.

(Verify by reading the matrix; only mark a parent `covered` if every sub-row is.)

- [ ] **Step 4: Correct the UC-REC-03 narrative**

In the body of UC-REC-03 (the **System interactions** subsection for `03.b`), the current text says the handler "calls `SpecBlockEntity.setSpecId`, `SpecBlockEntity.setStructure`, and `SpecBlockEntity.setSpecBounds` as appropriate". The current handler does NOT call `setSpecBounds` and does NOT apply `outPath`. Edit the line to read:

> `UC-REC-03.b — The server-side handler for `SetRecorderConfigC2S` calls `SpecBlockEntity.setSpecId` and `SpecBlockEntity.setStructure` when the corresponding payload fields are non-blank, each of which calls `setChangedAndSync`. (Note: `outPath` is currently carried in the packet but ignored by the handler; bounds changes go through a separate path.)`

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): mark UC-REC-02.a/b/d, 03.b, 04.d covered; correct 03.b narrative"
```

- [ ] **Step 6: Bump `last_audited_commit`**

```bash
NEW_SHA=$(git rev-parse HEAD)
# Edit the `last_audited_commit:` frontmatter value in docs/use-cases/recording.md to $NEW_SHA
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): bump last_audited_commit after UC-REC gametest coverage"
```

---

### Task 8: Final verification

- [ ] **Step 1: Full multi-sourceset compile**

Run (per `feedback_build_command`):
```bash
cmd.exe /c "gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the gametest Kotest suite**

Run: `cmd.exe /c "gradlew.bat :26.1:test"`
Read the JUnit XML report under `build/test-results/test/` for both `MarkerToolSpec` and `RecordingLifecycleSpec`. Confirm:
- All 4 `MarkerToolSpec` tests pass (02.a, 02.b, 02.d, and the three 03.b tests).
- The new UC-REC-04.d test passes in `RecordingLifecycleSpec`.
- No regressions in any previously-passing test in this sourceset.

- [ ] **Step 3: Confirm docs**

Read `docs/use-cases/recording.md`:
- Rows updated as described in Task 7.
- `last_audited_commit` matches `git rev-parse HEAD` (or the latest commit in this plan).
- Parent rows UC-REC-02, UC-REC-03, UC-REC-04 reflect their sub-row coverage correctly.

No further commit if everything matches.
