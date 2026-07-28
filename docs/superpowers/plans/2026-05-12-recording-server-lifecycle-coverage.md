# Recording Server-Lifecycle Coverage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add ~15 server-side tests in one new gametest spec, closing the recording-lifecycle gaps in `docs/use-cases/recording.md` (UC-REC-04, 05.a/e/f, 06.a/c, plus low-cost unit rows for 02.c, 02.e, 03.c, 06.b, 06.d). Update the coverage matrix and fix one doc/code discrepancy uncovered during design.

**Architecture:** Single new file `src/gametest/kotlin/.../test/recorder/RecordingLifecycleSpec.kt` extending `GarnetTestSpec`, mirroring the style of `RecorderRunnerNetworkRegistrySpec`. Each test embeds the UC ID in its name. Async file-write tests use a small inline poll-with-timeout helper. Marker/UndoStack tests are pure unit tests inside the same Kotest spec (no `onServer`).

**Tech Stack:** Kotest (gametest source set), Fabric, Kotlin coroutines, existing helpers (`GarnetTestSpec`, `withTempRoot`, `placeRecorderBE`, `makeMockServerPlayer`, `drainPayloads`, `onServer`).

---

## Context for the engineer

**Build verification (per project memory):**
```
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```
This compiles all 5 source sets (per the memory note "Full build verification command"). Use `:26.1:test` to run the unit tests; the gametest sourceset is run via `runGametest` (see `docs/gametest/INDEX.md` if needed).

**Spec registration (per project memory):** Every new Kotest spec MUST be added to the explicit `specs = listOf(...)` list in `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`. Autoscan is off — unregistered specs silently don't run.

**Test-thread model (per project memory):** `GarnetTestSpec` test bodies run on a worker. Code that touches the world / BE must be wrapped in `onServer { ... }` (a helper that hops to `McDispatchers.Server`). Pure unit tests don't need it.

**`isConfigured` default gotcha (per project memory):** A freshly-placed `SpecBlockEntity` defaults `specId = "spec"`, so `isConfigured` is true by default. Tests that need `isConfigured == false` must explicitly call `be.setSpecId("")`.

**`be.startRun` warning (per project memory):** Do NOT call `be.startRun(...)` from a Kotest spec body — it hangs. We don't use it in this plan.

**File-write path:** `SpecBlockEntity.stopRecordingAndFinalize` writes via `serverLevel.server.getWorldPath(LevelResource.ROOT).resolve(SharedSettings.specSaveDir).resolve("$specId.spec.kts")` unless `be.managedSourcePath` is set, in which case it writes to that path. The save dir is shared across the gametest server lifetime — tests must use unique `specId` values to avoid collisions.

**Doc/code discrepancy uncovered during design:** `docs/use-cases/recording.md` UC-REC-06.a says the DISCARD handler "calls `StateRecorder.deactivate` on the active recorder (if any), sets `stateRecorder = null`". The actual code (`handleRecorderCommand` in `NetworkRegistry.kt`) maps `RecorderCmd.DISCARD` to `be.discardForRerecord()`, which only collapses duplicate markers — it does NOT touch the recorder. Task 9 fixes this doc.

---

### Task 1: Skeleton + GametestSentinel registration

**Files:**
- Create: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`

- [ ] **Step 1: Create the empty spec file**

```kotlin
package com.breadmoirai.garnet.test.recorder

import com.breadmoirai.garnet.testing.GarnetTestSpec

/**
 * Server-side coverage for recording-lifecycle UCs. Each test corresponds to one or more
 * rows in `docs/use-cases/recording.md`. Test names embed the UC ID for traceability.
 *
 * Out of scope: client-screen rows (UC-REC-01.c/d, 03.a/b), marker-tool integration
 * rows (UC-REC-02.a/b/d), and the phase-event row (UC-REC-04.d). See the design doc
 * `docs/superpowers/specs/2026-05-12-recording-server-lifecycle-coverage-design.md`.
 */
