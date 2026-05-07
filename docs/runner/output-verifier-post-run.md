---
title: Post-Run Output Verification
tags: [verification, recording, simtime, lifecycle]
summary: Why OutputVerifier runs after the recording closes instead of inline in SpecRunner, and what invariants that buys.
---

# Post-Run Output Verification

`OutputVerifier.verify(spec, recording)` runs **once, after the run ends**, against a completed `StateRecording`. It is not invoked from `SpecRunner.onPhase`.

## What `SpecRunner.onPhase` does

1. Increments `ticksElapsed` at `START_OF_TICK`.
2. Applies inputs at the current `SimTime` (`applyInputsAt`).
3. Returns `true` once `ticksElapsed >= spec.lifespan`.

There is no per-phase output check. Outputs are evaluated by the caller after `onPhase` returns `true`, by handing the spec and the closed `StateRecording` to `OutputVerifier`.

## Why post-run, not inline

**Unexpected-change detection needs the whole post-state series.** For each output position the verifier walks `postState[t]` for `t in 0 until lifespan` and emits a failing check for any tick where the post-state changed but no `SpecEntry` declared a check at that tick. This is a set-difference between recorded changes and declared entries — naturally a post-run computation.

**Replay determinism is decoupled from assertion logic.** `SpecRunner` is responsible for *driving the world identically to the recording* (input application). Verification is a pure function of the resulting `StateRecording`. Splitting them means verifier changes can't break replay timing, and replay changes can't silently change which assertions run.

## What gets checked

Per output `SpecEntry` (i.e., each row with `kind = OUTPUT`):

1. **Declared check.** Looks up the recorded post-state at `entry.time` (anchored to `END_OF_TICK` with `order = MAX_VALUE` for default-phase entries) and evaluates `entry.condition` against it. Emits one `TickCheck`.
2. **Unexpected change scan.** For each tick `t in 0 until lifespan`, if `postState[t] != postState[t-1]` (with `postState[-1] := initialSnapshot`) and no entry at this position declared `t`, emits a failing `TickCheck` labelled "unexpected change".

Multiple entries at the same `(pos)` describe a multi-step output; each gets its own declared check.

## Invariants the verifier relies on

1. **`recording.initialSnapshot[output.pos]` exists** for every declared output. The verifier `error()`s if missing — this is the recorder's contract: capture initial state for every position inside bounds before the first change.
2. **`SimTime.END_OF_TICK` is the canonical post-state sample.** Every per-tick read is `view.stateAt(pos, SimTime(t, END_OF_TICK, Int.MAX_VALUE))`. The `Int.MAX_VALUE` order forces the view to return the *latest* state at that tick/phase, after all sub-tick changes are applied.
3. **The recording covers `[0, lifespan)`.** `SpecRunner` runs `onPhase` until `ticksElapsed >= spec.lifespan`; the recorder is expected to capture across that same window.

## Output API surface

```kotlin
data class VerificationResult(val checks: List<TickCheck>) {
    val pass: Boolean get() = checks.all { it.pass }
}
```

Each `TickCheck` carries `simTime`, `label`, `expected`, `actual`, `pass`. The verifier never throws on a failed assertion — failures are returned as `pass = false` checks. Errors are reserved for invariant violations (missing snapshot entry).
