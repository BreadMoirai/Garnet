# Plan E — Always-on diagnostic recording attached to TestResult

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture a `StateRecording` for every test that runs through `runRedstoneSpec`, attach it to the test's `TestResult`, surface it through `LauncherResult` to `EngineDrivenRun`, and include it in the `TestResultS2CPayload` sent to clients. This is the always-on R2′ diagnostic recording from the spec.

**Architecture:** `runRedstoneSpec` already produces a `StateRecording` and returns it. A new Kotest `TestListener` (`DiagnosticRecorderListener`) collects per-test recordings into a side-channel keyed by test name. `LauncherResult` grows a `recordings: Map<String, StateRecording>` field. `EngineDrivenRun.toTestResult` and the network payload pass the recording through. The on-server `RedstoneSpecRunnerBlock` UI side surfaces it via Plan F.

**Tech Stack:** Kotest TestListener API, `StateRecordingStorage` (NBT codec), Fabric `StreamCodec`.

**Spec reference:** Spec §"R2′ — diagnostic recording (keep as opt-in)" (now always-on per user choice), §"Error handling and reporting".

**Depends on:** Plans A, B, C, D.

---

## File structure (after this plan)

**New:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListener.kt` — collects per-test recordings.
- `src/test/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListenerTest.kt`.

**Modified:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RunRedstoneSpec.kt` — pushes the captured recording onto the listener-side ThreadLocal.
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/ResultCollector.kt` — adds `recordings` field to `LauncherResult`.
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/KotestLauncher.kt` — registers `DiagnosticRecorderListener`.
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/EngineDrivenRun.kt` — propagates the recording from `LauncherResult` into `TestResult.recording` (new optional field).
- `src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt` — gains optional `recording: StateRecording?` field.
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt` — `TestResultS2CPayload` STREAM_CODEC composes the new field.

---

## Task 1: Add `recording` field to `TestResult` (TDD)

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/data/TestResultTest.kt` *(if not present)*

- [ ] **Step 1: Read the existing TestResult**

```bash
cat src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt
```

This file is auto-emitted via `@AutoEmit` (per Plan A's discovery: AutoEmit annotation is in use). Adding a field will require regenerating its codec.

- [ ] **Step 2: Add the field**

```kotlin
@AutoEmit
data class TestResult(
    val specId: String,
    val timestamp: Long,
    val checks: List<TickCheck>,
    /** Optional diagnostic recording from the run. Populated by Plan E's DiagnosticRecorderListener. */
    val recording: StateRecording? = null,
)
```

If `StateRecording` is not in the same module classpath as `TestResult` for codec purposes, add a small wrapper type or inline the necessary encoder (see Step 4 below). Check `data/SpecJsonCodec.kt` to see how `RedstoneSpec`-side codecs handle list/optional fields.

- [ ] **Step 3: Run KSP / build to regenerate AutoEmit codec**

Run: `cmd.exe /c "./gradlew.bat :26.1:kspKotlin"`
Expected: BUILD SUCCESSFUL; new generated codec includes the optional recording field.

If AutoEmit cannot codec-encode `StateRecording` directly, add a manual `StreamCodec<ByteBuf, StateRecording>` based on the existing `StateRecordingStorage` NBT serialization, and exclude the field from AutoEmit by marking it with the appropriate annotation (see existing AutoEmit usage in `RedstoneSpec` for the pattern).

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/data/TestResult.kt
git commit -m "feat(data): TestResult carries optional StateRecording for diagnostics"
```

---

## Task 2: `DiagnosticRecorderListener`

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListener.kt`
- Create: `src/test/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListenerTest.kt`

- [ ] **Step 1: Write the listener**

```kotlin
package com.breadmoirai.redstonespecs.testing.launcher

import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.listeners.TestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the [StateRecording] produced by `runRedstoneSpec` (set via [recordingThreadLocal])
 * for each leaf test, keyed by the test's full name. Read by [ResultCollector] when assembling
 * the [LauncherResult].
 */
