# Plan B — `runGarnetSpec` helper, retire OutputVerifier from runtime

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a single `runGarnetSpec(spec, originPos, level)` test-body helper that drives the existing `SpecRunner` pipeline from inside a Kotest test body, calling structured assertion helpers and throwing Kotest assertion failures on mismatch — replacing `OutputVerifier`'s post-run diff with inline assertions.

**Architecture:** `OutputVerifier`'s logic is preserved but moves into `GarnetAssertions.kt` — a set of helpers each test body calls explicitly, or implicitly via `runGarnetSpec(spec)`. `OutputVerifier` itself remains in the codebase for now (Plan D removes its caller; this plan only stops anything new from using it). Generated `.spec.kts` files (Plan C) and dev-side hand-written tests both call `runGarnetSpec`.

**Tech Stack:** Kotlin coroutines, Kotest assertions, the existing `runner/` package.

**Spec reference:** `docs/superpowers/specs/2026-05-07-garnet-kotest-bridge-design.md` §"Module shape" (RunnerDsl), §"R2 collapses into the test body".

**Depends on:** Plan A.

---

## File structure (after this plan)

**New:**
- `src/main/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertions.kt` — moves `OutputVerifier`'s evaluation helpers; exposes per-condition assertion functions.
- `src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt` — the `runGarnetSpec(spec, ...)` suspend function that drives `SpecRunner` to completion and asserts.
- `src/test/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertionsTest.kt` — unit tests for the assertion helpers (no MC).

**Modified later:**
- `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt` — calls `OutputVerifier` directly; will be replaced in Plan D, not here.

**Untouched:**
- `OutputVerifier.kt` — left alone in this plan; Plan D removes its caller, Plan C+ removes the class entirely.

---

## Task 1: Carve assertion helpers out of OutputVerifier (TDD)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertions.kt`
- Create: `src/test/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertionsTest.kt`

- [ ] **Step 1: Write failing test for `assertOutputsMatch`**

```kotlin
package com.breadmoirai.garnet.testing.runner

import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.runner.StateRecording
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import net.minecraft.core.Vec3i

class GarnetAssertionsTest : FunSpec({
    test("assertOutputsMatch passes when no outputs declared and recording empty") {
        val spec = GarnetSpec(
            id = "empty", bounds = Vec3i(1, 1, 1), lifespan = 1,
            structure = null, entries = emptyList(),
        )
        val recording = StateRecording.empty()  // helper added below if needed
        shouldNotThrowAny { assertOutputsMatch(spec, recording) }
    }

    test("assertOutputsMatch throws AssertionError with TickCheck context on mismatch") {
        // TODO: build a spec with one output entry expecting powered=true at tick 1,
        // and a recording where the block stayed unpowered. Assert the failure
        // message contains the entry label and the tick.
        val (spec, recording) = buildMismatchedSpecAndRecording()
        val ex = shouldThrow<AssertionError> { assertOutputsMatch(spec, recording) }
        ex.message shouldContain "tick 1"
    }
})

private fun buildMismatchedSpecAndRecording(): Pair<GarnetSpec, StateRecording> {
    TODO("Construct a spec with one OUTPUT entry expecting powered=true at SimTime(1, END_OF_TICK), " +
         "and a StateRecording whose post-state at that pos+tick reports powered=false. " +
         "See StateRecordingViewTest.kt and OutputVerifierTest if it exists for fixture patterns.")
}
```

> Note: the `buildMismatchedSpecAndRecording` fixture is intentionally a `TODO` because it requires familiarity with `StateRecording`'s internal shape. Before writing the code under test, the engineer should resolve this TODO by looking at `runner/StateRecording.kt` and existing tests in `src/test/kotlin/com/breadmoirai/garnet/runner/`. The fixture must produce a real `StateRecording` whose post-tick state at the output position contradicts the entry's condition.

- [ ] **Step 2: Run test to verify it fails**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests com.breadmoirai.garnet.testing.runner.GarnetAssertionsTest"`
Expected: FAIL with "function not defined" / "TODO".

- [ ] **Step 3: Implement `assertOutputsMatch`**