class RecordingLifecycleSpec : GarnetTestSpec({
    // tests added in subsequent tasks
})
```

- [ ] **Step 2: Register in GametestSentinel**

In `src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt`, add the import next to the other `test.*` imports:

```kotlin
import com.breadmoirai.garnet.test.recorder.RecordingLifecycleSpec
```

Add `RecordingLifecycleSpec::class,` to the `specs = listOf(...)` block, e.g. immediately after `RecorderRunnerNetworkRegistrySpec::class,`.

- [ ] **Step 3: Verify it compiles**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"`
Expected: BUILD SUCCESSFUL. (No tests yet — the empty spec is legal.)

- [ ] **Step 4: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/test/GametestSentinel.kt
git commit -m "test(recorder): scaffold RecordingLifecycleSpec for lifecycle UCs"
```

---

### Task 2: Unit tests — `isConfigured` guard (UC-REC-03.c)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

These tests verify the *guard logic* directly. Constructing a `SpecBlockEntity` requires a `BlockPos` and a `BlockState`; the guard reads only `specId` and `specBounds`, so we don't need a level. Use `setSpecId` / `setSpecBounds` to drive the fields.

`SpecBlockEntity` lives in `com.breadmoirai.garnet.block`. The default state is `ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState()`.

The setters call `setChangedAndSync()` which in turn calls `level?.sendBlockUpdated(...)`. Because `level` is null, the call is a no-op — safe to use without a server.

- [ ] **Step 1: Write the failing tests**

Add inside the `GarnetTestSpec({ ... })` body:

```kotlin
test("UC-REC-03.c: isConfigured returns false for blank specId") {
    val be = com.breadmoirai.garnet.block.SpecBlockEntity(
        net.minecraft.core.BlockPos.ZERO,
        com.breadmoirai.garnet.ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(),
    )
    be.setSpecId("")
    be.isConfigured shouldBe false
}

test("UC-REC-03.c: isConfigured returns false when any bound dimension is zero") {
    val be = com.breadmoirai.garnet.block.SpecBlockEntity(
        net.minecraft.core.BlockPos.ZERO,
        com.breadmoirai.garnet.ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(),
    )
    be.setSpecId("ok")
    be.setSpecBounds(net.minecraft.core.Vec3i(0, 5, 5))
    be.isConfigured shouldBe false
    be.setSpecBounds(net.minecraft.core.Vec3i(5, 0, 5))
    be.isConfigured shouldBe false
    be.setSpecBounds(net.minecraft.core.Vec3i(5, 5, 0))
    be.isConfigured shouldBe false
    be.setSpecBounds(net.minecraft.core.Vec3i(1, 1, 1))
    be.isConfigured shouldBe true
}
```

(Use top-level imports if the file has them — the existing specs put `io.kotest.matchers.shouldBe` and the MC types at the top of the file. Mirror that style; the fully-qualified form is shown above so you can paste-then-clean.)

- [ ] **Step 2: Run to confirm fail vs pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` to compile.

(These specs only run inside the gametest harness, not under `:26.1:test`. We rely on the compile + a single later harness run; per-test red→green is not feasible here. Run `runGametest` once at the end of the plan to verify all new tests pass — see Task 11.)

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-03.c isConfigured guard"
```

---

### Task 3: `startRecording` validation tests (UC-REC-06.b)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

`startRecording` requires a `ServerLevel`, so wrap in `onServer { ... }` and use `placeRecorderBE`. We mutate the BE's specId/bounds after placement to drive the failure branches.

- [ ] **Step 1: Add the tests**

```kotlin
test("UC-REC-06.b: startRecording returns false for blank specId") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2000, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "to-be-blanked")
        be.setSpecId("")
        be.startRecording() shouldBe false
        be.isRecording shouldBe false
    }
}

test("UC-REC-06.b: startRecording returns false for zero-volume bounds") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2010, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "ok")
        be.setSpecBounds(net.minecraft.core.Vec3i(0, 5, 5))
        be.startRecording() shouldBe false
        be.isRecording shouldBe false
    }
}
```

(Imports already exist from Task 2 — use the bare names if the file has top-level `import` statements.)

- [ ] **Step 2: Compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-06.b startRecording validation"
```

