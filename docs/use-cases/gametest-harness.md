---
title: Gametest-harness use-cases
tags: [gametest, harness, fixtures, kotest, use-cases]
summary: Test infrastructure scenarios: spec registration, runGarnetSpec dispatch, client-gametest fixtures, diagnostic recording.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Gametest-harness use-cases

These UCs describe how author-written tests interact with the harness. They are the only UCs whose **actor** is the *test author* rather than a player.

---

### UC-GT-01 — Register a new Kotest spec in the appropriate sentinel

**Actor:** Test author
**Trigger:** Author creates a new `GarnetTestSpec` (or `FunSpec`) subclass in `src/gametest/` or `src/clientTest/` and needs it to run.
**Preconditions:** The spec class has been written; autoscan is globally disabled by `launchKotest` (system properties `kotest.framework.classpath.scanning.autoscan.disable` and `kotest.framework.classpath.scanning.config.disable` are both `true`).
**Outcome:** The new spec class appears in Kotest's output on the next `runGameTest` or `runClientTest` invocation; its tests are counted in `LauncherResult.total`.

**System interactions:**
- UC-GT-01.a — For a server-gametest spec, the author adds the spec's `KClass` to the `specs = listOf(...)` argument in `GametestSentinel.runAll`; failure to do so causes the spec to be silently ignored — it will never appear in results or failures.
- UC-GT-01.b — For a client-gametest spec, the author adds the spec's `KClass` to the `specs = listOf(...)` argument in `ClientTestSentinel.runKotestOnWorker`; the same silent-skip applies.
- UC-GT-01.c — `launchKotest(sourceSet, reportsDir, specs)` forwards the explicit list to `TestEngineLauncher.withClasses(specs)`, bypassing the classpath walker entirely; the `specs` list is the only discovery mechanism for these source sets.
- UC-GT-01.d — `AsyncEventHandler.registerWithServer(server)` must have been called before any spec in the list is executed; `GametestSentinel` calls it at the top of `runAll` after obtaining the live `MinecraftServer`; `ClientTestSentinel` calls `AsyncEventHandler.register()` at the top of `runTest` (before `SERVER_STARTED` fires, via the singleplayer world created by `SpecTestContext.createWorld`).

**Invariants:** [gametest/kotest-bridge.md](../gametest/kotest-bridge.md), [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md)

---

### UC-GT-02 — Drive a spec body via `runGarnetSpec`

**Actor:** Test author
**Trigger:** A `GarnetTestSpec` test body calls `runGarnetSpec(spec, originPos, level)` to execute a DSL-defined `GarnetSpec` against a live world.
**Preconditions:** The caller is executing within a `GarnetTestSpec` coroutine (so `RecordingHolder` is installed in the coroutine context and `McDispatchers.Server` is the active dispatcher); `originPos` and `level` are bound via `GarnetTestSpecContext` (set before the spec is instantiated by `EngineDrivenRun`).
**Outcome:** The `GarnetSpec` lambda is replayed tick-by-tick against the world; a `StateRecording` is returned and stored in `RecordingHolder.recording` so `DiagnosticRecorderListener` can retrieve it after the test; the run throws `AssertionError` if any assertion fails.

**System interactions:**
- UC-GT-02.a — `GarnetTestSpec` installs a `CoroutineDispatcherFactory` that wraps every test body and lifecycle hook in `withContext(McDispatchers.Server + RecordingHolder())`; this ensures the `RecordingHolder` slot is in-context for both the test body and the `afterTest` hook called by `DiagnosticRecorderListener`.
- UC-GT-02.b — `runGarnetSpec(spec, originPos, level)` delegates to the engine's `runEngine(level, originPos, spec)` and then sets `coroutineContext[RecordingHolder]?.recording = recording` so the holder carries the result.
- UC-GT-02.c — Inside the tick loop, test bodies use `awaitTicks(n)` / `awaitTickEnd()` from `Suspending.kt` to suspend until `serverTickEnd` emits; `emitServerTickEnd` calls `server.managedBlock { server.pendingTasksCount == 0 }` after `tryEmit`, so resumed continuations drain synchronously within the same tick.
- UC-GT-02.d — The same-tick guarantee means any `onServer { }` work done between two consecutive `awaitTicks` calls executes atomically within one tick's drain window; authors must enter `awaitTicks` *before* performing the action whose ticks they need to observe, because `serverTickEnd` has `replay = 0` and `DROP_OLDEST` overflow — emissions while no consumer is suspended are dropped.
- UC-GT-02.e — `GarnetTestSpecContext.current()` supplies `originPos` and `level` to the spec class's `val originPos` and `val level` properties; attempting to access these outside an `EngineDrivenRun`-bound context throws `IllegalStateException("No GarnetTestSpecContext bound")`.

