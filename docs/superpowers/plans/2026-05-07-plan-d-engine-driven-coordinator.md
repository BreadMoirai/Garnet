# Plan D — Engine-driven coordinator; in-game Run launches Kotest

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a user clicks Run on a `GarnetRunnerBlock`, the server loads `<id>.spec.kts` to a Kotest `Spec` class and launches it via `KotestLauncher` on a worker thread. Replace `SpecRunnerCoordinator.finishRun`'s direct `OutputVerifier.verify` call with an engine-driven flow whose `LauncherResult` is converted to the existing `TestResult` payload.

**Architecture:** `SpecRunnerCoordinator.startRun` becomes a thin shim that:
1. Resolves the saved `.spec.kts` (or emits one from the BE's in-memory spec for unsaved runs).
2. Calls `KotestLauncher.launchKotest(...)` on a non-server worker thread, passing the loaded `KClass<out Spec>`.
3. Translates `LauncherResult` → `TestResult` and pushes via `TestResultS2CPayload` exactly as today.

The "originPos / level" identifiers used in emitted `.spec.kts` (Plan C) become receiver-bound properties on `GarnetTestSpec` itself, populated from a `ThreadLocal` set by the launcher before `KClass.constructors.first().call()`.

**Tech Stack:** Kotest engine, kotlinx-coroutines, Fabric server thread + worker pool.

**Spec reference:** Spec §"In-game run lifecycle".

**Depends on:** Plans A, B, C.

---

## File structure (after this plan)

**New:**
- `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpecContext.kt` — thread-local context holding `originPos` and `level` for an active engine run.
- `src/main/kotlin/com/breadmoirai/garnet/runner/EngineDrivenRun.kt` — translates a `BE → KClass<Spec> → LauncherResult → TestResult` flow.

**Modified:**
- `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt` — adds receiver-bound `originPos: BlockPos` and `level: ServerLevel` properties read from the thread-local context.
- `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt` — `startRun` and `finishRun` rewritten to call `EngineDrivenRun.run(...)`.
- `src/main/kotlin/com/breadmoirai/garnet/data/serial/KtsSpecEmitter.kt` — confirm the emitted reference to `originPos`/`level` matches the new receiver members (Plan C placeholder becomes real).

**Deleted:**
- `src/main/kotlin/com/breadmoirai/garnet/runner/OutputVerifier.kt` — its caller is gone after this plan.

---

## Task 1: Add receiver-bound `originPos` / `level` to `GarnetTestSpec`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpecContext.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt`

- [ ] **Step 1: Create the context holder**

```kotlin
package com.breadmoirai.garnet.testing

import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Per-thread context for an engine-driven GarnetTestSpec run.
 *
 * The engine launcher (Plan D's EngineDrivenRun) sets this before instantiating the
 * Spec class. The Spec's `originPos` / `level` getters read from here. The context is
 * cleared after the run completes.
 */
internal object GarnetTestSpecContext {
    private val ctx = ThreadLocal<Binding?>()

    data class Binding(val originPos: BlockPos, val level: ServerLevel)

    fun bind(originPos: BlockPos, level: ServerLevel) {
        ctx.set(Binding(originPos, level))
    }

    fun clear() = ctx.remove()

    fun current(): Binding =
        ctx.get() ?: error("No GarnetTestSpecContext bound on this thread. " +
            "GarnetTestSpec must be instantiated via EngineDrivenRun.run(...).")
}
```

- [ ] **Step 2: Wire `GarnetTestSpec`**

Modify `GarnetTestSpec.kt`:

```kotlin
@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.garnet.testing

import com.breadmoirai.garnet.testing.core.McDispatchers
import io.kotest.core.concurrency.CoroutineDispatcherFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

abstract class GarnetTestSpec(body: GarnetTestSpec.() -> Unit = {}) : FunSpec() {

    /** World-relative origin of this spec's run. Bound by EngineDrivenRun before instantiation. */
    val originPos: BlockPos get() = GarnetTestSpecContext.current().originPos

    /** Server level for this spec's run. Bound by EngineDrivenRun before instantiation. */
    val level: ServerLevel get() = GarnetTestSpecContext.current().level

    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(McDispatchers.Server) { block() }
            override fun close() = Unit
        }
        body()
    }
}
```

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpec.kt \
        src/main/kotlin/com/breadmoirai/garnet/testing/GarnetTestSpecContext.kt
git commit -m "feat(testing): GarnetTestSpec exposes thread-local originPos/level"
```

---

## Task 2: `EngineDrivenRun` — orchestrate one spec's engine launch

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/runner/EngineDrivenRun.kt`

- [ ] **Step 1: Implement**

```kotlin
package com.breadmoirai.garnet.runner

import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.data.SimTime
import com.breadmoirai.garnet.data.TestResult
import com.breadmoirai.garnet.data.TickCheck
import com.breadmoirai.garnet.data.serial.KtsSpecEmitter
import com.breadmoirai.garnet.data.serial.KtsSpecLoader
import com.breadmoirai.garnet.testing.GarnetTestSpecContext
import com.breadmoirai.garnet.testing.launcher.LauncherResult
import com.breadmoirai.garnet.testing.launcher.launchKotest
import io.kotest.core.spec.Spec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.reflect.KClass

private val LOGGER = LoggerFactory.getLogger("Garnet")

/** Single worker thread for engine launches; we never run two engines in parallel against one server. */
private val engineExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "garnet-kotest-engine").apply { isDaemon = true }
}

object EngineDrivenRun {
    /**
     * Compile [spec] to a Kotest Spec class (via emit→load), launch the engine on a worker thread,
     * and return a [TestResult] for [TestResultS2CPayload].
     *
     * Must be called from the server thread; blocks until the engine completes.
     * The engine itself dispatches test bodies back to the server thread via GarnetTestSpec's
     * CoroutineDispatcherFactory, so the calling server thread will yield while the engine runs.
     */
    fun run(spec: GarnetSpec, originPos: BlockPos, level: ServerLevel): TestResult {
        val source = KtsSpecEmitter.emit(spec)
        val klass: KClass<out Spec> = KtsSpecLoader.loadSpec(source, "${spec.id}.spec.kts")

        // Bind context for the engine's instantiation of the Spec class.
        GarnetTestSpecContext.bind(originPos, level)
        val launcherResult: LauncherResult = try {
            launchKotest(
                sourceSet = "runtime",
                reportsDir = reportsDir(level),
                specs = listOf(klass),
            )
        } finally {
            GarnetTestSpecContext.clear()
        }
        return toTestResult(spec.id, launcherResult)
    }

    private fun reportsDir(level: ServerLevel): Path {
        val saveRoot = level.server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
        val dir = saveRoot.resolve("garnet-reports")
        Files.createDirectories(dir)
        return dir
    }

    private fun toTestResult(specId: String, lr: LauncherResult): TestResult {
        // The launcher's per-test failures map 1:1 onto TickCheck rows. We don't have per-tick
        // granularity from the engine yet (Plan E adds DiagnosticRecorder which surfaces it),
        // so for now: one TickCheck per launcher failure with simTime=START as a placeholder.
        val checks: List<TickCheck> = lr.errors.map { e ->
            TickCheck(SimTime.START, e.name, expected = "(see test message)", actual = e.message, pass = false)
        } + List(lr.passed) { TickCheck(SimTime.START, "passed", "ok", "ok", pass = true) }
        return TestResult(specId, System.currentTimeMillis(), checks)
    }
}
```

- [ ] **Step 2: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/runner/EngineDrivenRun.kt
git commit -m "feat(runner): EngineDrivenRun orchestrates one Kotest engine launch per in-game run"
```

---

## Task 3: Replace `SpecRunnerCoordinator.startRun` / `finishRun`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt`

- [ ] **Step 1: Rewrite the BE-driven path to use `EngineDrivenRun`**

The file currently does record-replay-verify via `OutputVerifier`. Replace `startRun` with:

```kotlin
fun startRun(be: SpecBlockEntity) {
    val spec = be.spec ?: return
    val level = be.level as? ServerLevel ?: return
    LOGGER.debug("[SpecRunnerCoordinator#startRun] starting '{}' via Kotest engine", spec.id)

    // Engine drives record/run/verify itself via runGarnetSpec inside the spec body.
    // Run on a dedicated thread so the server thread can keep ticking; the engine yields
    // back to the server thread via GarnetTestSpec's dispatcher.
    Thread({
        val testResult = try {
            EngineDrivenRun.run(spec, be.blockPos, level)
        } catch (t: Throwable) {
            LOGGER.warn("[SpecRunnerCoordinator] engine crashed for '{}'", spec.id, t)
            com.breadmoirai.garnet.data.TestResult(
                spec.id, System.currentTimeMillis(),
                listOf(com.breadmoirai.garnet.data.TickCheck(
                    com.breadmoirai.garnet.data.SimTime.START,
                    "engine-error", "ok", t.message ?: "(no message)", pass = false,
                )),
            )
        }
        // Hop back to server thread to update BE + send packet.
        level.server.execute {
            be.setLastTestResult(testResult)
            net.fabricmc.fabric.api.networking.v1.PlayerLookup.level(level).forEach { player ->
                net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                    com.breadmoirai.garnet.network.TestResultS2CPayload(be.blockPos, testResult))
            }
        }
    }, "garnet-engine-launch-${spec.id}").start()
}
```

Delete `finishRun(...)`, `tickRunners(...)`'s standalone-runner branch (the standalone path is now driven by `runGarnetSpec` from inside test bodies, but it still needs phase forwarding — keep that), and remove the `runners`/`snapshots`/`stateRecorders` maps if no longer referenced.

> **Important:** the standalone-runner registration from Plan B is still needed — it's how `runGarnetSpec` (called from inside Kotest bodies) gets phase events. Keep `registerStandalone` / `unregisterStandalone` and the standalone branch in `tickRunners`. Only the *BE-driven* maps and `finishRun` go away.

After rewrite, `SpecRunnerCoordinator.kt` should look roughly like:

```kotlin
object SpecRunnerCoordinator {
    private val LOGGER = LoggerFactory.getLogger("Garnet")
    private val standaloneRunners = mutableListOf<SpecRunner>()

    fun registerStandalone(runner: SpecRunner) { standaloneRunners += runner }
    fun unregisterStandalone(runner: SpecRunner) { standaloneRunners.remove(runner) }

    fun startRun(be: SpecBlockEntity) { /* engine launch as above */ }

    fun resetSpec(be: SpecBlockEntity) {
        // No BE-tracked runner state to reset anymore; just clear the BE-side recorded result.
        be.setLastTestResult(null)
    }

    fun onPhase(level: ServerLevel, phase: Phase) {
        for (recorder in StateRecorder.activeRecorders()) {
            if (phase == Phase.START_OF_TICK) recorder.onTickStart()
            recorder.onPhaseStart(phase)
        }
        val completed = mutableListOf<SpecRunner>()
        for (runner in standaloneRunners) {
            if (runner.level !== level) continue
            if (runner.onPhase(phase)) completed += runner
        }
        standaloneRunners.removeAll(completed)
    }
}
```

> If `SpecBlockEntity.setLastTestResult` doesn't accept null, add an overload or leave the BE alone in `resetSpec` — confirm the existing API.

- [ ] **Step 2: Delete `OutputVerifier.kt`**

```bash
git rm src/main/kotlin/com/breadmoirai/garnet/runner/OutputVerifier.kt
```

If any test references `OutputVerifier`, update it to call `assertOutputsMatch` from Plan B instead, OR delete the test if it duplicates `GarnetAssertionsTest`.

- [ ] **Step 3: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/runner/SpecRunnerCoordinator.kt \
        src/main/kotlin/com/breadmoirai/garnet/runner/OutputVerifier.kt
git commit -m "refactor(runner): coordinator launches Kotest engine; OutputVerifier removed"
```

---

## Task 4: End-to-end clientTest — Run button drives the engine

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/test/RunnerBlockEngineE2ETest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.data.GarnetSpec
import com.breadmoirai.garnet.data.dsl.garnetSpec
import com.breadmoirai.garnet.data.serial.KtsSpecEmitter
import com.breadmoirai.garnet.persistence.SpecPersistence
import com.breadmoirai.garnet.runner.EngineDrivenRun
import com.breadmoirai.garnet.testing.GarnetTestSpec
import com.breadmoirai.garnet.testing.core.McDispatchers
import io.kotest.matchers.collections.shouldNotBeEmpty
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class RunnerBlockEngineE2ETest : GarnetTestSpec({
    test("EngineDrivenRun completes a trivial spec end-to-end") {
        val spec = garnetSpec("e2e-trivial") {
            bounds(1, 1, 1)
            lifespan = 2
        }
        val server = McDispatchers.currentServer
        val level = server.overworld()

        val result = EngineDrivenRun.run(spec, BlockPos(0, 64, 0), level)
        result.checks.shouldNotBeEmpty()
    }
})
```

- [ ] **Step 2: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientTest"`
Expected: in `build/reports/garnet/clientTest/`, `RunnerBlockEngineE2ETest` PASSes.

- [ ] **Step 3: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/garnet/test/RunnerBlockEngineE2ETest.kt
git commit -m "test(clientTest): EngineDrivenRun end-to-end on trivial spec"
```

---

## Verification checklist

- [ ] `GarnetTestSpec.originPos` and `GarnetTestSpec.level` resolve from `GarnetTestSpecContext`.
- [ ] `EngineDrivenRun.run(spec, originPos, level)` returns a `TestResult`.
- [ ] `SpecRunnerCoordinator.startRun(be)` launches the engine on a non-server thread; results land back via the existing `TestResultS2CPayload`.
- [ ] `OutputVerifier.kt` no longer exists.
- [ ] All five source sets compile.
- [ ] `:26.1:test` passes.
- [ ] `:26.1:runClientTest` passes; the new e2e test reports in HTML.

---

## Notes on what is intentionally NOT in this plan

- Diagnostic recording (always-on, per-test) lands in Plan E.
- UI consumption of the diagnostic recording lands in Plan F.
- Migration of pre-existing on-disk `<id>.spec.kts` files written by the old emitter is **not handled**: their text doesn't compile under the new shape (no class declaration). Per spec, this is a greenfield format change.
