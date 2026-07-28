---
title: runGarnetSpec — inline verification
tags: [execution, kotest, verification]
summary: How runGarnetSpec drives the tick loop from inside a Kotest test body and asserts via inline shouldBe callbacks in the spec lambda.
---

# runGarnetSpec — inline verification

Verification is not a separate post-run step. Assertions execute inline,
inside the tick loop, through callbacks registered by the spec's `block`
lambda. The spec *is* the test.

## How a run starts

`runGarnetSpec(level, origin, spec)` is a suspend fun in `runner/runGarnetSpec.kt`.
It is called directly from a Kotest test body (or from the runner block's
server coroutine). There is no coordinator singleton or per-run object —
all state lives on the call stack.

1. Takes a `SpecSnapshot` of the region so it can be restored before and after.
2. Restores the snapshot, activating a `StateRecorder` over the bounds.
3. Invokes `spec.block` **once** with a `SpecRun` receiver. This call
   populates two sorted callback maps:
   - `SpecRun.inputActions` — indexed by `SimTime`; fire at `START_OF_TICK`.
   - `SpecRun.assertions` — indexed by `SimTime`; fire at `END_OF_TICK`.
4. Loops `0 until spec.lifespan` ticks: fires start-of-tick inputs, calls
   `awaitTickEnd()`, fires end-of-tick assertions.
5. If `spec.strict`, scans for unexpected change-ticks at declared output
   positions.
6. If any failures were collected, throws `AssertionError` with all messages
   joined.
7. Restores the snapshot, deactivates the recorder. Returns the `StateRecording`.

## How the test body uses it

```kotlin
test("comparator latches after 4 ticks") {
    runGarnetSpec(level, origin, spec)
    // throws AssertionError if the spec's output assertions failed
}
```

The spec itself carries all assertions:

```kotlin
// inside the .spec.kts file
garnetSpec("comparator_latch") {
    lifespan = 6
    input(1, 1, 0) {
        at(tick = 0) { press() }
    }
    output(4, 1, 2, label = "latch") {
        at(tick = 4) { powered shouldBe true }
    }
}
```

`runGarnetSpec` is the only public entry point. The test body never needs
to call `assertOutputsMatch` separately — the lambda's `output { … }` blocks
already contain all assertions.

## What replaced the old engine trio

The old `SpecRunnerCoordinator` / `EngineDrivenRun` / `SpecRunner` trio
was replaced by a single suspend function and a lean `SpecRun` context object.

| Old                                   | New                                              |
|---------------------------------------|--------------------------------------------------|
| `SpecRunnerCoordinator.startRun`      | `runGarnetSpec(level, origin, spec)`           |
| `EngineDrivenRun.run`                 | Tick loop inside `runGarnetSpec`               |
| `SpecRunner.applyCondition`           | `InputScope` callbacks + `tryApplyAsPlayerInteraction` |
| `assertOutputsMatch` / `OutputVerifier` | `OutputScope` callbacks inline in the tick loop  |
| `RecordingFinalizer`                  | `RecordingDslEmitter` (emit stage, not run stage)|

## strict mode

If `GarnetSpec.strict = true`, `runGarnetSpec` additionally scans the
replay recording for change-ticks at declared output positions that were
**not** declared in the spec. Each unexpected change adds a `SpecFailure`
entry. This replaces the old "unexpected change detection" that
`OutputVerifier` performed.
