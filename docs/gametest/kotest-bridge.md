---
title: Kotest + coroutine test bridge
tags: [testing, kotest, coroutines, gametest, client-gametest]
summary: How specs work in src/gametest, src/clientTest, src/test — the awaitTicks / spawnStructure cookbook plus invariants you must respect.
---

The same base class is used by shipped `.spec.kts` files at runtime — see `docs/superpowers/specs/2026-05-07-garnet-kotest-bridge-design.md`.

# Kotest + coroutine test bridge

All three test source sets (`src/test/`, `src/gametest/`, `src/clientTest/`) run Kotest specs backed by a coroutine bridge over MC's tick system. For design rationale, alternatives considered, and architecture decisions, see `docs/superpowers/specs/2026-05-07-kotest-coroutine-test-bridge-design.md`.

## Source set layout

| Source set | Sentinel | Runs via |
|---|---|---|
| `src/test/` | None — Kotest's JUnit Platform engine discovers specs automatically | `./gradlew :26.2:test` |
| `src/gametest/` | `GametestSentinel` — single `@GameTest` method that spawns a worker | `./gradlew :26.2:runGameTest` |
| `src/clientTest/` | `ClientTestSentinel` — `FabricClientGameTest.runTest` (already on a worker thread) | `./gradlew :26.2:runClientTest` |

All three produce Kotest's built-in HTML report under `build/reports/garnet/<sourceSet>/` and JUnit XML under `build/test-results/<sourceSet>/`.

## Base class

```kotlin
abstract class GarnetTestSpec(body: GarnetTestSpec.() -> Unit = {}) : FunSpec()
```

Extends Kotest's `FunSpec`. Uses `CoroutineDispatcherFactory` to wrap every test body and lifecycle hook in `withContext(McDispatchers.Server)`. Inside a `GarnetTestSpec`, you are always on the server thread — direct access to world state, block entities, and levels is safe without an `onServer { }` wrapper.

`GarnetTestSpec` is also used by shipped `.spec.kts` scripts at runtime (outside the gametest harness). The same base class is registered at both test-time (via the Kotest JUnit Platform engine or the sentinel-based gametest paths) and script-runtime, so specs written for the editor run unchanged as game-test specs.

## Primitives

```kotlin
// Suspend until n END_SERVER_TICK events have fired.
suspend fun awaitTicks(n: Int): MinecraftServer

// Suspend until the next END_SERVER_TICK.
suspend fun awaitTickEnd(): MinecraftServer

// Suspend until an END_SERVER_TICK satisfies a predicate.
suspend fun awaitTickWhere(predicate: (MinecraftServer) -> Boolean): MinecraftServer

// Hop to the server thread, run block, return result.
// Inside GarnetTestSpec this is a no-op — the dispatcher already short-circuits.
suspend fun <T> onServer(block: suspend MinecraftServer.() -> T): T
```

`onServer { }` is primarily useful in raw `FunSpec` subclasses (e.g., `src/test/` specs that bootstrap MC registries) or in helper functions that may be called from either context. Inside `GarnetTestSpec` it is redundant — the `CoroutineDispatcherFactory` already ensures server-thread dispatch, and the dispatcher short-circuits when `isSameThread` is true, so there is no overhead from leaving `onServer { }` calls in place.

### Same-tick guarantee

After `awaitTicks(n)` or `awaitTickEnd()` returns, any code that runs before the next `awaitTicks` call executes within the *same* tick — specifically inside the END_SERVER_TICK callback's `managedBlock { pendingTasksCount == 0 }` drain. This means reads and writes between two `awaitTicks` calls are same-tick guaranteed on the server thread with no race against the next tick.

## Structure spawning

Structures let tests verify real-world redstone behavior inside a live MC world.

```kotlin
suspend fun spawnStructure(id: Identifier): StructureHandle
```

Returns a `StructureHandle` with:

```kotlin
fun absolute(relative: BlockPos): BlockPos  // converts template-relative to world coords
fun signalAt(relative: BlockPos): Int       // getBestNeighborSignal at the given relative pos
suspend fun teardown()                       // fills the slot with air, releases it for reuse
```

