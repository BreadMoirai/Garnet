---
title: Engine-driven verification
tags: [execution, kotest, verification]
summary: How runRedstoneSpec drives SpecRunner from inside a Kotest test body and asserts via assertOutputsMatch.
---

# Engine-driven verification

After the bridge work landed in Plans A–F (2026-05-07), verification is no longer a
separate post-run step. Instead it runs inline, inside a Kotest test body, through
standard assertion calls.

## How a run starts

`SpecRunnerCoordinator.startRun` spawns a worker thread and hands off to
`EngineDrivenRun.run`. That class owns the per-run lifecycle:

1. Registers the runner with the standalone-runner registry so Kotest callbacks can
   reach it.
2. Calls `SpecRunner.start` to prime the runner with the loaded spec and its bounds.
3. Suspends on the server thread (via `McDispatchers.Server`) while `onPhase` advances
   tick-by-tick through the spec's lifespan.

Relevant paths:
- `runner/SpecRunnerCoordinator.kt` — owns the registry; calls `EngineDrivenRun.run`.
- `runner/EngineDrivenRun.kt` — the per-run object; drives `SpecRunner` and holds the
  diagnostic `StateRecording`.

## How the test body drives the runner

`runRedstoneSpec` is the DSL entry point available inside any `RedstoneTestSpec` test
body. It suspends until the run completes, then returns the finished `StateRecording`.

```kotlin
test("comparator latches after 4 ticks") {
    val recording = runRedstoneSpec(specId)
    assertOutputsMatch(spec, recording)
    // or hand-written:
    recording.signalAt(BlockPos(4, 2, 1), tick = 4) shouldBe 15
}
```

The test body runs on the Kotest dispatcher; `runRedstoneSpec` marshals the actual
runner calls onto the MC server thread through `CoroutineDispatcherFactory`.

## Verification

`assertOutputsMatch(spec, recording)` is in `testing/RedstoneSpecAssertions.kt`. It
walks every output entry in the spec and delegates to standard Kotest `shouldBe`
assertions — so failures land at exact assertion granularity with Kotest's diff
output, not as a single rollup message.

For tests that want finer control, any `signalAt` / `stateAt` call on the recording
can be asserted directly with `shouldBe` or any Kotest matcher.

## What replaced OutputVerifier

`OutputVerifier` (the old post-run validator) was removed. Its responsibilities split:

| Old                                   | New                                              |
|---------------------------------------|--------------------------------------------------|
| Per-entry declared-check evaluation   | `assertOutputsMatch` in `RedstoneSpecAssertions` |
| Unexpected-change detection           | Hand-written assertions or custom matchers       |
| Post-run `VerificationResult` rollup  | Kotest `TestResult` with per-assertion failures  |

The "unexpected change" scan was not ported — the engine-driven path expects specs to
declare what they care about; out-of-band changes are caught by keeping specs tight
rather than by scanning every tick in every position.