**Invariants:** [gametest/kotest-bridge.md](../gametest/kotest-bridge.md)

---

### UC-GT-03 — Use `SpecTestContext` for client-gametest UI assertions

**Actor:** Test author
**Trigger:** A `clientTest` spec needs to exercise a screen, widget, or payload round-trip that requires a real MC client.
**Preconditions:** `ClientTestSentinel.runTest` has called `SpecTestContext.createWorld(context)` and wrapped the resulting `TestSingleplayerContext` in `SpecTestContext`; `McDispatchers` is installed because `createWorld` fires `SERVER_STARTED`; the test is running on the Kotest worker thread while the Fabric test thread drives ticks via `context.waitTick()`.
**Outcome:** The screen under test opens, the author's assertions run against live widget state, and the world is cleanly closed via the `Closeable` `TestSingleplayerContext` at the end of the `use { }` block.

**System interactions:**
- UC-GT-03.a — `SpecTestContext.createWorld(context)` builds a singleplayer world with `setUseConsistentSettings(true)` for a deterministic seed, waits for chunk download, and runs `gamemode creative` and infinite saturation so the player never starves during long runs.
- UC-GT-03.b — `SpecTestContext.rightClickBlock(pos, dir)` dispatches item interaction via `stack.useOn(UseOnContext(...))` or bare `useWithoutItem`, bypassing `ServerPlayerGameMode`'s interaction phase that would toggle levers and buttons before the item-use path runs.
- UC-GT-03.c — Screen rebuilds arrive asynchronously after server data packets; the author calls `waitForButton(labelText, timeoutTicks)` before `clickButton(labelText)` to avoid racing the rebuild.
- UC-GT-03.d — When multiple widgets share the same label (e.g. several `" "` checkboxes or multiple `false` cycle-buttons), `clickNthButton(labelText, index)` and `clickNthCycleButtonByValue(valueText, index)` select by occurrence; `CycleButton` messages are matched as either bare value (`"false"`) or `"<label>: <value>"`.
- UC-GT-03.e — `fillEditBoxByWidth(widthPx, value)` sets `box.value` directly on the `EditBox`; the `ValueResponder` fires synchronously — the author must not also call `onChange` manually.
- UC-GT-03.f — `clickYaclButton(labelText)` and `setYaclOption(optionName, value)` reach YACL's config option tree directly rather than the rendered widget tree, because YACL options are not surfaced as `AbstractButton` children in `Screen.children()`.

**Invariants:** [gametest/spec-test-context.md](../gametest/spec-test-context.md), [gametest/kotest-bridge.md](../gametest/kotest-bridge.md)

---

### UC-GT-04 — Capture diagnostic recordings on test failure

**Actor:** Test author
**Trigger:** A `GarnetTestSpec` test fails (or the author wants to inspect the `StateRecording` produced during a run regardless of pass/fail).
**Preconditions:** The spec was run via a sentinel that passed a `DiagnosticRecorderListener` to `launchKotest`; `RecordingHolder` was installed in the coroutine context by `GarnetTestSpec`'s `CoroutineDispatcherFactory`; `runGarnetSpec` stored the recording into the holder.
**Outcome:** `LauncherResult.recordings` contains a `Map<String, StateRecording>` keyed by leaf test name; the caller (sentinel or test infrastructure) can retrieve and log or persist the recording for offline inspection.

**System interactions:**
- UC-GT-04.a — `launchKotest` constructs both a `ResultCollector` and a `DiagnosticRecorderListener` and registers them as extensions on the `TestEngineLauncher`; the final `LauncherResult` is assembled as `collector.result.copy(recordings = diagListener.snapshot())`.
- UC-GT-04.b — `DiagnosticRecorderListener.afterTest` fires for every leaf test (`TestType.Test`); it reads `currentCoroutineContext()[RecordingHolder]?.recording` and stores any non-null `StateRecording` into a `ConcurrentHashMap<String, StateRecording>` keyed by `testCase.name.testName`.
- UC-GT-04.c — The `RecordingHolder` is visible in `afterTest` because `GarnetTestSpec`'s `CoroutineDispatcherFactory` wraps both the test body and all per-test lifecycle hooks in the same `withContext(McDispatchers.Server + RecordingHolder())` scope; the holder is shared between the body (where `runGarnetSpec` writes to it) and the `afterTest` callback (where `DiagnosticRecorderListener` reads from it).
- UC-GT-04.d — `ResultCollector` runs in parallel with `DiagnosticRecorderListener` as a separate `TestListener`; it tallies `passed`/`failed` counts and collects `TestFailureRecord` entries for every failure and error; the two listeners are independent and do not share mutable state.
- UC-GT-04.e — `diagListener.snapshot()` returns an immutable copy of the map via `byTestName.toMap()`, so the `LauncherResult` value is safe to inspect after the engine has shut down without risk of concurrent modification.