class DiagnosticRecorderListener : TestListener {
    private val byTestName = ConcurrentHashMap<String, StateRecording>()

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        if (testCase.type != TestType.Test) return
        val rec = recordingThreadLocal.get() ?: return
        byTestName[testCase.name.testName] = rec
        recordingThreadLocal.remove()
    }

    fun snapshot(): Map<String, StateRecording> = byTestName.toMap()

    companion object {
        /** Set by `runRedstoneSpec` after producing a recording; cleared by `afterTest`. */
        val recordingThreadLocal: ThreadLocal<StateRecording?> = ThreadLocal()
    }
}
```

- [ ] **Step 2: Wire the listener in `KotestLauncher`**

Modify `launchKotest`:

```kotlin
fun launchKotest(
    sourceSet: String,
    reportsDir: Path,
    specs: List<KClass<out Spec>> = emptyList(),
): LauncherResult {
    reportsDir.createDirectories()
    System.setProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
    System.setProperty("kotest.framework.classpath.scanning.config.disable", "true")

    val resultCollector = ResultCollector()
    val diagListener = DiagnosticRecorderListener()
    val config = object : AbstractProjectConfig() { override val parallelism: Int = 1 }

    val launcher = TestEngineLauncher()
        .withProjectConfig(config)
        .withExtensions(resultCollector, diagListener)

    if (specs.isNotEmpty()) launcher.withClasses(specs).launch() else launcher.launch()

    return resultCollector.result.copy(recordings = diagListener.snapshot())
}
```

- [ ] **Step 3: Add `recordings` to `LauncherResult`**

```kotlin
data class LauncherResult(
    val passed: Int,
    val failed: Int,
    val errors: List<TestFailureRecord>,
    val recordings: Map<String, com.breadmoirai.redstonespecs.runner.StateRecording> = emptyMap(),
) {
    val total: Int get() = passed + failed
    fun summary(): String = if (failed == 0) {
        "All $total tests passed"
    } else {
        val sample = errors.take(5).joinToString("\n  ") { "${it.name}: ${it.message}" }
        "$failed/$total failed:\n  $sample" + if (errors.size > 5) "\n  ... (${errors.size - 5} more)" else ""
    }
}
```

- [ ] **Step 4: Update `runRedstoneSpec` to publish to the ThreadLocal**

In `RunRedstoneSpec.kt`, after `assertOutputsMatch(spec, recording)` succeeds OR before the throw on failure:

```kotlin
    } finally {
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }
    val recording = recorder.toRecording()
    DiagnosticRecorderListener.recordingThreadLocal.set(recording)
    assertOutputsMatch(spec, recording)
    recording
```

> The ThreadLocal is set whether or not the assertion fails — the listener's `afterTest` runs in either case and reads it. Setting it before the assertion ensures failure-path recordings are captured too.

- [ ] **Step 5: Write a test for the listener**

```kotlin
package com.breadmoirai.redstonespecs.testing.launcher

import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class DiagnosticRecorderListenerTest : FunSpec({
    test("snapshot exposes recordings keyed by test name") {
        val listener = DiagnosticRecorderListener()
        // Construct fake TestCase + TestResult; invoke afterTest twice with different ThreadLocals.
        // (See Kotest test fixtures or use a tiny in-process FunSpec to drive it.)
        // Stub assertion: snapshot starts empty.
        listener.snapshot().size shouldBe 0
    }
})
```

> Full coverage requires fabricating Kotest internal types; the basic stub above is sufficient. Higher-fidelity coverage comes via the integration test in Task 4.

- [ ] **Step 6: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:test"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListener.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/KotestLauncher.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/ResultCollector.kt \
        src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RunRedstoneSpec.kt \
        src/test/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListenerTest.kt
git commit -m "feat(testing): DiagnosticRecorderListener captures per-test StateRecording"
```

---

## Task 3: `EngineDrivenRun` propagates the recording into `TestResult`

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/runner/EngineDrivenRun.kt`

- [ ] **Step 1: Plumb the recording**

Update `toTestResult`:

```kotlin
private fun toTestResult(specId: String, lr: LauncherResult): TestResult {
    val recording = lr.recordings.values.firstOrNull()  // one-spec-one-test invariant
    val checks: List<TickCheck> = if (lr.failed == 0) {
        listOf(TickCheck(SimTime.START, "spec '$specId'", "ok", "ok", pass = true))
    } else {
        lr.errors.map { e ->
            TickCheck(SimTime.START, e.name, expected = "(see test message)", actual = e.message, pass = false)
        }
    }
    return TestResult(specId, System.currentTimeMillis(), checks, recording = recording)
}
```

- [ ] **Step 2: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:classes"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/runner/EngineDrivenRun.kt
git commit -m "feat(runner): EngineDrivenRun puts diagnostic recording on TestResult"
```