---

### Task 4: `startRecording` happy path + initial snapshot (UC-REC-04.a/b/c)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

This task verifies the success path: `startRecording` returns true, `isRecording` flips, the recorder lands in `StateRecorder.activeRecorders()`, and `initialSnapshot` captures a known block placed inside bounds.

`StateRecorder.activeRecorders()` is a static accessor that returns the live set. The `initialSnapshot` is keyed by *origin-relative* `BlockPos` (see `worldToOriginRelative`).

Place a non-air block (e.g. stone) inside the bounds *before* calling `startRecording` so the snapshot has something checkable.

- [ ] **Step 1: Add the test**

```kotlin
test("UC-REC-04.a/c: startRecording succeeds and recorder appears in activeRecorders") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2020, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "uc04ac", bounds = net.minecraft.core.Vec3i(3, 3, 3))

        be.startRecording() shouldBe true
        try {
            be.isRecording shouldBe true
            val active = com.breadmoirai.garnet.runner.StateRecorder.activeRecorders()
            active.size shouldBe 1
        } finally {
            // cleanup so the next test starts with an empty active set
            be.stopRecordingAndFinalize()
        }
    }
}

test("UC-REC-04.b: StateRecorder.start captures initial snapshot keyed by origin-relative BlockPos") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2030, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "uc04b", bounds = net.minecraft.core.Vec3i(3, 3, 3))

        // Place a stone block at world (2031, 64, 1000) -> origin-relative (1, 0, 0)
        val markerWorld = net.minecraft.core.BlockPos(2031, 64, 1000)
        level.setBlock(markerWorld, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 2)

        be.startRecording() shouldBe true
        try {
            // Reach the recorder via reflection on the private field. Alternative: read
            // the snapshot via a small public test seam. Reflection is acceptable here
            // because the field is stable and this is a single read.
            val recorder = be.javaClass.getDeclaredField("stateRecorder")
                .apply { isAccessible = true }
                .get(be) as com.breadmoirai.garnet.runner.StateRecorder
            val snapshot = recorder.initialSnapshot
            val rel = net.minecraft.core.BlockPos(1, 0, 0)
            val state = snapshot[rel]
            state shouldNotBe null
            state!!.block shouldBe net.minecraft.world.level.block.Blocks.STONE
        } finally {
            be.stopRecordingAndFinalize()
        }
    }
}
```

If reflection feels brittle to the engineer reading this: an acceptable alternative is to add a `@VisibleForTesting fun activeStateRecorder(): StateRecorder? = stateRecorder` accessor to `SpecBlockEntity`. Use whichever the engineer prefers; reflection is fine because the field name is stable and this is the only consumer.

- [ ] **Step 2: Compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-04 startRecording happy path + initial snapshot"
```

---

### Task 5: `stopRecordingAndFinalize` deactivation (UC-REC-05.a)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

Verify that after stop: `isRecording == false` and the recorder is gone from `activeRecorders()`. No file assertion — that's Tasks 6/7.

- [ ] **Step 1: Add the test**

```kotlin
test("UC-REC-05.a: stopRecordingAndFinalize deactivates recorder and clears stateRecorder") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2040, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "uc05a-no-markers")
        // No markers added => no file write, but the deactivation path still runs.
        be.startRecording() shouldBe true
        val activeBefore = com.breadmoirai.garnet.runner.StateRecorder.activeRecorders().size

        be.stopRecordingAndFinalize() shouldBe true
        be.isRecording shouldBe false
        com.breadmoirai.garnet.runner.StateRecorder.activeRecorders().size shouldBe (activeBefore - 1)
    }
}
```

- [ ] **Step 2: Compile**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-05.a stop deactivates recorder"
```

---

### Task 6: File-write polling helper + UC-REC-05.e (saveDir path)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

`stopRecordingAndFinalize` launches the file write on `coroutineScope.launch(Dispatchers.IO)` — async. We poll the expected file path with a short timeout.