**Invariants:** [gametest/kotest-bridge.md](../gametest/kotest-bridge.md)

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-GT-01 | Register a new Kotest spec in the appropriate sentinel | `GametestSentinel.runAll`, `ClientTestSentinel.runTest` | **GAP-PARTIAL** |
| UC-GT-01.a | Author adds spec `KClass` to `GametestSentinel.runAll` specs list | `GametestSentinel.runAll` | **GAP-PARTIAL** |
| UC-GT-01.b | Author adds spec `KClass` to `ClientTestSentinel.runKotestOnWorker` specs list | `ClientTestSentinel.runTest` | **GAP-PARTIAL** |
| UC-GT-01.c | `launchKotest` forwards explicit list to `TestEngineLauncher.withClasses`; classpath walker bypassed | — | **GAP** |
| UC-GT-01.d | `AsyncEventHandler.registerWithServer` called before any spec executes | `GametestSentinel.runAll`, `ClientTestSentinel.runTest` | **GAP-PARTIAL** |
| UC-GT-02 | Drive a spec body via `runGarnetSpec` | `RunGarnetSpecSmokeTest."runGarnetSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-GT-02.a | `GarnetTestSpec` installs `CoroutineDispatcherFactory` wrapping body in `McDispatchers.Server + RecordingHolder()` | `RecordingHolderTest."holder set in outer scope is visible in nested suspend functions"` | **GAP-PARTIAL** |
| UC-GT-02.b | `runGarnetSpec` delegates to engine and stores recording in `RecordingHolder` | `RunGarnetSpecSmokeTest."runGarnetSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-GT-02.c | `awaitTicks`/`awaitTickEnd` suspend until `serverTickEnd` emits; drain synchronous | `SmokeSpec."awaitTicks advances the server tick counter"`, `SuspendingTest."take(n).last() resolves after n emissions"` | covered |
| UC-GT-02.d | Same-tick guarantee: `onServer { }` work between `awaitTicks` is atomic | `SmokeSpec."test body runs on the server thread"` | **GAP-PARTIAL** |
| UC-GT-02.e | `GarnetTestSpecContext.current()` throws outside `EngineDrivenRun` context | — | **GAP** |
| UC-GT-03 | Use `SpecTestContext` for client-gametest UI assertions | `RunGarnetSpecSmokeTest."runGarnetSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-GT-03.a | `SpecTestContext.createWorld` builds deterministic singleplayer world | `ClientTestSentinel.runTest` | **GAP-PARTIAL** |
| UC-GT-03.b | `rightClickBlock` dispatches via `useOn`/`useWithoutItem` bypassing game-mode interaction | — | **GAP** |
| UC-GT-03.c | `waitForButton` polls before `clickButton` to avoid rebuild race | — | **GAP** |
| UC-GT-03.d | `clickNthButton`/`clickNthCycleButtonByValue` select widget by occurrence index | — | **GAP** |
| UC-GT-03.e | `fillEditBoxByWidth` sets value directly; `ValueResponder` fires synchronously | — | **GAP** |
| UC-GT-03.f | `clickYaclButton`/`setYaclOption` reach YACL option tree directly | — | **GAP** |
| UC-GT-04 | Capture diagnostic recordings on test failure | `DiagnosticRecorderListenerTest."snapshot is empty before any tests run"` | **GAP-PARTIAL** |
| UC-GT-04.a | `launchKotest` constructs `ResultCollector` + `DiagnosticRecorderListener`; result is `collector.result.copy(recordings = …)` | — | **GAP** |
| UC-GT-04.b | `DiagnosticRecorderListener.afterTest` reads `RecordingHolder` and stores non-null recording | `DiagnosticRecorderListenerTest."snapshot is empty before any tests run"` | **GAP-PARTIAL** |
| UC-GT-04.c | `RecordingHolder` visible in `afterTest` because both body and hook share same `withContext` scope | `RecordingHolderTest."holder set in outer scope is visible in nested suspend functions"` | covered |
| UC-GT-04.d | `ResultCollector` and `DiagnosticRecorderListener` are independent listeners with no shared state | — | **GAP** |
| UC-GT-04.e | `diagListener.snapshot()` returns immutable copy via `toMap()` | `DiagnosticRecorderListenerTest."snapshot is empty before any tests run"` | **GAP-PARTIAL** |
