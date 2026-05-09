# Drop Data Layer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the `data/` package and in-world editor with a deferred-closure DSL that *is* the spec; keep slim recorder/runner block UIs.

**Architecture:** A new `dsl/` package replaces `data/`. `redstoneSpec(id, bounds, lifespan, structure, strict) { … }` returns a value class wrapping a `SpecRun.() -> Unit` lambda. `runRedstoneSpec(level, origin, spec)` snapshots, restores, runs the lambda once to populate per-tick scheduler maps, then drives the tick loop dispatching inputs (direct setters → `BlockState`) at `START_OF_TICK` and assertions (condition-AST predicates) at `END_OF_TICK`. Recorder block emits `.spec.kts` text directly from `StateRecording` (no `RedstoneSpec` intermediate). Editor block, JSON codec, and ~half of `network/Packets.kt` are deleted.

**Tech Stack:** Kotlin (multi-version Stonecutter), Fabric, Kotest (JVM unit tests under `src/test/`), Fabric gametest (`src/gametest/`, `src/clientTest/`).

**Reference spec:** `docs/superpowers/specs/2026-05-09-drop-data-layer-design.md`

**Build verification command (run at end of every phase):**

```bash
cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"
```

Tests:

```bash
cmd.exe /c "./gradlew.bat :26.1:test"
```

---

## Phase 1 — Carve out `dsl/` package (pure moves)

Move shared primitives out of `data/` into a new `dsl/` package. No behavior change. After this phase the codebase compiles with imports rewritten throughout.

### Task 1: Move `SimTime` and `Phase` from `data/` to `dsl/`

**Files:**
- Move: `src/main/kotlin/com/breadmoirai/redstonespecs/data/SimTime.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/SpecTime.kt` (file rename for clarity; classes unchanged)

- [ ] **Step 1: Move file with `git mv`**

```bash
mkdir -p src/main/kotlin/com/breadmoirai/redstonespecs/dsl
git mv src/main/kotlin/com/breadmoirai/redstonespecs/data/SimTime.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/dsl/SpecTime.kt
```

- [ ] **Step 2: Edit the package declaration**

In the moved file, change line 1:
```kotlin
package com.breadmoirai.redstonespecs.data
```
to:
```kotlin
package com.breadmoirai.redstonespecs.dsl
```

- [ ] **Step 3: Update all imports project-wide**

```bash
grep -rl "com.breadmoirai.redstonespecs.data.SimTime\|com.breadmoirai.redstonespecs.data.Phase" src/ | \
  xargs sed -i 's/com\.breadmoirai\.redstonespecs\.data\.SimTime/com.breadmoirai.redstonespecs.dsl.SimTime/g; s/com\.breadmoirai\.redstonespecs\.data\.Phase/com.breadmoirai.redstonespecs.dsl.Phase/g'
```

- [ ] **Step 4: Run the build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes :26.1:testClasses :26.1:gametestClasses :26.1:clientClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(dsl): move SimTime/Phase from data/ into dsl/"
```

### Task 2: Move `StateCondition` from `data/` to `dsl/`

**Files:**
- Move: `src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/StateCondition.kt`

- [ ] **Step 1: Move file**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/data/StateCondition.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/dsl/StateCondition.kt
```

- [ ] **Step 2: Update package declaration in the moved file**

Change `package com.breadmoirai.redstonespecs.data` → `package com.breadmoirai.redstonespecs.dsl`.

- [ ] **Step 3: Update all imports**

```bash
grep -rl "com.breadmoirai.redstonespecs.data.StateCondition" src/ | \
  xargs sed -i 's/com\.breadmoirai\.redstonespecs\.data\.StateCondition/com.breadmoirai.redstonespecs.dsl.StateCondition/g'
```

- [ ] **Step 4: Run the build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes :26.1:testClasses :26.1:gametestClasses :26.1:clientClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor(dsl): move StateCondition from data/ into dsl/"
```

### Task 3: Move `ConditionEvaluator` from `runner/` to `dsl/`

**Files:**
- Move: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/ConditionEvaluator.kt`

- [ ] **Step 1: Move file**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/runner/ConditionEvaluator.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/dsl/ConditionEvaluator.kt
```

- [ ] **Step 2: Update package declaration**

Change `package com.breadmoirai.redstonespecs.runner` → `package com.breadmoirai.redstonespecs.dsl`.

- [ ] **Step 3: Update all imports**

```bash
grep -rl "com.breadmoirai.redstonespecs.runner.ConditionEvaluator\|com.breadmoirai.redstonespecs.runner.evaluateConditionOnState\|com.breadmoirai.redstonespecs.runner.describeCondition\|com.breadmoirai.redstonespecs.runner.describeStateForCondition\|com.breadmoirai.redstonespecs.runner.anchorTime" src/ | \
  xargs sed -i 's/com\.breadmoirai\.redstonespecs\.runner\.\(evaluateConditionOnState\|describeCondition\|describeStateForCondition\|anchorTime\|ConditionEvaluator\)/com.breadmoirai.redstonespecs.dsl.\1/g'
```

- [ ] **Step 4: Build and commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes :26.1:testClasses :26.1:gametestClasses :26.1:clientClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "refactor(dsl): move ConditionEvaluator from runner/ into dsl/"
```

### Task 4: Move `ConditionDsl` from `data/dsl/` to `dsl/`

**Files:**
- Move: `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/ConditionScope.kt`

- [ ] **Step 1: Move file**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/ConditionDsl.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/dsl/ConditionScope.kt
```

- [ ] **Step 2: Update package declaration**

Change `package com.breadmoirai.redstonespecs.data.dsl` → `package com.breadmoirai.redstonespecs.dsl`.

- [ ] **Step 3: Update all imports**

```bash
grep -rl "com.breadmoirai.redstonespecs.data.dsl.ConditionScope\|com.breadmoirai.redstonespecs.data.dsl.SpecDslMarker" src/ | \
  xargs sed -i 's/com\.breadmoirai\.redstonespecs\.data\.dsl\.ConditionScope/com.breadmoirai.redstonespecs.dsl.ConditionScope/g; s/com\.breadmoirai\.redstonespecs\.data\.dsl\.SpecDslMarker/com.breadmoirai.redstonespecs.dsl.SpecDslMarker/g'
