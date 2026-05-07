---
title: Unit-test vs gametest split
tags: [testing, junit, gametest, client-gametest, architecture]
summary: Which logic belongs in src/test/ JUnit unit tests versus src/gametest/ Fabric gametests, and why the split is drawn where it is.
---

# Unit-test vs gametest split

The repo uses three separate source sets:

- `src/test/` — JUnit 5 unit tests, run on the JVM with no MC client or
  server. Bootstrap MC via `SharedConstants.tryDetectVersion()` +
  `Bootstrap.bootStrap()` when registries are needed
  (`RecordingFinalizerTest` is the canonical example).
- `src/gametest/` — Fabric `@GameTest` server-side flows that run inside
  a dedicated MC server instance (`runGameTest`).
- `src/clientTest/` — `FabricClientGameTest` flows that run inside a
  full MC client (`runClientTest`).

## Decision rule

If the logic is **pure** — given inputs in, asserts outputs, no MC
ticks, no levels, no scheduled-tick semantics — it belongs in
`src/test/`. If correctness depends on MC's tick loop, neighbor
updates, scheduled ticks, or BE persistence on the server, it belongs
in `src/gametest/`. If it requires a real client — screens, widgets,
keybinds, payload round-trips driven from the client — it belongs in
`src/clientTest/`.

## Where the contracts actually live

- `RecordingFinalizerTest` (unit) — exercises the finalizer's
  pure-function shape: given a `StateRecording`, produce derived output
  entries per `SpecMode`. No level, no runner. Fast, hermetic.
- `OutputVerifierTest`, `StateRecordingStorageTest`,
  `StateRecordingViewTest`, `StateConditionTest`,
  `BoundsNudgeTest`, `IntEditBoxLogicTest`, `SimTimeTest`,
  `RedstoneSpecTest`, `SpecPersistenceTest`, `SpecMarkerToolTest`,
  `SpecEntryTest`, `FlatRowTest` — all unit tests, all pure data /
  algorithm checks.
- `RedstonespecsGameTests` — record-→finalize→runner contract scenarios
  per `SpecMode`. These need a real level: only the live MC tick loop
  produces accurate scheduled-tick cadence, neighbor-update ordering,
  and comparator/piston timing. The unit-test finalizer is asserted
  against *recordings*; the recordings themselves can only be produced
  inside MC.
- `RedstonespecsClientTests` (in `src/clientTest/`) — full client UI
  flow (recorder screen → marker tool → editor screen → runner block).
  Drives screens, payloads, keybinds. Runs via `runClientTest`.

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
  or scheduling? Add a gametest scenario in `RedstonespecsGameTests`.
- New screen, widget, payload, or marker-tool flow? Add to
  `RedstonespecsClientTests` in `src/clientTest/` (uses
  `SpecTestContext`, which lives alongside it).
- Match the existing file's style: unit tests are flat JUnit 5;
  gametests use either `@GameTest` methods (server-only) or
  `FabricClientGameTest.runTest` with `SpecTestContext` (client).

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