The save dir resolves to: `serverLevel.server.getWorldPath(LevelResource.ROOT).resolve(SharedSettings.specSaveDir).resolve("$specId.spec.kts")`.

We need at least one marker on the BE for the emit path to fire (UC-REC-05.b — empty markers skip the write). Add a marker via `be.addOrUpdateMarker(EntryMarker(...))` directly; we don't need the marker tool.

- [ ] **Step 1: Add the polling helper at the top of the file (top-level, file-private)**

```kotlin
private suspend fun awaitFile(path: java.nio.file.Path, timeoutMs: Long = 2000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (java.nio.file.Files.exists(path)) return
        kotlinx.coroutines.delay(20)
    }
    error("file did not appear within ${timeoutMs}ms: $path")
}
```

- [ ] **Step 2: Add the saveDir test**

```kotlin
test("UC-REC-05.e: stopRecordingAndFinalize with markers writes .spec.kts under SharedSettings.specSaveDir") {
    val specId = "uc05e-savedir-${java.util.UUID.randomUUID().toString().take(6)}"
    val expectedPath = onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2050, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = specId)
        // Drop one input marker so the emit path runs.
        be.addOrUpdateMarker(
            com.breadmoirai.garnet.runner.EntryMarker(
                pos = net.minecraft.core.BlockPos(1, 0, 0),
                label = "input_a",
                color = 0xFF4488FF.toInt(),
                kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT,
            )
        )
        be.startRecording() shouldBe true
        be.stopRecordingAndFinalize() shouldBe true
        this.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve(com.breadmoirai.garnet.config.SharedSettings.specSaveDir)
            .resolve("$specId.spec.kts")
    }
    awaitFile(expectedPath)
    // cleanup so the next gametest run starts clean
    java.nio.file.Files.deleteIfExists(expectedPath)
}
```

Note: `onServer { ... }` returns the lambda's last expression — confirm with the existing usage in `RecorderRunnerNetworkRegistrySpec`. If it does NOT return a value, hoist `expectedPath` to a `lateinit var` or capture via a single-element holder.

- [ ] **Step 3: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-05.e finalize writes .spec.kts to saveDir"
```

---

### Task 7: UC-REC-05.e (managedSourcePath path) + UC-REC-05.f sync

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

When `be.managedSourcePath` is set, the write goes there instead of the saveDir. Use `withTempRoot` for an isolated temp dir.

UC-REC-05.f says "`setChangedAndSync` is called after finalization so client sees idle." There's no observable hook for that without a client. We assert the *observable consequence*: `be.isRecording == false` after stop returns true — already covered by Task 5. We add a brief inline comment in this task referencing that.

- [ ] **Step 1: Add the test**

```kotlin
test("UC-REC-05.e: managedSourcePath redirects write to that path instead of saveDir") {
    val savedPath = java.util.concurrent.atomic.AtomicReference<java.nio.file.Path?>(null)
    withTempRoot("rec-uc05e-managed") { tmp ->
        val target = tmp.resolve("uc05e-managed.spec.kts")
        onServer {
            val level = this.overworld()
            val pos = net.minecraft.core.BlockPos(2060, 64, 1000)
            val be = placeRecorderBE(level, pos, specId = "uc05e-managed")
            be.managedSourcePath = target
            be.addOrUpdateMarker(
                com.breadmoirai.garnet.runner.EntryMarker(
                    pos = net.minecraft.core.BlockPos(1, 0, 0),
                    label = "input_a",
                    color = 0xFF4488FF.toInt(),
                    kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT,
                )
            )
            be.startRecording() shouldBe true
            be.stopRecordingAndFinalize() shouldBe true
            // UC-REC-05.f: stop returning true and isRecording==false is the observable
            // consequence of setChangedAndSync running on the server thread.
            be.isRecording shouldBe false
            savedPath.set(target)
        }
        awaitFile(target)
        // Sanity: file is non-empty DSL source
        val text = java.nio.file.Files.readString(target)
        text shouldContain "garnetSpec"
    }
}
```

- [ ] **Step 2: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-05.e managedSourcePath write + 05.f sync"
```

