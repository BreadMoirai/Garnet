---
title: Post-Run Output Verification
tags: [verification, recording, simtime, lifecycle]
summary: Why OutputVerifier runs after the recording closes instead of inline in SpecRunner, and what invariants that buys.
---

# Post-Run Output Verification

`OutputVerifier.verify(spec, recording)` runs **once, after the run ends**, against a completed `StateRecording`. It is not invoked from `SpecRunner.onPhase`. This article explains why.

## What changed

Commit `dbec017` ("route output verification through OutputVerifier post-run") moved output checking out of the per-phase loop. `SpecRunner.onPhase` now only:

1. Increments `ticksElapsed` at `START_OF_TICK`.
2. Applies inputs at the current `SimTime` (`applyInputsAt`).
3. Checks breakpoints (`checkBreakpointsAt`).
4. Returns `true` once `ticksElapsed >= spec.lifespan`.

There is no `checkOutputsAt` anymore. Outputs are evaluated by the caller after `onPhase` returns `true`, by handing the spec and the closed `StateRecording` to `OutputVerifier`.

## Why post-run, not inline

**Mode-dependent semantics need the whole recording.**

- TICK_AWARE needs `postState[t-1]` to compute `changeTicks` and detect unexpected changes. An inline checker would have to buffer the entire post-state vector anyway.
- UPDATE_AWARE asserts on `view.changesAt(pos)` — the full change list — and emits `unexpected change` failures for any recorded change not declared by an entry. That's a set-difference, not a per-phase predicate.
- SIMPLE only cares about the final state at `SimTime(lifespan-1, END_OF_TICK, MAX_VALUE)`. Inline checking would either run on every phase (wasted work) or duplicate the end-of-run trigger.

All three branches are cleanly expressed as "given the recording, here are the failing checks." Inline evaluation would force each mode to track partial state and emit deferred failures — a strictly more complex shape.

**Replay determinism is decoupled from assertion logic.** `SpecRunner` is responsible for *driving the world identically to the recording* (input application, breakpoint checks). Verification is a pure function of the resulting `StateRecording`. Splitting them means verifier changes can't break replay timing, and replay changes can't silently change which assertions run.

## Invariants the verifier relies on

`OutputVerifier` assumes:

1. **`recording.initialSnapshot[output.pos]` exists** for every declared output. Both `verifyTickAware` and `verifyUpdateAware` `error()` if it is missing. This is the recorder's contract: capture initial state for every position inside bounds before the first change.
2. **`SimTime.END_OF_TICK` is the canonical post-state sample.** Every per-tick read is `view.stateAt(pos, SimTime(t, END_OF_TICK, Int.MAX_VALUE))`. The `Int.MAX_VALUE` order forces the view to return the *latest* state at that tick/phase, after all sub-tick changes are applied.
3. **The recording covers `[0, lifespan)`.** `SpecRunner` runs `onPhase` until `ticksElapsed >= spec.lifespan`; the recorder is expected to capture across that same window. `OutputVerifier` reads `postState[t]` for `t in 0 until lifespan` without bounds-checking the recording.
4. **`view.changesAt(pos)` returns only changes at that exact position**, not aggregated over neighbors. UPDATE_AWARE's set-difference logic would silently produce false positives otherwise.

## Output API surface

```
data class VerificationResult(val checks: List<TickCheck>) {
    val pass: Boolean get() = checks.all { it.pass }
}
```

Each `TickCheck` carries `simTime`, `label`, `expected`, `actual`, `pass`. The verifier never throws on a failed assertion — failures are returned as `pass = false` checks. Errors are reserved for invariant violations (missing snapshot entry).

## Related

- `docs/runner/spec-modes.md` — what each mode actually checks.