```kotlin
package com.breadmoirai.garnet.testing.runner

import com.breadmoirai.garnet.data.Phase
import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.data.SimTime
import com.breadmoirai.garnet.data.SpecEntry
import com.breadmoirai.garnet.data.StateCondition
import com.breadmoirai.garnet.data.outputs
import com.breadmoirai.garnet.runner.StateRecording
import com.breadmoirai.garnet.runner.StateRecordingView
import com.breadmoirai.garnet.runner.evaluateConditionOnState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.state.BlockState

/**
 * Asserts that every output entry in [spec] is satisfied by the post-tick state in [recording],
 * and that no output position changed at a tick not covered by an entry.
 *
 * On mismatch, throws [AssertionError] whose message lists each failing TickCheck.
 *
 * Replaces `OutputVerifier.verify(...)`-then-inspect — the assertion is the verification.
 */
fun assertOutputsMatch(spec: GarnetSpec, recording: StateRecording) {
    val view = StateRecordingView.of(recording)
    val byPos: Map<BlockPos, List<SpecEntry>> = spec.outputs.groupBy { it.pos }
    val failures = mutableListOf<String>()

    for ((pos, entries) in byPos) {
        val initial = recording.initialSnapshot[pos]
            ?: error("Output $pos not in recording snapshot")

        for (entry in entries) {
            val state = view.stateAt(entry.pos, anchorTime(entry.time))
            if (!evaluateConditionOnState(entry.condition, state)) {
                failures += entryFailureMessage(entry, state)
            }
        }

        val declaredTicks = entries.map { it.time.tick }.toSet()
        var prev: BlockState = initial
        val anyLabel = entries.firstOrNull()?.let { labelOf(it) } ?: pos.toString()
        for (t in 0 until spec.lifespan) {
            val cur = view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            if (cur != prev && t !in declaredTicks) {
                failures += "$anyLabel @ tick $t: unexpected state change (prev=$prev, now=$cur)"
            }
            prev = cur
        }
    }

    if (failures.isNotEmpty()) {
        throw AssertionError("Spec '${spec.id}' verification failed:\n  " + failures.joinToString("\n  "))
    }
}

private fun anchorTime(time: SimTime): SimTime =
    if (time.order == 0 && time.phase == Phase.END_OF_TICK)
        SimTime(time.tick, Phase.END_OF_TICK, Int.MAX_VALUE)
    else time

private fun labelOf(e: SpecEntry): String = e.label.ifEmpty { e.pos.toString() }

private fun entryFailureMessage(entry: SpecEntry, state: BlockState): String {
    val expected = describeCondition(entry.condition)
    val actual   = describeStateForCondition(entry.condition, state)
    return "${labelOf(entry)} @ tick ${entry.time.tick}: expected [$expected], got [$actual]"
}
```

You'll need to extract `evaluateConditionOnState`, `describeCondition`, `describeStateForCondition` from `OutputVerifier.kt` into a new top-level file at the same package as `OutputVerifier`. Create:

`src/main/kotlin/com/breadmoirai/garnet/runner/ConditionEvaluation.kt`:

```kotlin
package com.breadmoirai.garnet.runner

import com.breadmoirai.garnet.data.StateCondition
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.BooleanProperty
import net.minecraft.world.level.block.state.properties.IntegerProperty

/** True if [state] satisfies [condition]. Shared by OutputVerifier and GarnetAssertions. */
fun evaluateConditionOnState(condition: StateCondition, state: BlockState): Boolean = when (condition) {
    is StateCondition.All -> condition.conditions.all { evaluateConditionOnState(it, state) }
    is StateCondition.Any -> condition.conditions.any { evaluateConditionOnState(it, state) }
    is StateCondition.Not -> !evaluateConditionOnState(condition.condition, state)
    is StateCondition.BlockType -> {
        val actualId = BuiltInRegistries.BLOCK.getKey(state.block)
        actualId == condition.blockId
    }
    is StateCondition.BoolProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? BooleanProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.IntProperty -> {
        val prop = state.block.stateDefinition.getProperty(condition.name) as? IntegerProperty ?: return false
        state.getValue(prop) == condition.value
    }
    is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) == condition.value
    is StateCondition.ContainerContents,
    is StateCondition.IntRange -> false
}

fun describeCondition(condition: StateCondition): String = when (condition) {
    is StateCondition.BoolProperty -> "${condition.name}=${condition.value}"
    is StateCondition.IntProperty -> "${condition.name}=${condition.value}"
    is StateCondition.EnumProperty -> "${condition.name}=${condition.value}"
    is StateCondition.BlockType -> "block=${condition.blockId}"
    is StateCondition.All -> condition.conditions.joinToString(",") { describeCondition(it) }
    is StateCondition.Any -> condition.conditions.joinToString("|") { describeCondition(it) }
    is StateCondition.Not -> "!${describeCondition(condition.condition)}"
    is StateCondition.ContainerContents -> "container"
    is StateCondition.IntRange -> "${condition.name}=${condition.min}..${condition.max}"
}

fun describeStateForCondition(condition: StateCondition, state: BlockState): String = when (condition) {
    is StateCondition.BoolProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.IntProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.EnumProperty -> blockStatePropertyStr(state, condition.name) ?: "missing"
    is StateCondition.BlockType ->
        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.block).toString()
    else -> "(complex)"
}
```

Then in `OutputVerifier.kt`, **delete** the private `evaluateConditionOnState`, `describeCondition`, `describeStateForCondition` methods and call the top-level versions from this new file (same package; no import needed). Update `OutputVerifier.evaluateConditionOnState(...)` callsites to the top-level function.

- [ ] **Step 4: Run all unit tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: PASS — both new tests and the existing `OutputVerifierTest` (if any) must still pass after the helper extraction.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/runner/ConditionEvaluation.kt \
        src/main/kotlin/com/breadmoirai/garnet/runner/OutputVerifier.kt \
        src/main/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertions.kt \
        src/test/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertionsTest.kt
git commit -m "feat(testing): add assertOutputsMatch + extract condition evaluation helpers"
```

---

## Task 2: Implement `runGarnetSpec` driver

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt`

- [ ] **Step 1: Write the driver**

```kotlin
package com.breadmoirai.garnet.testing.runner

import com.breadmoirai.garnet.data.Phase
import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.runner.SpecRunner
import com.breadmoirai.garnet.runner.SpecSnapshot
import com.breadmoirai.garnet.runner.StateRecorder
import com.breadmoirai.garnet.testing.core.McDispatchers
import com.breadmoirai.garnet.testing.core.awaitTickEnd
import com.breadmoirai.garnet.testing.core.awaitTicks
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Executes a [GarnetSpec] from inside a Kotest test body and asserts its outputs.
 *
 * Drives the four-stage record→run→sample→verify pipeline as a single suspend call:
 *
 *  1. Captures a [SpecSnapshot] of the bounds at [originPos] and starts a [StateRecorder].
 *  2. Drives [SpecRunner] tick-by-tick through [GarnetSpec.lifespan] phases.
 *  3. Calls [assertOutputsMatch] with the recorded post-states.
 *  4. Restores the snapshot regardless of pass/fail.
 *
 * Returns the captured [com.breadmoirai.garnet.runner.StateRecording] so callers
 * (and Plan E's DiagnosticRecorder TestListener) can attach it to test metadata.
 *
 * Must be called inside a [com.breadmoirai.garnet.testing.GarnetTestSpec] test body
 * (which dispatches on [McDispatchers.Server]).
 */
suspend fun runGarnetSpec(
    spec: GarnetSpec,
    originPos: BlockPos,
    level: ServerLevel,
): com.breadmoirai.garnet.runner.StateRecording = withContext(McDispatchers.Server) {
    val snapshot = SpecSnapshot.capture(level, originPos, spec.bounds)
    val recorderId = UUID.randomUUID()
    val recorder = StateRecorder.forSpec(recorderId, originPos, spec.bounds)
    recorder.start(level, originPos, spec.bounds)
    StateRecorder.activate(recorder)
    snapshot.restore(level)

    val runner = SpecRunner(spec, originPos, level, snapshot)
    try {
        runner.start()
        // Drive the runner forward one full tick at a time.
        // Note: this loop assumes the testing infrastructure (Plan E and beyond) wires
        // `SpecRunnerCoordinator.onPhase` to forward phases here. For the unit-test path
        // (inside `awaitTicks`), the existing `SubTickPhaseEvents` callbacks fire on the
        // server thread and the coordinator's onPhase already reaches active runners.
        var done = false
        while (!done) {
            awaitTickEnd()
            // After each END_OF_TICK, the runner's onPhase has been called by the
            // coordinator; check completion via spec lifespan.
            done = runner.spec.lifespan <= 0 ||
                   runner.spec.lifespan + 1 <= currentTickCounter()  // sentinel; see note
        }
    } finally {
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }

    val recording = recorder.toRecording()
    assertOutputsMatch(spec, recording)
    recording
}

/**
 * Placeholder for "ticks elapsed since runner start". The current SpecRunner exposes
 * completion via `onPhase(...) == true` rather than a counter; see Task 3 for the
 * fix that exposes a public `isComplete` flag.
 */
private fun currentTickCounter(): Int = 0
```