---

### Task 8: Empty-marker no-op (UC-REC-06.c)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

When `specMarkers` (after dedup) is empty, `stopRecordingAndFinalize` skips the emit + write. Recorder still deactivates and `isRecording` flips to false.

We assert: stop returns `true`, `isRecording == false`, the expected file path does NOT exist after a brief wait.

- [ ] **Step 1: Add the test**

```kotlin
test("UC-REC-06.c: stopRecordingAndFinalize with no markers writes no file but still clears recorder") {
    val specId = "uc06c-empty-${java.util.UUID.randomUUID().toString().take(6)}"
    val expectedPath = onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2070, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = specId)
        // Intentionally no addOrUpdateMarker call.
        be.startRecording() shouldBe true
        be.stopRecordingAndFinalize() shouldBe true
        be.isRecording shouldBe false
        this.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
            .resolve(com.breadmoirai.garnet.config.SharedSettings.specSaveDir)
            .resolve("$specId.spec.kts")
    }
    // Give any (incorrect) async write a chance to land before asserting absence.
    kotlinx.coroutines.delay(200)
    java.nio.file.Files.exists(expectedPath) shouldBe false
}
```

- [ ] **Step 2: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-06.c empty-marker stop writes no file"
```

---

### Task 9: DISCARD handler test + doc fix (UC-REC-06.a, UC-REC-06.d)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`
- Modify: `docs/use-cases/recording.md`

**Code reality:** `handleRecorderCommand` maps `RecorderCmd.DISCARD` to `be.discardForRerecord()`, which only collapses duplicate `(pos, kind)` markers. It does **NOT** deactivate an active recorder. The current UC-REC-06.a text is incorrect.

We add two tests that reflect actual behavior, and patch the UC text + coverage matrix.

- [ ] **Step 1: Add the tests**

```kotlin
test("UC-REC-06.a: handleRecorderCommand(DISCARD) calls discardForRerecord (does not touch active recorder)") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2080, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "uc06a")
        // Seed two markers with the same (pos, kind) so discard collapses them.
        val marker = com.breadmoirai.garnet.runner.EntryMarker(
            pos = net.minecraft.core.BlockPos(1, 0, 0),
            label = "input_a",
            color = 0xFF4488FF.toInt(),
            kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT,
        )
        be.setSpecMarkers(listOf(marker, marker.copy(label = "input_b")))
        be.specMarkers.size shouldBe 2

        val player = makeMockServerPlayer(this)
        com.breadmoirai.garnet.network.handleRecorderCommand(
            this, player,
            com.breadmoirai.garnet.network.RecorderCommandC2S(
                pos, com.breadmoirai.garnet.network.RecorderCmd.DISCARD,
            ),
        )

        // Behavior of discardForRerecord: collapse duplicates by (pos, kind).
        be.specMarkers.size shouldBe 1
    }
}

test("UC-REC-06.d: discardForRerecord collapses duplicate (pos,kind) entries to one each") {
    onServer {
        val level = this.overworld()
        val pos = net.minecraft.core.BlockPos(2090, 64, 1000)
        val be = placeRecorderBE(level, pos, specId = "uc06d")
        val rel = net.minecraft.core.BlockPos(1, 0, 0)
        val a = com.breadmoirai.garnet.runner.EntryMarker(
            pos = rel, label = "input_a", color = 0,
            kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT,
        )
        val b = a.copy(label = "input_b")
        val cOut = a.copy(
            label = "output_a",
            kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.OUTPUT,
        )
        be.setSpecMarkers(listOf(a, b, cOut, cOut.copy(label = "output_b")))
        be.discardForRerecord()
        be.specMarkers.size shouldBe 2
        // Exactly one INPUT and one OUTPUT remain at this (pos).
        val byKind = be.specMarkers.groupBy { it.kind }
        byKind[com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT]?.size shouldBe 1
        byKind[com.breadmoirai.garnet.runner.EntryMarker.Kind.OUTPUT]?.size shouldBe 1
    }
}
```