```

(Note: `SpecDslMarker` annotation may live inside `ConditionDsl.kt` — read the file before moving and pull the annotation along; if it's in a separate file, leave it for Task 5 but ensure the import-rewrite treats it consistently.)

- [ ] **Step 4: Build and commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes :26.1:testClasses :26.1:gametestClasses :26.1:clientClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "refactor(dsl): move ConditionDsl from data/dsl/ into dsl/"
```

---

## Phase 2 — New imperative DSL alongside the old

Add the new DSL surface (no integration with existing code yet). Old `data/RedstoneSpec`, old `data/dsl/SpecDsl`, and old `runner/SpecRunner` stay untouched and reachable. New unit tests cover the new types.

### Task 5: Add `dsl/RedstoneSpec.kt` (the new value class)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/RedstoneSpec.kt`

(The old `data/RedstoneSpec.kt` stays for now — they live in different packages so no name collision.)

- [ ] **Step 1: Create the new file**

```kotlin
package com.breadmoirai.redstonespecs.dsl

import net.minecraft.core.Vec3i

/**
 * The DSL-level spec value. Holds metadata in plain fields and the user's
 * [block] lambda; no entry list. Construction is via [redstoneSpec].
 */
class RedstoneSpec(
    val id: String,
    val bounds: Vec3i,
    val lifespan: Int,
    val structure: String?,
    val strict: Boolean,
    val block: SpecRun.() -> Unit,
) {
    init {
        require(bounds.x >= 1 && bounds.y >= 1 && bounds.z >= 1) {
            "bounds must be >= 1 on all axes, got: $bounds"
        }
        require(lifespan >= 1) { "lifespan must be >= 1, got $lifespan" }
    }

    companion object {
        val DEFAULT_BOUNDS: Vec3i = Vec3i(5, 5, 5)
    }
}

fun redstoneSpec(
    id: String,
    bounds: Vec3i = RedstoneSpec.DEFAULT_BOUNDS,
    lifespan: Int = 20,
    structure: String? = null,
    strict: Boolean = false,
    block: SpecRun.() -> Unit,
): RedstoneSpec = RedstoneSpec(id, bounds, lifespan, structure, strict, block)
```

- [ ] **Step 2: Build (will fail — `SpecRun` not yet defined)**

Skip — proceed to Task 6 which adds `SpecRun`.

### Task 6: Add `dsl/SpecRun.kt` (execution context with scheduler maps)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/SpecRun.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.dsl

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.TreeMap

/** Failure record collected by output assertions during execution. */
data class SpecFailure(val label: String, val time: SimTime, val message: String) {
    fun render(): String = "FAIL $label at tick ${time.tick}: $message"
}

/**
 * Execution context for a single [RedstoneSpec.block] invocation.
 *
 * The user's lambda runs **once** before the tick loop; calls to [input] and
 * [output] register tick-keyed callbacks into [inputActions] / [assertions].
 * The runner then dispatches those callbacks at the matching `SimTime`.
 *
 * `level` and `origin` are intentionally `internal` — the DSL methods only
 * need to register callbacks; per-tick application happens through the
 * runner, which has the same references.
 */
@SpecDslMarker
class SpecRun internal constructor(
    internal val level: ServerLevel,
    internal val origin: BlockPos,
    internal val recordingView: () -> StateRecordingViewLike,
) {
    internal val inputActions: TreeMap<SimTime, MutableList<() -> Unit>> = TreeMap()
    internal val assertions: TreeMap<SimTime, MutableList<() -> Unit>> = TreeMap()
    internal val outputDeclaredTicks: MutableMap<BlockPos, MutableSet<Int>> = mutableMapOf()
    internal val failures: MutableList<SpecFailure> = mutableListOf()

    fun input(
        x: Int, y: Int, z: Int,
        label: String = "",
        color: Int = -1,
        block: InputScope.() -> Unit,
    ) {
        InputScope(this, BlockPos(x, y, z), label, color).block()
    }

    fun output(
        x: Int, y: Int, z: Int,
        label: String = "",
        color: Int = -1,
        block: OutputScope.() -> Unit,
    ) {
        OutputScope(this, BlockPos(x, y, z), label, color).block()
    }

    internal fun scheduleInput(time: SimTime, action: () -> Unit) {
        inputActions.getOrPut(time) { mutableListOf() }.add(action)
    }

    internal fun scheduleAssertion(time: SimTime, action: () -> Unit) {
        assertions.getOrPut(time) { mutableListOf() }.add(action)
    }

    internal fun declareOutputTick(pos: BlockPos, tick: Int) {
        outputDeclaredTicks.getOrPut(pos) { mutableSetOf() }.add(tick)
    }

    internal fun reportFailure(failure: SpecFailure) {
        failures.add(failure)
    }
}

/**
 * Trim adapter so [SpecRun] doesn't take a hard dependency on the runner
 * package. The runner provides a real implementation (over `StateRecorder`'s
 * live buffer) when invoking the spec.
 */
interface StateRecordingViewLike {
    fun stateAt(pos: BlockPos, time: SimTime): net.minecraft.world.level.block.state.BlockState
    fun initialAt(pos: BlockPos): net.minecraft.world.level.block.state.BlockState
}
```

- [ ] **Step 2: Add `SpecDslMarker` annotation if not already in `dsl/`**

If Task 4 left `SpecDslMarker` outside `dsl/`, create `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/SpecDslMarker.kt`:

```kotlin
package com.breadmoirai.redstonespecs.dsl

