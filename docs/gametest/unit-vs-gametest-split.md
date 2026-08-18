---
title: Unit-test vs gametest split
tags: [testing, kotest, gametest, client-gametest, architecture]
summary: Which logic belongs in src/test/, src/gametest/, or src/clientTest/, and why the lines are drawn where they are.
---

# Unit-test vs gametest split

Three source sets run Kotest specs against the game; see `kotest-bridge.md` for the DSL and
`awaitTicks`/`onServer`/`spawnStructure` cookbook. A fourth, `src/testSupport/`, holds the bridge
itself (`GarnetTestSpec`, `ClientSpec`, `launchKotest`, and friends, package
`com.breadmoirai.garnet.harness`) rather than any of the mod's own tests — it is a dependency of
the other three, not a test suite in its own right, and it is the only place Kotest is a
dependency; `main`/`client` do not ship it in the jar.

- `src/test/` — Kotest unit specs, run on the JVM with no live MC client or
  server (no window, no GL, no GLFW). Bootstrap MC via
  `SharedConstants.tryDetectVersion()` + `Bootstrap.bootStrap()` when
  registries are needed (`StateRecordingStorageTest` is the canonical
  example). Since 2026-08-03 this source set also carries `client`'s
  compile classpath and runs the Compose compiler plugin, so it also holds
  the pure-JVM client-code specs, packaged directly under each spec's own
  feature package (e.g. `src/test/kotlin/com/breadmoirai/garnet/dock/shell/`,
  `.../editor/explorer/ui/`) rather than under a separate `client/` mirror —
  Compose snapshot-state and `@Composable` code included, as long as no live
  `Minecraft`/GL/GLFW is required.
- `src/gametest/` — Kotest specs driven by a single `@GameTest` sentinel
  that runs inside a dedicated MC server instance (`runGameTest`). Use the
  `awaitTicks`/`spawnStructure` primitives from the bridge.
- `src/clientTest/` — Kotest specs driven by a `FabricClientGameTest`
  sentinel that runs inside a full MC client (`runClientTest`). Use
  `ClientContextHolder` to access `ClientGameTestContext`.
