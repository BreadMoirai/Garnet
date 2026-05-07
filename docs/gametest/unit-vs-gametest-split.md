---
title: Unit-test vs gametest split
tags: [testing, kotest, gametest, client-gametest, architecture]
summary: Which logic belongs in src/test/, src/gametest/, or src/clientTest/, and why the lines are drawn where they are.
---

# Unit-test vs gametest split

All three source sets run Kotest specs; see `kotest-bridge.md` for the DSL and `awaitTicks`/`onServer`/`spawnStructure` cookbook.

The repo uses three separate source sets:

- `src/test/` — Kotest unit specs, run on the JVM with no MC client or
  server. Bootstrap MC via `SharedConstants.tryDetectVersion()` +
  `Bootstrap.bootStrap()` when registries are needed
  (`RecordingFinalizerTest` is the canonical example).
- `src/gametest/` — Kotest specs driven by a single `@GameTest` sentinel
  that runs inside a dedicated MC server instance (`runGameTest`). Use the
  `awaitTicks`/`spawnStructure` primitives from the bridge.
- `src/clientTest/` — Kotest specs driven by a `FabricClientGameTest`
  sentinel that runs inside a full MC client (`runClientTest`). Use
  `ClientContextHolder` to access `ClientGameTestContext`.

## Decision rule

If the logic is **pure** — given inputs in, asserts outputs, no MC
ticks, no levels, no scheduled-tick semantics — it belongs in
`src/test/`. If correctness depends on MC's tick loop, neighbor
updates, scheduled ticks, or BE persistence on the server, it belongs
in `src/gametest/`. If it requires a real client — screens, widgets,
keybinds, payload round-trips driven from the client — it belongs in
`src/clientTest/`.

## Where the contracts actually live

- **Unit (`src/test/`):** `SpecJsonCodecTest`, `SpecDslTest`,
  `KtsSpecLoaderTest`, `KtsSpecEmitterTest`, `SpecPersistenceTest`,
  `StateConditionTest`, `StateRecordingStorageTest`,
  `StateRecordingViewTest`, `IntEditBoxLogicTest`, `SimTimeTest`. All
  pure data / algorithm checks; no level, no runner.
- **Server gametest (`src/gametest/`):** `RedstonespecsGameTests` —
  currently a placeholder stub. These need a real level: only the live MC
  tick loop produces accurate scheduled-tick cadence, neighbor-update
  ordering, and comparator/piston timing. Pending re-authoring against
  the flat `SpecEntry` model using the Kotest bridge.
- **Client gametest (`src/clientTest/`):** `RedstonespecsClientTests` —
  also a placeholder stub at the moment. Pre-redesign this drove the full
  recorder screen → marker tool → editor screen → runner block flow.
  Runs via `runClientTest`.

## Why finalizer logic is unit-tested but mode contracts are gametests

`RecordingFinalizerTest` proves the finalizer is correct *for any*
`StateRecording`. But the recordings the finalizer will see in
production come from MC's recording machinery, which depends on the
tick loop and neighbor-update ordering. A unit test cannot validate
"recording for circuit X under mode Y has shape Z" — only a gametest
can produce a real recording for X.

Conversely, gametests are slow (full MC boot, world creation, ticks).
Asserting algebraic properties of finalizer output across many
synthetic recordings would be wasteful and flaky in a gametest. Hence
the split: unit-test the algorithm with synthetic inputs; gametest the
real-world inputs against pinned expectations.

## Practical guidance

- New algorithm or pure data logic? Add to `src/test/`.
- New runner / verifier / coordinator behavior that depends on ticks
  or scheduling? Add a gametest scenario in `RedstonespecsGameTests`
  using `ServerTestSpec` + `awaitTicks`/`spawnStructure`.
- New screen, widget, payload, or marker-tool flow? Add to
  `RedstonespecsClientTests` in `src/clientTest/` (uses
  `ClientContextHolder` to access `ClientGameTestContext`).
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
`RecordingFinalizerTest` for the pattern.