@DslMarker
annotation class SpecDslMarker
```

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

### Task 7: Add `dsl/InputScope.kt` (direct setters)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/InputScope.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.dsl

import com.breadmoirai.redstonespecs.runner.tryApplyAsPlayerInteraction
import net.minecraft.core.BlockPos
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.LeverBlock
import net.minecraft.world.level.block.RedstoneTorchBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property

/**
 * DSL scope for a single input position. `at(tick) { … }` schedules a state
 * application at `START_OF_TICK` of `tick`. Inputs are *direct setters*: each
 * verb produces a [BlockState] (or transformer) without going through the
 * condition AST.
 */
@SpecDslMarker
class InputScope internal constructor(
    private val run: SpecRun,
    private val pos: BlockPos,
    private val label: String,
    @Suppress("unused") private val color: Int,
) {
    /** Initial-condition slot. Applies before tick 0. */
    fun atStart(block: InputAction.() -> Unit) {
        scheduleAt(SimTime.START, block)
    }

    /** Apply at `START_OF_TICK` of [tick]. */
    fun at(tick: Int, block: InputAction.() -> Unit) {
        scheduleAt(SimTime(tick, Phase.START_OF_TICK), block)
    }

    /** Explicit phase override. */
    fun at(tick: Int, phase: Phase, order: Int = 0, block: InputAction.() -> Unit) {
        scheduleAt(SimTime(tick, phase, order), block)
    }

    private fun scheduleAt(time: SimTime, block: InputAction.() -> Unit) {
        val absPos = run.origin.offset(pos)
        run.scheduleInput(time) {
            val current = run.level.getBlockState(absPos)
            val target = InputAction(current).apply(block).resolve()
            tryApplyAsPlayerInteraction(run.level, absPos, current, target)
        }
    }
}

/**
 * Builder for one tick's input action; verbs progressively transform a
 * [BlockState] starting from `current`. Final state is applied via the
 * existing player-interaction dispatch in [tryApplyAsPlayerInteraction].
 */
@SpecDslMarker
class InputAction internal constructor(private var state: BlockState) {

    fun setBlock(replacement: BlockState) { state = replacement }

    fun setPowered(value: Boolean) {
        val prop = state.block.stateDefinition.getProperty("powered")
            ?: error("Block ${state.block} has no `powered` property")
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Boolean>, value)
    }

    fun setLit(value: Boolean) {
        val prop = state.block.stateDefinition.getProperty("lit")
            ?: error("Block ${state.block} has no `lit` property")
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Boolean>, value)
    }

    fun <T : Comparable<T>> setProp(name: String, value: T) {
        @Suppress("UNCHECKED_CAST")
        val prop = state.block.stateDefinition.getProperty(name) as? Property<T>
            ?: error("Block ${state.block} has no property `$name` (or wrong value type)")
        state = state.setValue(prop, value)
    }

    fun setProp(name: String, value: String) {
        val prop = state.block.stateDefinition.getProperty(name)
            ?: error("Block ${state.block} has no property `$name`")
        val parsed = prop.getValue(value).orElseThrow {
            IllegalArgumentException("Invalid value `$value` for property `$name` on ${state.block}")
        }
        @Suppress("UNCHECKED_CAST")
        state = state.setValue(prop as Property<Comparable<Any>>, parsed as Comparable<Any>)
    }

    internal fun resolve(): BlockState = state
}
```

(`tryApplyAsPlayerInteraction` is currently a member of `SpecRunner` — Task 9 lifts it to a top-level `runner/PlayerInteractionDispatch.kt` so `InputScope` can call it without depending on the soon-to-be-deleted `SpecRunner`.)

- [ ] **Step 2: Build (defer; will fail until Task 9 lifts the dispatch helper)**

Skip until Task 9.

### Task 8: Add `dsl/OutputScope.kt` (assertion predicates)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/OutputScope.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.dsl

import net.minecraft.core.BlockPos

/**
 * DSL scope for a single output position. `at(tick) { … }` builds a
 * [StateCondition] AST (via the same condition primitives the runner
 * uses) and schedules an assertion at `END_OF_TICK` of `tick`.
 */
@SpecDslMarker
class OutputScope internal constructor(
    private val run: SpecRun,
    private val pos: BlockPos,
    private val label: String,
    @Suppress("unused") private val color: Int,
) {
    /** Pre-run sentinel; SimTime.START. */
    fun atStart(block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime.START, block, declaredTick = null)
    }

    /** Assert at `END_OF_TICK` of [tick]. */
    fun at(tick: Int, block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime(tick, Phase.END_OF_TICK), block, declaredTick = tick)
    }

    fun at(tick: Int, phase: Phase, order: Int = 0, block: ConditionScope.() -> Unit) {
        scheduleAt(SimTime(tick, phase, order), block, declaredTick = tick)
    }

    private fun scheduleAt(
        time: SimTime,
        block: ConditionScope.() -> Unit,
        declaredTick: Int?,
    ) {
        val condition = ConditionScope().apply(block).buildSingle()
        val absPos = run.origin.offset(pos)
        val labelOrPos = label.ifEmpty { pos.toString() }
        if (declaredTick != null) run.declareOutputTick(pos, declaredTick)

        run.scheduleAssertion(time) {
            val view = run.recordingView()
            val state = view.stateAt(absPos, anchorTime(time))
            if (!evaluateConditionOnState(condition, state)) {
                val expected = describeCondition(condition)
                val actual = describeStateForCondition(condition, state)
                run.reportFailure(
                    SpecFailure(labelOrPos, time, "expected $expected but got $actual")
                )
            }
        }
    }
}
```

- [ ] **Step 2: Build (defer until Task 9)**

### Task 9: Lift player-interaction dispatch out of `SpecRunner`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt:1-130` — extract `tryApplyAsPlayerInteraction` into a free function
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/PlayerInteractionDispatch.kt`

- [ ] **Step 1: Read the existing implementation**

Read `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`. Locate `tryApplyAsPlayerInteraction` (it dispatches buttons via `ButtonBlock.press`, levers, etc.).

- [ ] **Step 2: Create the new file with the function lifted as `internal fun`**

```kotlin
package com.breadmoirai.redstonespecs.runner

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState

/**
 * Apply [target] at [pos] in [level] as if a player interacted — buttons go
 * through `ButtonBlock.press` so scheduled-tick paths fire correctly; other
 * blocks fall through to a plain `setBlock`. Lifted from the old
 * `SpecRunner` so the DSL can call it without depending on that class.
 */