- [ ] **Step 2: Fix the doc**

In `docs/use-cases/recording.md`, replace UC-REC-06.a's bullet:

Old:
```
- UC-REC-06.a — The `DISCARD` command handler calls `StateRecorder.deactivate` on the active recorder (if any), sets `stateRecorder = null`, and calls `setChangedAndSync` — no `RecordingDslEmitter.emit` call is made, so no file is written.
```

New:
```
- UC-REC-06.a — The `DISCARD` command handler delegates to `SpecBlockEntity.discardForRerecord`, which collapses duplicate `(pos, kind)` markers to a single placeholder per pair and calls `setChangedAndSync`. It does NOT deactivate an active `StateRecorder` or set `stateRecorder = null` — those happen only via the `STOP` path. To abort an in-progress recording without a file write, the author must currently send `STOP` with an empty marker list (UC-REC-06.c).
```

(The matrix update is in Task 11; don't touch the matrix yet to keep diffs reviewable.)

- [ ] **Step 3: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt \
        docs/use-cases/recording.md
git commit -m "test(recorder): UC-REC-06.a/d discard semantics + fix UC-06.a doc"
```

---

### Task 10: Marker + UndoStack unit tests (UC-REC-02.c, 02.e)

**Files:**
- Modify: `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

`InputSpecMarkerItem.createMarker(relPos, be)` and `OutputSpecMarkerItem.createMarker(relPos, be)` are pure functions of `relPos` and `be.specMarkers` (used to derive the next label). `UndoStack` is a singleton object with `push(uuid, UndoRecord)` / `pop(uuid)` and an internal cap of 20 per UUID.

For createMarker we still need a `SpecBlockEntity` instance (it reads `be.specMarkers` to compute the next label). Construct it the same way as Task 2 — no level required.

- [ ] **Step 1: Add the marker tests**

```kotlin
test("UC-REC-02.c: InputSpecMarkerItem.createMarker yields input_a then input_b with color 0xFF4488FF") {
    val be = com.breadmoirai.garnet.block.SpecBlockEntity(
        net.minecraft.core.BlockPos.ZERO,
        com.breadmoirai.garnet.ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(),
    )
    val item = com.breadmoirai.garnet.item.InputSpecMarkerItem()
    val first = item.createMarker(net.minecraft.core.BlockPos(1, 0, 0), be)
    first.label shouldBe "input_a"
    first.color shouldBe 0xFF4488FF.toInt()
    first.kind shouldBe com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT

    be.setSpecMarkers(listOf(first))
    val second = item.createMarker(net.minecraft.core.BlockPos(2, 0, 0), be)
    second.label shouldBe "input_b"
}

test("UC-REC-02.c: OutputSpecMarkerItem.createMarker uses color 0xFFFF8800") {
    val be = com.breadmoirai.garnet.block.SpecBlockEntity(
        net.minecraft.core.BlockPos.ZERO,
        com.breadmoirai.garnet.ModRegistries.GARNET_RECORDER_BLOCK.defaultBlockState(),
    )
    val marker = com.breadmoirai.garnet.item.OutputSpecMarkerItem()
        .createMarker(net.minecraft.core.BlockPos(1, 0, 0), be)
    marker.label shouldBe "output_a"
    marker.color shouldBe 0xFFFF8800.toInt()
    marker.kind shouldBe com.breadmoirai.garnet.runner.EntryMarker.Kind.OUTPUT
}
```

- [ ] **Step 2: Add the UndoStack test**

```kotlin
test("UC-REC-02.e: UndoStack push then pop returns the marker; cap at 20 per UUID") {
    val uuid = java.util.UUID.randomUUID()
    val origin = net.minecraft.core.BlockPos(0, 64, 0)
    val mk = { i: Int ->
        com.breadmoirai.garnet.item.UndoStack.UndoRecord(
            originPos = origin,
            marker = com.breadmoirai.garnet.runner.EntryMarker(
                pos = net.minecraft.core.BlockPos(i, 0, 0),
                label = "input_$i",
                color = 0xFF4488FF.toInt(),
                kind = com.breadmoirai.garnet.runner.EntryMarker.Kind.INPUT,
            ),
        )
    }
    com.breadmoirai.garnet.item.UndoStack.clear(uuid)

    val r0 = mk(0)
    com.breadmoirai.garnet.item.UndoStack.push(uuid, r0)
    com.breadmoirai.garnet.item.UndoStack.pop(uuid) shouldBe r0

    // Push 21; bottom must have been evicted -> popping 20 times yields entries 1..20 reversed.
    for (i in 0..20) com.breadmoirai.garnet.item.UndoStack.push(uuid, mk(i))
    val popped = generateSequence { com.breadmoirai.garnet.item.UndoStack.pop(uuid) }.toList()
    popped.size shouldBe 20
    // Newest first: 20, 19, ..., 1
    popped.first().marker.pos.x shouldBe 20
    popped.last().marker.pos.x shouldBe 1
}
```

- [ ] **Step 3: Compile + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:gametestClasses"` — expect BUILD SUCCESSFUL.

```bash
git add src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt
git commit -m "test(recorder): UC-REC-02.c marker factories + 02.e UndoStack"
```

---

### Task 11: Run the gametest harness; update coverage matrix

**Files:**
- Modify: `docs/use-cases/recording.md`

Now that the spec is written, run it once via the gametest harness and update the coverage matrix to reflect new coverage.

- [ ] **Step 1: Run the full gametest harness**

Run: `cmd.exe /c "./gradlew.bat :26.1:runGametest"` (or the project's standard gametest task — confirm via `docs/gametest/INDEX.md` if `runGametest` isn't right).

Expected: harness completes, `RecordingLifecycleSpec` shows all ~13 new tests passing, no regressions in existing specs.

- [ ] **Step 2: If a test fails, debug**

Read the Kotest XML report under `build/reports/garnet/gametest/` (per `GametestSentinel.runAll`'s `reportsDir`). Common failure modes:

- `onServer` does not return a value → use the `lateinit var` workaround for path capture.
- File lingering from a prior run causing stale assertions → ensure `Files.deleteIfExists(expectedPath)` after every saveDir test.
- `StateRecorder.activeRecorders().size` non-zero from a previous test that didn't clean up → ensure every `startRecording` test pairs with a `stopRecordingAndFinalize`.

Fix and re-run.

- [ ] **Step 3: Update the coverage matrix in `docs/use-cases/recording.md`**

Replace the matrix rows below with these (preserve the surrounding `| UC ID | Description | Test | Status |` header). Test references use the form `RecordingLifecycleSpec."UC-REC-XX.y: …"`.

```
| UC-REC-02.c | `createMarker` constructs `EntryMarker` with auto-generated label and color | `RecordingLifecycleSpec."UC-REC-02.c: InputSpecMarkerItem.createMarker yields input_a then input_b with color 0xFF4488FF"`, `RecordingLifecycleSpec."UC-REC-02.c: OutputSpecMarkerItem.createMarker uses color 0xFFFF8800"` | covered |
| UC-REC-02.e | `UndoStack.push` records placed marker; `pop` + `removeMarker` reverses it | `RecordingLifecycleSpec."UC-REC-02.e: UndoStack push then pop returns the marker; cap at 20 per UUID"` | **GAP-PARTIAL** |
| UC-REC-03.c | `isConfigured` guard prevents start with blank ID or zero-volume bounds | `RecordingLifecycleSpec."UC-REC-03.c: isConfigured returns false for blank specId"`, `RecordingLifecycleSpec."UC-REC-03.c: isConfigured returns false when any bound dimension is zero"` | covered |
| UC-REC-04 | Start a recording session | `RecordingLifecycleSpec."UC-REC-04.a/c: startRecording succeeds and recorder appears in activeRecorders"` | **GAP-PARTIAL** |
| UC-REC-04.a | `startRecording` validates and calls `StateRecorder.forSpec` | `RecordingLifecycleSpec."UC-REC-04.a/c: startRecording succeeds and recorder appears in activeRecorders"` | covered |
| UC-REC-04.b | `StateRecorder.start` takes initial snapshot of region | `RecordingLifecycleSpec."UC-REC-04.b: StateRecorder.start captures initial snapshot keyed by origin-relative BlockPos"` | covered |
| UC-REC-04.c | `StateRecorder.activate` adds recorder to global `activeRecorders` set | `RecordingLifecycleSpec."UC-REC-04.a/c: startRecording succeeds and recorder appears in activeRecorders"` | covered |
| UC-REC-05.a | `stopRecordingAndFinalize` deactivates recorder and obtains `StateRecording` | `RecordingLifecycleSpec."UC-REC-05.a: stopRecordingAndFinalize deactivates recorder and clears stateRecorder"` | covered |
| UC-REC-05.e | DSL source written async via `SpecPersistence.writeSpecKts` or `managedSourcePath.writeText` | `RecordingLifecycleSpec."UC-REC-05.e: stopRecordingAndFinalize with markers writes .spec.kts under SharedSettings.specSaveDir"`, `RecordingLifecycleSpec."UC-REC-05.e: managedSourcePath redirects write to that path instead of saveDir"`, `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-REC-05.f | `setChangedAndSync` called after finalization so client sees `"idle"` | `RecordingLifecycleSpec."UC-REC-05.e: managedSourcePath redirects write to that path instead of saveDir"` | **GAP-PARTIAL** |
| UC-REC-06 | Recover from or discard a failed recording | `RecordingLifecycleSpec."UC-REC-06.a: handleRecorderCommand(DISCARD) calls discardForRerecord (does not touch active recorder)"` | **GAP-PARTIAL** |
| UC-REC-06.a | `DISCARD` handler delegates to `discardForRerecord`; markers collapsed, recorder untouched | `RecordingLifecycleSpec."UC-REC-06.a: handleRecorderCommand(DISCARD) calls discardForRerecord (does not touch active recorder)"` | covered |
| UC-REC-06.b | `startRecording` returns `false` for blank ID / zero-volume bounds | `RecordingLifecycleSpec."UC-REC-06.b: startRecording returns false for blank specId"`, `RecordingLifecycleSpec."UC-REC-06.b: startRecording returns false for zero-volume bounds"` | covered |
| UC-REC-06.c | Empty marker list skips emit and write; block returns to `"idle"` silently | `RecordingLifecycleSpec."UC-REC-06.c: stopRecordingAndFinalize with no markers writes no file but still clears recorder"` | covered |
| UC-REC-06.d | `discardForRerecord` resets markers to de-duplicated placeholder set | `RecordingLifecycleSpec."UC-REC-06.d: discardForRerecord collapses duplicate (pos,kind) entries to one each"` | covered |
```

Also update the `last_audited_commit:` field at the top of `recording.md` to the latest commit hash on `main`:

```bash
git rev-parse HEAD
```

- [ ] **Step 4: Final commit**

```bash
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): mark recording lifecycle UCs covered by RecordingLifecycleSpec"
```

---

## Self-review checklist (do not delete; for engineer reference)

- [x] Every targeted UC in the design has a task (UC-REC-02.c, 02.e, 03.c, 04.a/b/c, 05.a, 05.e×2, 05.f, 06.a, 06.b, 06.c, 06.d).
- [x] No "TBD" or "implement later" — every test body is shown verbatim.
- [x] Type/method names check: `EntryMarker(pos, label, color, kind)`, `EntryMarker.Kind.{INPUT,OUTPUT}`, `UndoStack.{push,pop,clear,UndoRecord}`, `StateRecorder.activeRecorders()`, `placeRecorderBE`, `handleRecorderCommand` — all verified against source during planning.
- [x] Doc-sync: Task 9 fixes the UC-REC-06.a discrepancy; Task 11 updates the coverage matrix and `last_audited_commit`.
- [x] Spec registration in `GametestSentinel` (Task 1) — required per project memory rule.
- [x] Build verification command specified (5 sourceset compile per project memory).