> The `currentTickCounter` placeholder above is acknowledged broken — Task 3 below removes it.

- [ ] **Step 2: Don't run yet — Task 3 fixes the loop.**

---

## Task 3: Expose runner completion via a public flag

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunner.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt`

- [ ] **Step 1: Add a public `isComplete` to `SpecRunner`**

Edit `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunner.kt`. Add a public read-only flag set by `onPhase(...)` when it returns true:

```kotlin
class SpecRunner(
    val spec: GarnetSpec,
    val originPos: BlockPos,
    val level: ServerLevel,
    private val snapshot: SpecSnapshot,
) {
    private var ticksElapsed = -1
    var isComplete: Boolean = false
        private set

    // ... existing start(), resume(), resetCircuit() unchanged ...

    fun onPhase(phase: Phase): Boolean {
        if (phase == Phase.START_OF_TICK) ticksElapsed++
        if (ticksElapsed < 0) return false
        if (ticksElapsed >= spec.lifespan) {
            LOGGER.debug("[SpecRunner#onPhase] spec '{}' finished after {} ticks", spec.id, ticksElapsed)
            isComplete = true
            return true
        }
        applyInputsAt(SimTime(ticksElapsed, phase))
        return false
    }

    // ... rest unchanged ...
}
```

- [ ] **Step 2: Replace the loop in `RunGarnetSpec.kt`**

Replace the `while (!done)` block in Task 2's draft with:

```kotlin
    val runner = SpecRunner(spec, originPos, level, snapshot)
    try {
        runner.start()
        // Drive the runner: each awaitTickEnd advances one full server tick.
        // SpecRunnerCoordinator.onPhase forwards START_OF_TICK and END_OF_TICK phases
        // to all active runners, so by the time awaitTickEnd returns, runner.onPhase
        // has been called for END_OF_TICK and isComplete reflects this tick.
        while (!runner.isComplete) {
            awaitTickEnd()
        }
    } finally {
        // ... unchanged ...
    }
```

Delete the `private fun currentTickCounter()` stub.

- [ ] **Step 3: Build all source sets**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunner.kt \
        src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt
git commit -m "feat(testing): runGarnetSpec drives SpecRunner from a Kotest test body"
```

---

## Task 4: Pre-existing runner registration is required by `runGarnetSpec`

> Context: `SpecRunner.onPhase` is reachable only through `SpecRunnerCoordinator`, which today registers a runner via `startRun(specBlockEntity)`. From a Kotest test body there is no `SpecBlockEntity`. We need a parallel registration path.

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt`

- [ ] **Step 1: Add a BE-less registration**

Add to `SpecRunnerCoordinator`:

```kotlin
private val standaloneRunners = mutableListOf<SpecRunner>()

/**
 * Register a [SpecRunner] not bound to a [SpecBlockEntity]. Used by `runGarnetSpec` from
 * test bodies. The caller is responsible for [unregisterStandalone] when the runner completes.
 *
 * Phase events still flow through [onPhase].
 */
fun registerStandalone(runner: SpecRunner) {
    standaloneRunners += runner
}