fun tryApplyAsPlayerInteraction(
    level: ServerLevel,
    pos: BlockPos,
    current: BlockState,
    target: BlockState,
) {
    // PASTE the body of SpecRunner.tryApplyAsPlayerInteraction here, adapting
    // member references to free-function form. Preserve all branch logic
    // (button press, lever, default setBlock).
}
```

- [ ] **Step 3: Update `SpecRunner.kt` to delegate to the free function**

Replace the in-class `tryApplyAsPlayerInteraction` with a call to the free function (or remove the in-class version if no other in-class consumer remains).

- [ ] **Step 4: Build the project**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit (Tasks 5–9 together)**

```bash
git add -A
git commit -m "feat(dsl): add new RedstoneSpec / SpecRun / Input/OutputScope alongside old DSL"
```

### Task 10: Add unit tests for the new DSL

**Files:**
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/dsl/SpecRunSchedulerTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.breadmoirai.redstonespecs.dsl

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class SpecRunSchedulerTest : FunSpec({
    test("redstoneSpec carries args; lambda is not executed at construction") {
        var ran = false
        val spec = redstoneSpec(
            id = "t",
            bounds = Vec3i(3, 3, 3),
            lifespan = 5,
        ) { ran = true }

        spec.id shouldBe "t"
        spec.bounds shouldBe Vec3i(3, 3, 3)
        spec.lifespan shouldBe 5
        spec.strict shouldBe false
        ran shouldBe false  // lambda is deferred
    }

    test("input scope schedules at START_OF_TICK; output at END_OF_TICK") {
        // This test exercises only the scheduler bookkeeping, not the level
        // mutation. Use a dummy SpecRun built without a level (see helper).
        // The scheduler maps must contain the right SimTime keys.
        // Implementation hint: extract a `SpecRun.testHarness(...)` builder
        // that bypasses the level constructor for unit tests.
        // ... details up to implementer; see the design doc Section "Core types".
    }

    test("strict flag round-trips through the value class") {
        val spec = redstoneSpec(id = "s", strict = true) { }
        spec.strict shouldBe true
    }
})
```

- [ ] **Step 2: Run tests; expect compile or fail**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.dsl.SpecRunSchedulerTest'"`
Expected: at least the scheduler test fails (no test harness yet).

- [ ] **Step 3: Add a test harness**

Add to `dsl/SpecRun.kt`:

```kotlin
/** Test-only constructor that skips the level/recording wiring. */
internal fun specRunForTest(): SpecRun {
    // For unit tests we don't have a ServerLevel. Fail fast if the level is
    // touched during scheduler-only assertions.
    val noLevel = error("level not available in scheduler-only test") as Nothing
    return SpecRun(
        level = noLevel,
        origin = BlockPos.ZERO,
        recordingView = { error("recording not available in scheduler-only test") },
    )
}
```