- `src/testSupport/` — the harness itself (`GarnetTestSpec`, `GarnetTestSpecContext`,
  `ClientSpec`, `RecordingHolder`, `launcher/` helpers). Not a test suite; see
  [architecture/module-map.md](../architecture/module-map.md#test-source-sets).

## Decision rule

If it needs a live `Minecraft` instance, a GL context, or GLFW — pixel probes,
`Minecraft.hitResult`, `MouseHandler` state, routing events into a running `ComposeScene` — it
belongs in `src/clientTest/`. Everything else belongs in `src/test/`, **including Compose
snapshot state and `@Composable` code**: since 2026-08-03 the `test` source set carries
`client`'s compile classpath and runs the Compose compiler plugin. If correctness depends on
MC's tick loop, neighbor updates, scheduled ticks, or BE persistence on the server, it belongs in
`src/gametest/` instead — that axis (pure vs. tick-dependent) is orthogonal to the client-or-not
axis above and still decides the `src/test/` vs `src/gametest/` boundary.

### Why `src/test` can see `client`

It could not until 2026-08-03, which stranded ~55 pure-JVM assertions inside a full client boot —
Gson round-trips, a GLFW→Compose lookup table, and a picker spec that stubbed every seam it had.
The fix was two lines of `build.gradle.kts`: the client source set on the test classpath, and
`compileTestKotlin` removed from the Compose-plugin strip list. The strip remains for `main`,
`gametest` and `testSupport`, which is what keeps Compose off the server jar.

## Where the contracts actually live

- **Unit (`src/test/`):** `KtsSpecLoaderTest`,
  `SpecPersistenceTest`, `StateConditionTest`, `StateRecordingStorageTest`,
  `StateRecordingViewTest`, `GridLayoutTest`, `SimTimeTest`, and — relocated from
  `src/clientTest/` on 2026-08-03 because none of them need a live client —
  `ExplorerTreeStateTest`, `ExplorerStateStoreTest`, `ModConfigTest`, `RootPickerControllerTest`,
  `StructureExplorerStatusTest`, `ExplorerLifecycleTest`, `DockInsetsTest`, `DockLifecycleTest`,
  `GlfwKeyMapTest`, `ExplorerActionsTest`, `DockViewportSyncTest`, packaged directly under
  their own feature package under `src/test/kotlin/com/breadmoirai/garnet/` (e.g.
  `dock/input/`, `dock/shell/`, `editor/explorer/ui/`), matching the production code they
  test. All pure data / algorithm / Compose-state checks; no level, no runner, no live
  client.
- **Server gametest (`src/gametest/`):** the `*Spec` classes registered in
  `GametestSentinel` (`SmokeSpec`, `editor/*Spec`, `structure/*Spec`). These need a real level:
  only the live MC tick loop produces accurate scheduled-tick cadence, neighbor-update
  ordering, and comparator/piston timing. Author new tests using
  `runGarnetSpec` with the DSL lambda; see
  [runner/engine-driven-verification.md](../runner/engine-driven-verification.md).
- **Client gametest (`src/clientTest/`):** the seven specs registered in
  `ClientTestSentinel` — `RunGarnetSpecSmokeTest`, `ViewportSpec`, `DockRenderSpec`,
  `DockInputSpec`, `CursorFocusToggleSpec`, `JewelExplorerSpec`, `ExplorerUiSpec` — one user
  story each. Runs via `runClientTest`; exercises the Compose dock (viewport, input routing,
  Explorer) with a real `Minecraft` instance and GL context — there is no recorder-screen/
  runner-block flow to exercise anymore, since that UI and its blocks were deleted.

## Why DSL/algorithm logic is unit-tested but circuit behaviour is gametested

Unit tests exercise the DSL's callback-scheduling correctness with
synthetic `SpecRun` instances — without a real MC tick loop. They can
assert that `input(x,y,z) { at(tick=3) { … } }` registers exactly one
callback at `SimTime(3, START_OF_TICK)`, for example.

Circuit-level tests (does a comparator latch on tick 4?) require the live
MC tick loop: scheduled-tick cadence, neighbor-update ordering, and
piston timing cannot be replicated in a unit test.

Conversely, gametests are slow (full MC boot, world creation, ticks).
Asserting algebraic properties of DSL scheduling or DSL-emitter output
across many synthetic inputs would be wasteful and flaky in a gametest.
Hence the split: unit-test the algorithm with synthetic inputs; gametest
the real-world circuit behaviour.

## Practical guidance

- New algorithm or pure data logic? Add to `src/test/`.
- New runner / verifier / coordinator behavior that depends on ticks
  or scheduling? Add a gametest spec under `src/gametest/`
  using `GarnetTestSpec` + `awaitTicks`/`spawnStructure`, and register it
  in `GametestSentinel`.
- New dock panel, widget, or payload-flow logic — including `@Composable`
  code and Compose snapshot state? Default to a plain Kotest spec under
  `src/test/kotlin/com/breadmoirai/garnet/`, in the same feature package as
  the code it tests; `test` carries `client`'s compile classpath and the
  Compose compiler plugin, so no live client is needed. Only reach for
  `ClientSpec` under `src/clientTest/` (registered in `ClientTestSentinel`)
  when the assertion genuinely needs a live `Minecraft` instance, a GL
  context, or GLFW — see the decision rule above.
- See `kotest-bridge.md` for the full DSL reference and cookbook.

## Bootstrap caveat for unit tests

Any unit test that touches `BuiltInRegistries` (looking up a Block,
default block state, properties) needs:

```kotlin
@BeforeAll fun bootstrap() {
    SharedConstants.tryDetectVersion()
    Bootstrap.bootStrap()
}
```

Forgetting this surfaces as `NullPointerException` deep in registry
lookup, not as a clean "registries not loaded" error. See
`StateRecordingStorageTest` for the pattern.