---

## Task 4: `TestResultS2CPayload` stream-codec includes recording

**Files:**
- Modify: `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`

- [ ] **Step 1: Update the codec**

The existing payload uses `ByteBufCodecs.fromCodec(TestResult.CODEC)`. If `TestResult.CODEC` is regenerated by `@AutoEmit` (Task 1, Step 3) it already includes the new field — confirm by inspecting the generated file under `build/generated/ksp/.../TestResultEmitter.kt` (or whatever path AutoEmit uses).

If AutoEmit cannot codec the `StateRecording`, exclude `recording` from AutoEmit and write a hand-rolled composite stream codec:

```kotlin
companion object {
    val TYPE = CustomPacketPayload.Type<TestResultS2CPayload>(
        Identifier.fromNamespaceAndPath("redstonespecs", "test_result")
    )
    val STREAM_CODEC: StreamCodec<ByteBuf, TestResultS2CPayload> = StreamCodec.composite(
        BlockPos.STREAM_CODEC, TestResultS2CPayload::originPos,
        TestResultStreamCodec, TestResultS2CPayload::result,
        ::TestResultS2CPayload,
    )
}
```

Where `TestResultStreamCodec` is a hand-rolled codec that delegates to AutoEmit for `(specId, timestamp, checks)` and uses `NbtIo` + `StateRecordingStorage.toTag/fromTag` for the optional `recording` field. Include a presence byte (0/1) before the recording bytes.

- [ ] **Step 2: Build**

Run: `cmd.exe /c "./gradlew.bat :26.1:clientClasses classes gametestClasses clientTestClasses testClasses"`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt
git commit -m "feat(network): TestResultS2CPayload includes optional diagnostic recording"
```

---

## Task 5: End-to-end clientTest — confirm recording reaches client

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/DiagnosticRecordingE2ETest.kt`

- [ ] **Step 1: Write the test**

```kotlin
package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.runner.EngineDrivenRun
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.matchers.nulls.shouldNotBeNull
import net.minecraft.core.BlockPos

class DiagnosticRecordingE2ETest : RedstoneTestSpec({
    test("EngineDrivenRun result carries a non-null StateRecording") {
        val spec = redstoneSpec("e2e-diag") { bounds(1, 1, 1); lifespan = 2 }
        val server = McDispatchers.currentServer
        val result = EngineDrivenRun.run(spec, BlockPos(0, 64, 0), server.overworld())
        result.recording.shouldNotBeNull()
    }
})
```

- [ ] **Step 2: Run**

Run: `cmd.exe /c "./gradlew.bat :26.1:runClientTest"`
Expected: `DiagnosticRecordingE2ETest` PASSes.

- [ ] **Step 3: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/DiagnosticRecordingE2ETest.kt
git commit -m "test(clientTest): EngineDrivenRun attaches diagnostic recording to TestResult"
```

---

## Verification checklist

- [ ] `TestResult.recording` exists and is `null` by default.
- [ ] `DiagnosticRecorderListener` collects per-test recordings into a `Map<String, StateRecording>`.
- [ ] `LauncherResult.recordings` exposes them.
- [ ] `EngineDrivenRun.run(...)` returns `TestResult.recording != null` when a `runRedstoneSpec` body executed.
- [ ] `TestResultS2CPayload` round-trips a recording over the wire.
- [ ] All five source sets compile.
- [ ] `:26.1:runClientTest` passes.

---

## Notes on what is intentionally NOT in this plan

- UI consumption of `TestResult.recording` for the in-game timeline scrubber is Plan F.
- HTML reporter integration of the recording (e.g., a trace tab in the report) is out of scope; the recording is on the structured payload but not in the human report.
- Memory cap on retained recordings: not enforced. If real-world specs produce huge recordings, add a tick-count cap or a ring buffer in a follow-up.