fun unregisterStandalone(runner: SpecRunner) {
    standaloneRunners.remove(runner)
}
```

Modify `tickRunners(...)` to also tick standalone runners:

```kotlin
private fun tickRunners(level: ServerLevel, phase: Phase) {
    val completed = mutableListOf<SpecBlockEntity>()
    for ((be, runner) in runners) {
        if (be.level !== level) continue
        if (runner.onPhase(phase)) completed += be
    }
    // Standalone runners (test-body driven) are level-agnostic; they each hold their own level reference
    // and are responsible for filtering. Forward the phase unconditionally.
    val completedStandalone = mutableListOf<SpecRunner>()
    for (runner in standaloneRunners) {
        if (runner.level !== level) continue
        if (runner.onPhase(phase)) completedStandalone += runner
    }
    standaloneRunners.removeAll(completedStandalone)
    for (be in completed) {
        runners.remove(be)
        finishRun(be)
    }
}
```

- [ ] **Step 2: Update `runGarnetSpec` to register/unregister**

In `RunGarnetSpec.kt`, wrap the runner setup:

```kotlin
    val runner = SpecRunner(spec, originPos, level, snapshot)
    SpecRunnerCoordinator.registerStandalone(runner)
    try {
        runner.start()
        while (!runner.isComplete) {
            awaitTickEnd()
        }
    } finally {
        SpecRunnerCoordinator.unregisterStandalone(runner)
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }
```

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt \
        src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt
git commit -m "feat(runner): standalone runner registration for test-body driven runs"
```

---

## Task 5: Smoke test — runGarnetSpec under clientTest

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/RunGarnetSpecSmokeTest.kt`

- [ ] **Step 1: Write a minimal end-to-end test**

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.core.McDispatchers
import com.breadmoirai.garnet.testing.runner.runGarnetSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class RunGarnetSpecSmokeTest : GarnetTestSpec({
    test("runGarnetSpec completes for a trivial empty spec") {
        val spec = GarnetSpec(
            id = "smoke-empty",
            bounds = Vec3i(1, 1, 1),
            lifespan = 1,
            structure = null,
            entries = emptyList(),
        )
        val server = McDispatchers.currentServer
        val recording = runGarnetSpec(spec, BlockPos(0, 64, 0), server.overworld())
        recording.changes.size shouldBeGreaterThanOrEqual 0  // sanity — completed without throwing
    }
})
```

> Caveat: this smoke test exists in `src/clientTest/`, which spins up a real client+server. There is no programmatic in-game `Run` button yet (Plan D wires that). For this plan, run it manually via the Gradle task below.

- [ ] **Step 2: Run via clientTest**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientTest"`
Expected: in the Kotest report under `build/reports/garnet/clientTest/`, `RunGarnetSpecSmokeTest` shows PASS.

- [ ] **Step 3: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/RunGarnetSpecSmokeTest.kt
git commit -m "test(clientTest): smoke-test runGarnetSpec on trivial empty spec"
```

---

## Verification checklist

- [ ] `assertOutputsMatch` exists in `src/main/kotlin/com/breadmoirai/garnet/testing/runner/GarnetAssertions.kt`.
- [ ] `runGarnetSpec` exists in `src/main/kotlin/com/breadmoirai/garnet/testing/runner/RunGarnetSpec.kt`.
- [ ] `SpecRunner.isComplete` is public and set after `onPhase` returns `true`.
- [ ] `SpecRunnerCoordinator.registerStandalone` / `unregisterStandalone` exist; phase forwarding ticks them.
- [ ] `cmd.exe /c "./gradlew.bat :26.1:test"` passes (assertion-helpers test).
- [ ] `cmd.exe /c "./gradlew.bat :26.1:runClientTest"` passes (smoke test).
- [ ] `OutputVerifier.kt` is *not* deleted yet — `SpecRunnerCoordinator.finishRun` still calls it. Plan D removes the caller.

---

## Notes on what is intentionally NOT in this plan

- `SpecRunnerCoordinator.finishRun` still routes through `OutputVerifier`. Plan D replaces that call with the engine path.
- No `.spec.kts` shape change — Plan C reshapes the emitter so files declare a `GarnetTestSpec` subclass.
- No diagnostic-recorder TestListener; that's Plan E.