(Or restructure `SpecRun` so that `level` / `origin` / `recordingView` are lateinit / nullable and only enforced when the lambda actually runs — pick whichever is cleaner. Document the choice in the file's header.)

- [ ] **Step 4: Run tests; pass**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.dsl.SpecRunSchedulerTest'"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "test(dsl): SpecRun scheduler unit tests"
```

### Task 11: Add `runner/runRedstoneSpec.kt` (the new tick loop)

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/runRedstoneSpec.kt`

This replaces the testing-runner `RunRedstoneSpec.kt` and the in-mod `SpecRunner` + `SpecRunnerCoordinator` + `EngineDrivenRun` flow, but is added alongside them; cutover happens in Task 12.

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.dsl.Phase
import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import com.breadmoirai.redstonespecs.dsl.SimTime
import com.breadmoirai.redstonespecs.dsl.SpecRun
import com.breadmoirai.redstonespecs.dsl.StateRecordingViewLike
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.awaitTickEnd
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import java.util.UUID

/**
 * Run [spec] from inside a Kotest test body (or a server coroutine) and
 * assert its outputs.
 *
 *  1. Snapshot [origin] + bounds. Activate a recorder.
 *  2. Restore the snapshot so the run starts from a known state.
 *  3. Invoke [spec.block] once with a [SpecRun] backed by the live recorder
 *     buffer (`recorder.liveView()` — see Open Items in the design doc).
 *  4. For each tick `t in 0 until spec.lifespan`:
 *     - Fire `inputActions[(t, START_OF_TICK, *)]`.
 *     - `awaitTickEnd()`.
 *     - Fire `assertions[(t, END_OF_TICK, *)]`.
 *  5. If `spec.strict`, scan the recording for unexpected change-ticks at
 *     declared output positions and append failures.
 *  6. Restore the snapshot, deactivate the recorder.
 *  7. Throw [AssertionError] if any failures were collected.
 */
suspend fun runRedstoneSpec(
    level: ServerLevel,
    origin: BlockPos,
    spec: RedstoneSpec,
): StateRecording = withContext(McDispatchers.Server) {
    val snapshot = SpecSnapshot.capture(level, origin, spec.bounds)
    val recorderId = UUID.randomUUID()
    val recorder = StateRecorder.forSpec(recorderId, origin, spec.bounds)

    try {
        recorder.start(level, origin, spec.bounds)
        StateRecorder.activate(recorder)
        snapshot.restore(level)

        val run = SpecRun(
            level = level,
            origin = origin,
            recordingView = { recorderLiveView(recorder) },
        )
        spec.block(run)

        for (tick in 0 until spec.lifespan) {
            // Fire all START_OF_TICK input actions for this tick (any phase
            // counted as start-of-tick: we filter by tick + phase).
            val startKey = SimTime(tick, Phase.START_OF_TICK)
            run.inputActions
                .subMap(SimTime(tick, Phase.START_OF_TICK, Int.MIN_VALUE), true,
                        SimTime(tick, Phase.START_OF_TICK, Int.MAX_VALUE), true)
                .values.flatten().forEach { it() }

            awaitTickEnd()

            run.assertions
                .subMap(SimTime(tick, Phase.END_OF_TICK, Int.MIN_VALUE), true,
                        SimTime(tick, Phase.END_OF_TICK, Int.MAX_VALUE), true)
                .values.flatten().forEach { it() }
        }

        if (spec.strict) {
            scanForUnexpectedChanges(recorder, run, spec.lifespan)
        }

        if (run.failures.isNotEmpty()) {
            throw AssertionError(
                "assertOutputsMatch failed:\n" + run.failures.joinToString("\n") { it.render() }
            )
        }
    } finally {
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }

    return@withContext recorder.toRecording()
}

private fun recorderLiveView(recorder: StateRecorder): StateRecordingViewLike {
    // See Open Items in the design doc — Phase 2 must expose a live read API
    // on StateRecorder. Until that exists, fall back to building a recording
    // snapshot on each call (slower but correct for short tests).
    return object : StateRecordingViewLike {
        override fun stateAt(pos: net.minecraft.core.BlockPos, time: com.breadmoirai.redstonespecs.dsl.SimTime) =
            StateRecordingView.of(recorder.toRecording()).stateAt(pos, time)

        override fun initialAt(pos: net.minecraft.core.BlockPos) =
            recorder.toRecording().initialSnapshot[pos]
                ?: error("Position $pos not in recorder snapshot")
    }
}

private fun scanForUnexpectedChanges(
    recorder: StateRecorder,
    run: SpecRun,
    lifespan: Int,
) {
    val recording = recorder.toRecording()
    val view = StateRecordingView.of(recording)
    for ((pos, declaredTicks) in run.outputDeclaredTicks) {
        val initial = recording.initialSnapshot[pos] ?: continue
        var prev = initial
        for (t in 0 until lifespan) {
            val cur = view.stateAt(pos, SimTime(t, Phase.END_OF_TICK, Int.MAX_VALUE))
            if (cur != prev && t !in declaredTicks) {
                run.reportFailure(
                    com.breadmoirai.redstonespecs.dsl.SpecFailure(
                        label = pos.toString(),
                        time = SimTime(t, Phase.END_OF_TICK),
                        message = "unexpected change (expected no change, got changed)",
                    )
                )
            }
            prev = cur
        }
    }
}
```

- [ ] **Step 2: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(runner): add runRedstoneSpec for the new DSL"
```

---

## Phase 3 — Cutover to the new DSL

Flip every consumer (testing harness, recorder block, runner block, kts loader) to the new DSL. The old code becomes dead but stays compiled until Phase 6 deletes it.

### Task 12: Switch `testing/runner/RunRedstoneSpec.kt` to use the new engine

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RunRedstoneSpec.kt`
- Modify (or replace): `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RedstoneSpecAssertions.kt` — its job is now done inside the new engine, so drop the function or keep as a thin wrapper that throws if called with the old type.

- [ ] **Step 1: Read the current `RunRedstoneSpec.kt`**

The existing function takes the old `data.RedstoneSpec` and runs it via the old `SpecRunnerCoordinator`. Replace its implementation to take the new `dsl.RedstoneSpec` and delegate to the new `runner.runRedstoneSpec`.

- [ ] **Step 2: Update the suspend function signature and body**

```kotlin
package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.runner.runRedstoneSpec as runEngine
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import kotlin.coroutines.coroutineContext

suspend fun runRedstoneSpec(
    spec: RedstoneSpec,
    originPos: BlockPos,
    level: ServerLevel,
): StateRecording {
    val recording = runEngine(level, originPos, spec)
    coroutineContext[RecordingHolder]?.recording = recording
    return recording
}
```

(Drop the old assertion call — assertions are inline in the new engine.)

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses"`
Expected: BUILD SUCCESSFUL. Existing gametests will fail to compile if any still use the old `data.RedstoneSpec` type — fix call-sites by pointing at the new DSL.

- [ ] **Step 4: Run tests + gametests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: PASS (gametests are placeholder stubs per `gametest/INDEX.md`; if any non-stub fails, follow up on its call-site).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(runner): testing-harness uses new DSL engine"
```

### Task 13: Add `runner/RecordingDslEmitter.kt`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingDslEmitter.kt`

Replaces the `RecordingFinalizer` → `KtsSpecEmitter` two-step. Walks a `StateRecording` directly and emits `.spec.kts` text.

- [ ] **Step 1: Read the current finalizer + emitter for the derivation rules**

Read `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt` and `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt`. Identify:
- How input change-ticks are detected.
- How condition derivation maps `RedstoneTorch.LIT` → `lit()`, `DiodeBlock.POWERED` → `powered()`, fallthrough to `prop("name", "value")`.
- The `(pos, kind, label, color)` markers read off the BE.

- [ ] **Step 2: Write the emitter**

```kotlin
package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Walks a [StateRecording] and produces `.spec.kts` source text. The output
 * uses the new imperative DSL: `setProp`/`setPowered`/`setLit` for inputs
 * (specialized where the property is well-known; falls back to `setProp`),
 * and `powered()`/`lit()`/`prop()` predicates for outputs.
 *
 * Inputs and outputs are identified by markers on the BE; the emitter takes
 * those as arguments rather than reading the BE itself.
 */
data class EntryMarker(
    val pos: BlockPos,
    val label: String,
    val color: Int,
    val kind: Kind,
) {
    enum class Kind { INPUT, OUTPUT }
}

object RecordingDslEmitter {
    fun emit(
        id: String,
        bounds: Vec3i,
        lifespan: Int,
        structure: String?,
        strict: Boolean,
        markers: List<EntryMarker>,
        recording: StateRecording,
    ): String {
        // 1. Header line: redstoneSpec(id = ..., bounds = Vec3i(...), ...)
        // 2. For each marker:
        //    - If INPUT: emit input(x, y, z, label = ...) { at(t) { setX(...) } }
        //      where `setX(...)` is derived from the change between (t-1) and (t)
        //    - If OUTPUT: emit output(x, y, z, label = ...) { at(t) { <predicate> } }
        // 3. Group lines by position so the file reads naturally.
        //
        // Keep the structure deterministic: sort markers by (kind, pos), then
        // sort each at(...) block by tick.
        TODO(
            "Implement using the same change-detection + condition-derivation " +
                    "rules as the old RecordingFinalizer + KtsSpecEmitter. The output " +
                    "must be a valid .spec.kts that re-loads to an equivalent RedstoneSpec."
        )
    }
}
```

(Plan note: the implementing agent should port the change-detection logic from `RecordingFinalizer.kt:1-115` and the property-rendering logic from `KtsSpecEmitter.kt:1-148`. Both files are short — the port is mechanical.)

- [ ] **Step 3: Add a unit test asserting the emitter output round-trips**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/runner/RecordingDslEmitterTest.kt` with:
- A hand-rolled fake `StateRecording` covering: lever input, comparator output, redstone torch output.
- Call `RecordingDslEmitter.emit(...)`.
- Assert key substrings are present (`redstoneSpec(`, `setPowered(true)`, `at(2) { lit() }`, etc.).

(The full round-trip via `KtsSpecLoader` requires an active script host — defer that to a gametest.)

- [ ] **Step 4: Build + test**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests 'com.breadmoirai.redstonespecs.runner.RecordingDslEmitterTest'"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(runner): RecordingDslEmitter — recording → .spec.kts text"
```

### Task 14: Switch `RedstoneSpecRecorderBlock` to call `RecordingDslEmitter`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt` if needed — add a `writeSpecKts(path, source)` that takes raw text instead of a `RedstoneSpec`.

- [ ] **Step 1: Read the recorder block's finalize path**

Identify the call sequence: `RecorderBlock.onUse` (or hook) → `RecordingFinalizer.finalize` → `KtsSpecEmitter.emit` → `SpecPersistence.write`.

- [ ] **Step 2: Replace with the new emitter**

Build the marker list from the BE state, call `RecordingDslEmitter.emit(...)` to produce text, then write text to disk via `SpecPersistence.writeSpecKts(path, source)`.

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(block): recorder block emits .spec.kts via RecordingDslEmitter"
```

### Task 15: Switch `KtsSpecLoader` and the `.spec.kts` script type to return new `RedstoneSpec`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt` — change `@KotlinScript` `provided` imports to `dsl/`; change return type bound.
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt` — return `dsl.RedstoneSpec`.
- (Phase 4 will move both files into `persistence/`.)

- [ ] **Step 1: Update `SpecScript.kt`**

Change the script's `defaultImports` to point to `com.breadmoirai.redstonespecs.dsl.*` (and `net.minecraft.core.Vec3i` for bounds). The script body now invokes the new top-level `redstoneSpec(...)` function.

- [ ] **Step 2: Update `KtsSpecLoader.kt`**

Change return type from `data.RedstoneSpec` to `dsl.RedstoneSpec`. Update the cast that unwraps the script result accordingly.

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run kts loader tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests '*KtsSpec*'"`
Expected: PASS or — if the existing tests pin the old `data.RedstoneSpec` return — update those tests to assert against the new shape.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(persistence): .spec.kts now returns the new dsl.RedstoneSpec"
```

### Task 16: Switch `RedstoneSpecRunnerBlock` to call `runRedstoneSpec` directly

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt`
- Possibly modify: `src/main/kotlin/com/breadmoirai/redstonespecs/event/SubTickPhaseEvents.kt` — if the runner block was relying on `SpecRunnerCoordinator`, drop that dependency.

- [ ] **Step 1: Read the current runner-block run path**

Identify how the runner block currently kicks off a run (likely via `SpecRunnerCoordinator.register(...)`).

- [ ] **Step 2: Replace with direct `runRedstoneSpec` call**

Launch `runRedstoneSpec(level, origin, spec)` from a server-side coroutine. On completion or `AssertionError`, push a result summary to the client via `RunnerStatus` (Phase 4 will add this packet — for now log to server console).

- [ ] **Step 3: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL. The old coordinator + SpecRunner are now unreferenced (still compiled, deleted in Phase 6).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(block): runner block calls runRedstoneSpec directly"
```

---

## Phase 4 — Slim block UI

Add the new screens, new packets, directory scan. Replace the editor screen's open-on-rightclick with the kind-specific screens. Old editor screen and old packets stay reachable only from the old editor block (which is deleted in Phase 5).

### Task 17: Add `persistence/SpecDirectoryScan.kt`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecDirectoryScan.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.breadmoirai.redstonespecs.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name

/**
 * Server-side scan of the world's spec directory. Returns a sorted list of
 * `.spec.kts` filenames (paths relative to [specsDir]); the runner screen
 * uses this to populate its picker dropdown.
 */
object SpecDirectoryScan {
    fun list(specsDir: Path): List<String> {
        if (!Files.isDirectory(specsDir)) return emptyList()
        return Files.list(specsDir).use { stream ->
            stream
                .filter { it.name.endsWith(".spec.kts") }
                .map { it.name }
                .sorted()
                .toList()
        }
    }
}
```

- [ ] **Step 2: Add a JVM unit test**

Create `src/test/kotlin/com/breadmoirai/redstonespecs/persistence/SpecDirectoryScanTest.kt`:
- Use `kotlin.io.path.createTempDirectory`.
- Create three files: `a.spec.kts`, `z.spec.kts`, `not-a-spec.txt`.
- Assert `list(...)` returns `["a.spec.kts", "z.spec.kts"]`.

- [ ] **Step 3: Build + test + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:test --tests '*SpecDirectoryScan*'"`
Expected: PASS.

```bash
git add -A
git commit -m "feat(persistence): SpecDirectoryScan for runner picker"
```

### Task 18: Add the new packets to `network/Packets.kt`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`

- [ ] **Step 1: Add 7 new payloads**

Append (do not remove old payloads yet):

```kotlin
// Recorder
data class SetRecorderConfigC2S(
    val originPos: BlockPos,
    val specId: String,
    val outPath: String,
    val structureId: String,
)

enum class RecorderCmd { START, STOP, DISCARD }
data class RecorderCommandC2S(val originPos: BlockPos, val cmd: RecorderCmd)

// Runner
data class SetRunnerConfigC2S(val originPos: BlockPos, val specPath: String)
enum class RunnerCmd { PLACE_STRUCTURE, RUN, RESTORE }
data class RunnerCommandC2S(val originPos: BlockPos, val cmd: RunnerCmd)

data class OpenRecorderScreenS2C(
    val originPos: BlockPos,
    val specId: String,
    val outPath: String,
    val structureId: String,
    val state: String,        // idle / recording / finalized
)

data class OpenRunnerScreenS2C(
    val originPos: BlockPos,
    val specPath: String,
    val specList: List<String>,
    val meta: RunnerMetaSnapshot?, // id/bounds/lifespan/structure once a spec is loaded
)

data class RunnerMetaSnapshot(
    val id: String,
    val boundsX: Int, val boundsY: Int, val boundsZ: Int,
    val lifespan: Int,
    val structure: String?,
)

enum class RunnerState { IDLE, RUNNING, PASS, FAIL }
data class RunnerStatusS2C(
    val originPos: BlockPos,
    val state: RunnerState,
    val summary: String,
)
```

For each payload, add the `Type<T>` + `StreamCodec<RegistryFriendlyByteBuf, T>` handle following the existing pattern in the file (read the file first to mirror the style). All payloads use `BlockPos.STREAM_CODEC`, `ByteBufCodecs.STRING_UTF8`, and `ByteBufCodecs.VAR_INT.map(enumByOrdinal)` for enums.

- [ ] **Step 2: Register in `NetworkRegistry.kt`**

Add 7 `PayloadTypeRegistry.playC2S/playS2C.register(...)` lines following the existing registrations.

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat(network): add slim recorder/runner config + command packets"
```

### Task 19: Add `client/screen/RecorderScreen.kt`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderScreen.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt` — on `useWithoutItem`, fire `OpenRecorderScreenS2C`.
- Modify: client-side network handler to open the screen on receipt.

- [ ] **Step 1: Build the screen**

A standard MC `Screen`. Layout: 3 `EditBox`es (specId, outPath, structureId), 3 `Button`s (Start, Stop, Discard). Each EditBox change → `SetRecorderConfigC2S`. Each button click → `RecorderCommandC2S`.

(Use the existing `EditBox.responder` pattern — note from memory: don't manually fire onChange after setValue, the responder fires it.)

- [ ] **Step 2: Wire server-side handlers**

Server receives `SetRecorderConfigC2S` → validates BE at `originPos` → updates BE NBT. Receives `RecorderCommandC2S` → drives `StateRecorder` (start, stop+emit, discard).

- [ ] **Step 3: Wire the right-click path**

In `RedstoneSpecRecorderBlock.useWithoutItem`, send `OpenRecorderScreenS2C` to the player; client handler opens the screen.

- [ ] **Step 4: Manual smoke test in dev**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClient"`
Place a recorder, right-click. Expected: RecorderScreen opens, fields editable, start/stop buttons send packets (verify via server log).

- [ ] **Step 5: Build + commit**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes"`
Expected: BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "feat(client): RecorderScreen + open-on-rightclick"
```

### Task 20: Add `client/screen/RunnerScreen.kt`

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerScreen.kt`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt` — on `useWithoutItem`, scan dir, fire `OpenRunnerScreenS2C`.
- Modify: client-side network handler to open the screen.

- [ ] **Step 1: Build the screen**

Layout: spec dropdown (populate from `specList` in the open packet), 3 buttons (Place, Run, Restore), read-only meta panel (id/bounds/lifespan/structure) shown only after a spec is loaded.

For the dropdown: prefer `CycleButton<String>` over a custom dropdown widget (avoids the dropdown-clipping issue documented in memory).

- [ ] **Step 2: Server handler**

Server receives `SetRunnerConfigC2S` → validates BE → loads `.spec.kts` via `KtsSpecLoader` → stores spec on BE → pushes `OpenRunnerScreenS2C` (or a meta-only update payload) with the new meta. Receives `RunnerCommandC2S(PLACE_STRUCTURE)` → `StructurePersistence.load(spec.structure).place(level, origin)`. `RUN` → launch `runRedstoneSpec` server-side coroutine; on completion push `RunnerStatusS2C`. `RESTORE` → snapshot restore.

- [ ] **Step 3: Build + smoke test**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClient"`
Place a runner, right-click. Expected: dropdown lists `.spec.kts` files; selecting one loads meta; Place stamps the structure; Run triggers the run and a status comes back.

- [ ] **Step 4: Build + commit**

```bash
git add -A
git commit -m "feat(client): RunnerScreen + place/run/restore flow"
```

### Task 21: Replace editor-screen open-on-rightclick on the recorder/runner blocks

**Files:**
- Verify Tasks 19 + 20 already removed the old `OpenSpecEditorScreen` payload trigger from those blocks.
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt` — drop the editor branch from BE state if Phase 3 didn't.

- [ ] **Step 1: Search for any remaining editor-open paths from the recorder/runner blocks**

```bash
grep -rn "SpecEditor\|OpenSpecEditor" src/main/kotlin/com/breadmoirai/redstonespecs/block/
```

- [ ] **Step 2: Remove or redirect any hits**

Recorder + runner blocks must only open their respective new screens.

- [ ] **Step 3: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(block): recorder/runner blocks only use the new screens"
```

---

## Phase 5 — Delete the editor stack

Pure subtraction. After this phase, the only block kinds are recorder and runner; the only screens are RecorderScreen and RunnerScreen.

### Task 22: Delete the editor block + editor screens + editor packets

**Files:**
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecEditorBlock.kt`
- Delete: `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockKind.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerTimelineScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerSpecPickerScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderSetupScreen.kt`
- Delete: `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/DropdownButton.kt`, `IntEditBox.kt`, `IntStepper.kt`, `FlatRow.kt`, `ColorSwatchWidget.kt` (if not referenced by RecorderScreen/RunnerScreen — verify with grep first)
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` — remove every payload that carried `RedstoneSpec`/`SpecEntry`/`StateCondition`
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` — drop the corresponding registrations
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/Redstonespecs.kt` and `ModRegistries.kt` — drop the editor block registration

- [ ] **Step 1: Identify which client-side widgets are still referenced**

```bash
grep -rln "DropdownButton\|IntEditBox\|IntStepper\|FlatRow\|ColorSwatchWidget" src/client/kotlin/ | grep -v -E "DropdownButton|IntEditBox|IntStepper|FlatRow|ColorSwatchWidget"
```

Keep the ones still referenced by `RecorderScreen`/`RunnerScreen`; delete the rest.

- [ ] **Step 2: Delete the editor block + its registration**

```bash
git rm src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecEditorBlock.kt
git rm src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockKind.kt
```

Edit `ModRegistries.kt` and `Redstonespecs.kt`: remove every reference to `RedstoneSpecEditorBlock` and `SpecBlockKind`. Adjust `SpecBlockEntity` if it switched on `SpecBlockKind` — collapse to two-block kinds inline (the BE itself is shared by both remaining blocks).

- [ ] **Step 3: Delete editor screens**

```bash
git rm src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecOverviewScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecBoundsScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecFileBrowserScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerTimelineScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerSpecPickerScreen.kt \
       src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderSetupScreen.kt
```

(Plus the unreferenced widgets from Step 1.)

- [ ] **Step 4: Delete editor packets**

In `Packets.kt`: remove every payload data class that carries `RedstoneSpec`, `SpecEntry`, `StateCondition`, `SimTime` (the data-bearing edit/sync packets). Leave only the slim recorder/runner packets from Task 18 and any non-spec payloads (managed-worlds packets are in `network/managed/` and are out of scope).

In `NetworkRegistry.kt`: drop the registrations for the deleted payloads.

- [ ] **Step 5: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL. Any compile errors point to leftover consumers — fix or delete those too.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: delete editor block, editor screens, edit packets"
```

---

## Phase 6 — Delete `data/` and the dead engine code

Final clean state.

### Task 23: Delete the `data/` package

**Files (all to delete):**
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/RedstoneSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/SpecEntry.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/EntryKind.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/SpecDsl.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/dsl/EntryDsl.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecJsonCodec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecEmitter.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecLiteralCapture.kt` (no longer used after editor removal)

**Files to move out of `data/`:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/KtsSpecLoader.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt` → `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecScript.kt`

- [ ] **Step 1: Move the kept files**

```bash
git mv src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/KtsSpecLoader.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/persistence/KtsSpecLoader.kt
git mv src/main/kotlin/com/breadmoirai/redstonespecs/data/serial/SpecScript.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecScript.kt
```

Update package declarations in both moved files: `data.serial` → `persistence`.

Update consumers' imports:
```bash
grep -rl "com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader\|com.breadmoirai.redstonespecs.data.serial.SpecScript" src/ | \
  xargs sed -i 's/com\.breadmoirai\.redstonespecs\.data\.serial\.KtsSpecLoader/com.breadmoirai.redstonespecs.persistence.KtsSpecLoader/g; s/com\.breadmoirai\.redstonespecs\.data\.serial\.SpecScript/com.breadmoirai.redstonespecs.persistence.SpecScript/g'
```

- [ ] **Step 2: Delete the rest of `data/`**

```bash
git rm -r src/main/kotlin/com/breadmoirai/redstonespecs/data/
```

(`git rm -r` will fail if anything is left — that's a feature; investigate any leftovers.)

- [ ] **Step 3: Full build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: delete data/ package; KtsSpecLoader/SpecScript move to persistence/"
```

### Task 24: Delete the dead engine code

**Files to delete:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/EngineDrivenRun.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RedstoneSpecAssertions.kt` (its function is inlined into the new engine)

- [ ] **Step 1: Verify no remaining references**

```bash
grep -rn "SpecRunner\|SpecRunnerCoordinator\|EngineDrivenRun\|RecordingFinalizer\|assertOutputsMatch" src/ | grep -v "runRedstoneSpec.kt"
```

Expected: only references inside the to-be-deleted files themselves, plus the renamed `runRedstoneSpec`. Any other references must be cleaned up first.

- [ ] **Step 2: Delete files**

```bash
git rm src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunner.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecRunnerCoordinator.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/runner/EngineDrivenRun.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingFinalizer.kt \
       src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RedstoneSpecAssertions.kt
```

- [ ] **Step 3: Full build + tests**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"`
Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: BOTH SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor(runner): delete SpecRunner / Coordinator / EngineDrivenRun / RecordingFinalizer"
```

### Task 25: Update docs

**Files:**
- Modify: `docs/architecture/module-map.md` — replace the `data/` section with `dsl/`; replace the runner section to drop the per-runner trio; update the dependency-direction diagram.
- Modify: `docs/architecture/INDEX.md` if any article title changed.
- Modify: `docs/runner/INDEX.md` — drop the engine-driven-verification reference if its article became stale, or update the article to point at `runRedstoneSpec`.
- Modify: `docs/persistence/INDEX.md` — note `KtsSpecLoader`/`SpecScript` now live in `persistence/`.
- Modify: `docs/ui/INDEX.md` — drop editor-screen references; add RecorderScreen / RunnerScreen entries.
- Modify: `docs/gametest/INDEX.md` — note that gametests now author against the new DSL.

- [ ] **Step 1: Update each INDEX.md and the affected articles**

For each article that referenced a deleted/moved type, update the path and prose. For deleted articles (e.g. anything specifically about `SpecEntry` or the old `RedstoneSpec`), remove or rewrite.

- [ ] **Step 2: Verify no broken links**

```bash
grep -rn "data/RedstoneSpec\|data/SpecEntry\|SpecRunnerCoordinator\|RecordingFinalizer\|SpecEditorScreen" docs/
```

Expected: only references in `docs/superpowers/specs/` (historical specs are immutable) and in this plan file itself.

- [ ] **Step 3: Commit**

```bash
git add docs/
git commit -m "docs: sync architecture/UI/runner indexes with the new DSL-only shape"
```

---

## Done

After Task 25:
- `data/` package gone.
- DSL is the single source of truth: `redstoneSpec(...) { … }` from `.spec.kts` files; the `SpecRun.() -> Unit` lambda *is* the spec.
- Recorder block emits `.spec.kts` text directly via `RecordingDslEmitter`.
- Runner block calls `runRedstoneSpec(level, origin, spec)` directly.
- Two slim screens — RecorderScreen, RunnerScreen — replace the editor.
- ~1500 lines of `data/` + dead engine + editor UI deleted.