Teardown is also called automatically by a Kotest `TestListener.afterTest` hook; the explicit `teardown()` in `finally` blocks is belt-and-suspenders.

### Structure file location

Structure files **must** be placed at:

```
src/gametest/resources/data/<namespace>/gametest/structure/<name>.snbt
```

Not `data/<namespace>/structures/`. The Fabric Gametest API's `StructureManager` looks up structures under the `gametest/structure/` subpath. Files at `structures/` will not be found and `spawnStructure` will throw `IllegalArgumentException: Structure not found: <id>`.

## Cookbook example

```kotlin
class ComparatorSpec : GarnetTestSpec({
    test("comparator latches after 4 ticks") {
        val s = spawnStructure(Identifier.fromNamespaceAndPath("garnet", "comparator_basic"))
        try {
            // server-thread direct access — no onServer { } wrapper needed
            McDispatchers.currentServer.overworld().setBlock(
                s.absolute(BlockPos(2, 2, 1)), Blocks.OAK_BUTTON.defaultBlockState(), 2,
            )
            awaitTicks(4)
            s.signalAt(BlockPos(4, 2, 1)) shouldBe 15
        } finally {
            s.teardown()
        }
    }
})
```

Use `spawnStructure` per test for isolation. With sequential mode (the default: `concurrentSpecs = 1`, `concurrentTests = 1`), the grid always allocates slot 0 and releases it after each test.

## Invariants

**Never `join()` a worker thread on the server thread inside the gametest sentinel.** The sentinel starts a worker, posts `helper.succeed()`/`fail()` back to the server thread, then returns. If it joined the worker, the server thread would block, preventing tick events from firing — which would deadlock any spec waiting on `awaitTicks`. The sentinel is implemented correctly; this note exists for anyone modifying it.

**Keep `onServer { }` blocks (and test body work in general) short.** Each runnable dispatched to the server thread is wrapped in a 100ms watchdog that logs a warning if exceeded. One tick is 50ms; a runnable that runs over 100ms stalls two ticks. Warnings are diagnostic only — the test continues — but repeated stalls indicate a test that is doing too much on the server thread.

**Call `awaitTicks` before the action when waiting for a specific number of ticks.** The `serverTickEnd` `SharedFlow` has `replay = 0` and `DROP_OLDEST` overflow. Emissions while no consumer is suspended are dropped. Start collecting (enter `awaitTicks`) before performing the action that triggers the ticks you need to observe.

**The `runGameTest` world persists between invocations** (`versions/<v>/build/run/gameTest/world/`). Blocks and block entities a spec places at fixed coordinates survive to the *next* run. This bites any spec that places a block and then asserts on a *freshly-created* BE: `level.setBlock(pos, state)` is a no-op when `pos` already holds that exact `state`, so `getBlockEntity(pos)` returns the **stale** BE from the prior run with its old fields, not a new one. Force a fresh BE by clearing the position first — `level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)` before placing — or by using a position no other run touches. (This is distinct from the flaky `setBlock`→recorder interaction; it is deterministic world carry-over.)

## Spec style

The project standard is Kotest's `FunSpec`. `GarnetTestSpec` extends `FunSpec`; unit tests in `src/test/` extend `FunSpec` directly. Use `context("group") { test("case") { ... } }` nesting when a logical group of cases shares setup or wants to be documented together. Other Kotest styles (`DescribeSpec`, `BehaviorSpec`, `StringSpec`) are not used to keep specs uniform across the codebase.

## Reports

```
build/reports/garnet/
├── test/        — Kotest HTML
├── gametest/    — Kotest HTML
└── clientTest/  — Kotest HTML

build/test-results/
├── test/        — JUnit XML (picked up by CI)
├── gametest/    — JUnit XML
└── clientTest/  — JUnit XML
```

Kotest's HTML reporter is the standard. JUnit XML is emitted automatically by the JUnit Platform engine for unit tests; the gametest/clientTest sentinels rely on Kotest's `LauncherResult.summary()` for in-log feedback.
