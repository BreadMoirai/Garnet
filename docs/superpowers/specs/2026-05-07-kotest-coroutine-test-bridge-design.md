---
title: Kotest + coroutine test bridge (`:test-bridge` module)
date: 2026-05-07
status: approved
summary: Unify all three test source sets (`src/test/`, `src/gametest/`, `src/clientTest/`) on Kotest by building a coroutine-friendly bridge over Minecraft's tick system. Sentinel-only `@GameTest` / `FabricClientGameTest` entry points launch the Kotest engine on a worker thread; tests use a `SharedFlow<MinecraftServer>` of tick events plus a `ServerThread` `CoroutineDispatcher` to coordinate with the server.
---

# Kotest + coroutine test bridge

## Problem

The repo has three test source sets driven by three different runners:

- `src/test/` — JUnit 5 on the JVM.
- `src/gametest/` — MC's `@GameTest` framework, ticked by `GameTestRunner` inside a server tick loop.
- `src/clientTest/` — `FabricClientGameTest` on a worker thread driving a real client.

Authors writing tests have to switch frameworks per source set, and reporting is fragmented (JUnit XML for unit tests, MC's `JUnitLikeTestReporter` for gametests, ad-hoc logging for client tests). We want one framework everywhere — same DSL, same matchers, same reporting — without losing the tick-loop fidelity that's the reason gametests exist.

## Decision

Adopt **Kotest** as the single test framework, with **`FunSpec`** as the project-standard spec style. Build a `:test-bridge` Gradle subproject that exposes coroutine-friendly primitives over MC's tick events and thread executors. Each non-unit source set has a single sentinel entry point (`@GameTest` or `FabricClientGameTest`) whose body launches Kotest on a worker thread; specs run as Kotest specs, suspend on tick events as flows, and hop to the server thread via `withContext(ServerThread)`. Kotest's built-in HTML reporter handles output across all source sets — Kensa was evaluated but rejected (its Kotest "integration" requires JUnit Jupiter `@Test` methods plus a parser-fragile no-lambdas-in-test-bodies constraint that would 4–6× the line count of every assertion-style test for a literate report whose sentence rendering had cosmetic bugs).

This was chosen over:

- Native frameworks per source set with a shared assertion DSL — partial unification only; doesn't address discovery or reporting.
- A custom Kotest `TestEngine` that drives MC — reimplements `GameTestRunner` and tracks MC version drift; rejected.
- Kensa for literate HTML reports — its Kotest integration turned out to be JUnit-Jupiter-flavored with severe parser constraints; cost outweighed benefit. Kotest's HTML reporter is sufficient.

## Architecture

### Module layout

A new `testBridge` source set in the root project (not a separate Gradle subproject — the project has no real subprojects; introducing one would entangle with Stonecutter and loom configuration). The `gametest` and `clientTest` source sets pull `testBridge` into their compile/runtime classpaths via existing source-set plumbing.

```
src/testBridge/kotlin/com/breadmoirai/redstonespecs/testing/
├── core/
│   ├── Ticks.kt          — serverTickStart, serverTickEnd: SharedFlow<MinecraftServer>
│   ├── Dispatchers.kt    — ServerThreadDispatcher, McDispatchers.Server
│   ├── Lifecycle.kt      — Fabric event subscriptions; install/teardown
│   └── ClientContextHolder.kt — exposes ClientGameTestContext to client specs
├── server/
│   ├── Suspending.kt     — awaitTicks, awaitTickEnd, awaitTickWhere, onServer
│   └── Structures.kt     — spawnStructure, StructureGrid, StructureHandle
└── launcher/
    ├── KotestLauncher.kt — TestEngineLauncher invocation, JUnit XML output dir
    └── ResultCollector.kt — Kotest TestListener that aggregates pass/fail counts
```

Dependencies (added at the root project level so the source set inherits): `kotlin-coroutines-core`, `io.kotest:kotest-runner-junit5`, `io.kotest:kotest-assertions-core`. The Minecraft/Fabric API classpath is already on `main`/`client` and is inherited via the source-set wiring.

### Producer/consumer primitives

The bridge collapses to four primitives plus a base class. Everything else is built on them.

```kotlin
// Producer: tick events as flows. Single subscription, lifetime-scoped to the server.
val serverTickStart: SharedFlow<MinecraftServer>
val serverTickEnd: SharedFlow<MinecraftServer>

// Consumer: a single dispatcher over MinecraftServer.execute, plus a server holder.
object McDispatchers {
    val Server: CoroutineDispatcher    // installed on SERVER_STARTED, cleared on SERVER_STOPPED
    val currentServer: MinecraftServer // same lifecycle; throws if accessed before install
}

// Public test API:
suspend fun awaitTickEnd(): MinecraftServer = serverTickEnd.first()
suspend fun awaitTicks(n: Int): MinecraftServer = serverTickEnd.take(n).last()
suspend fun awaitTickWhere(p: (MinecraftServer) -> Boolean) = serverTickEnd.first(p)
suspend fun <T> onServer(block: suspend MinecraftServer.() -> T): T =
    withContext(McDispatchers.Server) { block(McDispatchers.currentServer) }

// Spec base class: tests + hooks default to running on McDispatchers.Server.
abstract class ServerTestSpec(body: ServerTestSpec.() -> Unit = {}) : FunSpec()
```

**Same-tick guarantee via executor drain.** When `emitServerTickEnd(server)` fires (server thread, inside END_SERVER_TICK callback), it calls `tryEmit(server)` *and then* invokes `server.runAllTasks()` (inherited from `BlockableEventLoop`). `tryEmit` wakes any consumer suspended on `awaitTickEnd`/`awaitTicks`; their continuations dispatch via `McDispatchers.Server`, which posts to `MinecraftServer.execute`; the immediately-following `runAllTasks()` drains those continuations on the server thread before returning to MC. Result: any code sequence between two `awaitTicks` calls (or before the first / after the last) executes synchronously inside one tick callback, with no race against the next tick.

**Why `SharedFlow` with `replay = 0`, `extraBufferCapacity = 1`, `DROP_OLDEST`:** tests `awaitTicks(n)` *before* the action that triggers the wait, so dropped emissions while no consumer is suspended are correct. Single-slot buffer prevents the emitter from spinning. `MutableSharedFlow` supports multi-consumer fan-out for opt-in concurrent specs.

**Why the dispatcher short-circuits on `isSameThread`:** nested `withContext(McDispatchers.Server)` calls (e.g. inside helper functions called from already-server-thread contexts) don't re-bounce through the executor. Inside a `ServerTestSpec` test body, `onServer { }` becomes a no-op — the test is already on the server thread.

**`onServer { }` retains a role outside `ServerTestSpec`.** Specs that aren't extending `ServerTestSpec` (e.g., raw `FunSpec` in `src/test/`, or a `clientTest` spec doing setup before any tick fires) still run on Kotest's default dispatcher; for those, `onServer { }` is the explicit hop. Inside a `ServerTestSpec` it's redundant noise.

**No `ClientThread` dispatcher.** Fabric's `ClientGameTestContext` API (`getInput().pressKey`, `waitForScreen`, `waitFor { mc -> ... }`) already synchronizes with the client thread internally. Adding it would be unused complexity; revisit only if a test genuinely needs raw client-thread dispatch.

**100ms server-thread watchdog.** `ServerThreadDispatcher` wraps each Runnable in a watchdog that logs a warning if it runs longer than 100ms. Per-Runnable, not cumulative. Catches accidental `Thread.sleep` / blocking IO / runaway loops in test bodies that would stall the tick loop.

### Sentinels

Three discovery points — one per source set, no other test discovery.

**`src/test/`** — no sentinel. Kotest's JUnit Platform engine already runs specs through the standard `test` task once `kotest-runner-junit5` is on the classpath.

**`src/gametest/GametestSentinel.kt`** — single `@GameTest` method:

```kotlin
@GameTest(template = "redstonespecs:empty_platform", timeoutTicks = 12000)
fun runAll(helper: GameTestHelper) {
    // We're on the server thread. Spawn a worker; do NOT join here.
    Thread.ofPlatform().name("kotest-gametest").uncaughtExceptionHandler { _, t ->
        helper.level.server.execute { helper.fail("Kotest worker crashed: ${t.message}") }
    }.start {
        val result = runCatching { launchKotest(...) }
        helper.level.server.execute {
            result.fold(
                onSuccess = { r -> if (r.failed > 0) helper.fail(r.summary()) else helper.succeed() },
                onFailure = { e -> helper.fail("Kotest engine error: ${e.message}") },
            )
        }
    }
}
```

**Critical invariant:** the worker thread runs Kotest. The sentinel method returns immediately after `worker.start()`; MC's `GameTestSequence` keeps the gametest alive until the worker resolves it via `helper.succeed()`/`fail()` posted back to the server thread. Joining on the server thread would freeze the tick loop and prevent any of the suspending primitives from making progress.

**`src/clientTest/ClientTestSentinel.kt`** — `FabricClientGameTest.runTest` is already on a worker thread, so no thread-spawning needed:

```kotlin
override fun runTest(context: ClientGameTestContext) {
    ClientContextHolder.install(context)  // bridge holder; client specs read it for ClientGameTestContext
    val result = launchKotest(sourceSet = "clientTest", reportsDir = ...)
    if (result.failed > 0) error("${result.failed} Kotest test(s) failed")
}
```

`ClientContextHolder` is a thread-safe singleton in the bridge that exposes the active `ClientGameTestContext` to client-side specs (so they can call `context.getInput().pressKey(...)`, `context.waitForScreen(...)`, etc. without threading the context through every helper signature). Cleared after `runTest` returns.

### Structure spawning

Per-test isolation without going through `GameTestInfo`. A server-scoped `StructureGrid` hands out chunk-aligned regions:

```kotlin
class StructureHandle(val origin: BlockPos, val bounds: BoundingBox, server: MinecraftServer) {
    fun absolute(relative: BlockPos): BlockPos = origin.offset(relative)
    fun signalAt(relative: BlockPos): Int
    suspend fun teardown()
}

suspend fun spawnStructure(id: ResourceLocation): StructureHandle =
    onServer { StructureGrid.forServer(this).spawn(id) }
```

- Templates loaded from `data/<ns>/structures/*.nbt` via MC's `StructureManager` (same path native gametests use).
- Sequential mode: always slot 0 at `(0, 64, 0)`, cleared and reused between tests.
- Concurrent mode (opt-in): slot N at `(32·N, 64, 0)`. One slot per concurrent test.
- No named markers — tests use relative coordinates per `helper.relativePos`-style native convention. Sugar can be added later if it becomes painful.

Teardown is automatic via a Kotest `TestListener.afterTest` extension; `afterSpec` fills the entire grid bounds with air as a backstop against leaks.

### Concurrency

Kotest configured with `concurrentSpecs = 1`, `concurrentTests = 1` by default for gametest/clientTest. Specs opt in via Kotest's per-spec `concurrency` config when world-state isolation allows. Unit tests (`src/test/`) use Kotest defaults.

### Reporting

```
build/reports/redstonespecs/
├── test/        — Kotest HTML
├── gametest/    — Kotest HTML, JUnit XML
└── clientTest/  — Kotest HTML, JUnit XML

build/test-results/
├── test/        — JUnit XML (existing convention, picked up by CI)
├── gametest/    — JUnit XML
└── clientTest/  — JUnit XML
```

Kotest's HTML reporter is the only reporting backend. MC's `JUnitLikeTestReporter` is dropped from the gametest path — Kotest owns reporting end-to-end.

## Error handling and lifecycle

### Failure shapes (gametest sentinel)

1. **Spec assertion fails** → Kotest reports test failure → `LauncherResult.failed > 0` → sentinel posts `helper.fail(summary)`.
2. **Worker thread crashes** → `UncaughtExceptionHandler` posts `helper.fail("Kotest worker crashed: ...")`.
3. **Engine itself throws during `launch()`** → `runCatching` catches → `helper.fail("Kotest engine error: ...")`.

All three resolve through the same server-thread `helper.execute` hop.

### Timeouts

- **Sentinel `@GameTest(timeoutTicks = 12000)`** — 10 minutes for the entire suite. Configurable via Gradle property if needed; hardcoded for now.
- **Per-test timeouts** — Kotest's `config(timeout = 30.seconds)` or `withTimeout { ... }` blocks. Fail individual leaves cleanly without taking down the sentinel.

### Cancellation

- `serverTickEnd` `SharedFlow` has no per-consumer producer-side state; cancelled consumers stop collecting cleanly.
- `onServer { }` work that's already running on the server thread can't be cancelled mid-block (server-thread tasks aren't preemptable). **Convention:** keep `onServer { }` blocks short. Documented behavior; not enforced.
- `withTimeout` is the standard escape hatch.

### Server-thread watchdog

Each `onServer { }` runnable is wrapped in a watchdog: if it runs >100ms (one tick is 50ms), log a warning. Diagnostic only, not enforcement. Helps catch tests that accidentally hold the tick loop.

### Structure cleanup

Two layers of defense against leaked grid slots:
1. Kotest `TestListener.afterTest` — calls `teardown()` on each registered `StructureHandle`, swallows exceptions.
2. `afterSpec` — fills the entire grid bounds with air. Cheap, idempotent.

Sentinel crash → server stops → no persistence concern.

### Lifecycle ordering

```
SERVER_STARTED
  └── tick subscriptions wire
  └── McDispatchers.Server installed
MC gametest discovery → finds GametestSentinel.runAll
runAll invoked on server thread → spawns worker
worker → launchKotest → specs run
SERVER_STOPPING → dispatcher cleared
```

`onServer { }` calls before `SERVER_STARTED` cannot occur with sentinel-only discovery; the `checkNotNull` in the dispatcher accessor is defense-in-depth.

## Testing strategy

### Bridge unit tests (`:test-bridge`'s own `src/test/`)

- `ServerThreadDispatcher.isDispatchNeeded` against a fake server.
- `awaitTicks(n)` against a hand-driven `MutableSharedFlow<MinecraftServer>` — no MC required.
- `awaitTickWhere { }` short-circuit semantics.
- `StructureGrid` slot allocation/release — logical only, no real `StructureManager`.

### Bridge smoke gametest

One `@GameTest` that spawns `redstonespecs:empty_platform`, runs a 3-leaf Kotest spec exercising `awaitTicks(2)`, `onServer { read tickCount }`, `spawnStructure + teardown`. Catches "did the bridge actually wire to MC's lifecycle" bugs independently of real test content.

### Migration

**`src/test/`:** ~10 existing JUnit tests convert mechanically.
- `@Test fun foo() { ... }` → `test("foo") { ... }` in `FunSpec`.
- `@BeforeAll` → `beforeSpec { ... }`.
- `assertEquals` → `shouldBe`.
- One PR per test file or grouped by package.
- Gradle: drop `fabric-loader-junit`, add `kotest-runner-junit5` + `kotest-assertions-core` to `testImplementation`.

**`src/gametest/` and `src/clientTest/`:** both are placeholder stubs pending re-authoring against the flat `SpecEntry` model regardless. The bridge lands *before* re-authoring; new suites are written natively against the bridge. The `lever_lamp.snbt` resource stays — it's the structure for the first real spec.

### Documentation

- New article `docs/gametest/kotest-bridge.md` covering DSL cookbook, the 100ms watchdog, the no-`join`-on-server-thread invariant.
- Update `docs/gametest/unit-vs-gametest-split.md` to reflect the new framework.
- Register in `docs/gametest/INDEX.md`.
- Add `docs/build/test-bridge-module.md` for the Gradle wiring.

## Out of scope

- **Stonecutter abstraction** over MC version differences in `GameTestHelper` / `StructureTemplate`. Single-version target now; revisit when a backport actually lands per the existing `backport` skill workflow.
- **`ClientThread` dispatcher / `clientTicks` flow.** Not needed; Fabric's `ClientGameTestContext` covers client-thread work.
- **Native MC `@GameTest` discovery alongside Kotest.** Sentinel-only.
- **`GameTestInfo`-driven isolation per spec.** Grid + cleanup is sufficient; can add a `gametest("name", structure = ...) { helper -> ... }` overlay later if anyone wants the native sequence DSL back.
- **Kensa integration.** Evaluated and rejected — see "Decision" section above.

## Module naming

The source set is named `testBridge` (Gradle source-set names are conventionally camelCase — `clientTest` already follows this pattern). If the bridge is later promoted to a real Gradle subproject, the subproject would be `:test-bridge` (kebab-case) per the JVM artifact-naming convention.
