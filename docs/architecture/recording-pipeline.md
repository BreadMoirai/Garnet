---
title: The record → finalize → run → verify pipeline
tags: [pipeline, lifecycle, recording, finalization, runner, verification, dataflow]
summary: End-to-end flow of a spec from world capture to validation, naming the handoff types and the invariants that hold at each boundary.
---

# The record → finalize → run → verify pipeline

This is the system's spine. Every other concern (UI, persistence, networking) feeds in or out of one of the four stages below. Each stage is documented in detail elsewhere; this article exists to show how they compose.

```
   world          recorder              finalizer            runner              verifier
    │                │                     │                   │                    │
    │  StateRecorder │  StateRecording  → RedstoneSpec  →  SpecRunner       →  OutputVerifier
    │   .start       │   (per-phase        (input + output    .start                .verify
    │   .onPhaseStart│    snapshots)       entries derived    .onPhase              (per-mode
    │                │                     by mode)           applies inputs,        invariants)
    │                │                                        samples outputs)
    └────────────────┴─── SpecBlockEntity (origin, bounds) ─────────────────────────┘
```

## Stage 1 — Record (capture)

**Owner:** `runner/StateRecorder.kt`, driven by `RedstoneSpecRecorderBlock` + `event/SubTickPhaseEvents.kt`.

Per server tick, for each `Phase` of interest, the recorder samples block states inside its bounding region and appends them to a `StateRecording`. Output is in-memory; nothing is written to disk yet.

**Invariants leaving this stage:**
- Snapshot exists for every `END_OF_TICK` boundary covered by the run.
- Coordinates are origin-relative (the BE's `originPos` is `(0,0,0)`).
- All inputs the user marked are present at their captured `SimTime`.

## Stage 2 — Finalize (derive entries)

**Owner:** `runner/RecordingFinalizer.kt`. Pure function: `(baseSpec, recording) → RedstoneSpec?`.

Walks the recording, diffs adjacent snapshots, and emits `InputSpec` and `OutputSpec` entries. Behavior pivots on `SpecMode`:
- `SIMPLE` — emits only START + END output entries (the START output check was dropped — see [runner/spec-modes.md](../runner/spec-modes.md)).
- `TICK_AWARE` — emits one entry per tick where outputs changed.
- `UPDATE_AWARE` — emits per-update-phase entries.

**Invariants leaving this stage:**
- `InputSpec` always contains exactly one `START` entry (see [persistence/spec-data-model-invariants.md](../persistence/spec-data-model-invariants.md)).
- Output entries are sorted by `SimTime`.
- The returned spec round-trips through the JSON+NBT codec.

This is the point where the in-flight `StateRecording` becomes a persistable `RedstoneSpec`. After finalize, the recording is no longer needed.

## Stage 3 — Run (replay)

**Owner:** `runner/SpecRunner.kt`, dispatched by `runner/SpecRunnerCoordinator.kt`.

Given a finalized spec, `SpecRunner` walks each `Phase` and:
1. Applies any `InputSpec` entries scheduled for the current `SimTime`. Button-style inputs route through `ButtonBlock.press` via `tryApplyAsPlayerInteraction` (see [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md)) — raw `setBlock` would skip the depower scheduled tick.
2. Samples block states and accumulates a fresh `StateRecording` (yes — running a spec also records).

**Invariants leaving this stage:**
- The runner-produced recording covers the same SimTime range as the original.
- Input dispatch is mode-independent (only output verification is mode-aware).
- Determinism: a spec replayed twice produces the same recording, modulo MC-side scheduled-tick ordering.

## Stage 4 — Verify (assert)

**Owner:** `runner/OutputVerifier.kt`. Post-run, *not* during `onPhase`.

Compares the runner-produced recording against the spec's declared `OutputSpec` entries. Why post-run rather than streaming: mode-dependent semantics need to see the whole trace before deciding what counts as a violation (see [runner/output-verifier-post-run.md](../runner/output-verifier-post-run.md)).

**Output:** a `TestResult` consumed by the runner block's UI feedback and (in tests) the gametest harness.

## Where each stage lives on disk and over the wire

| Stage | In memory | On disk | Over the network |
|---|---|---|---|
| Record | `StateRecording` | _(transient)_ | _(server-only)_ |
| Finalize | `RedstoneSpec` | `<id>.json` + `<id>.nbt` (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)) | `SaveSpecEntryC2SPayload` for individual edits |
| Run | `StateRecording` (replay) | _(transient)_ | _(server-only)_ |
| Verify | `TestResult` | _(transient)_ | _(displayed via S2C)_ |

## Common confusions

- **The runner records too.** Stage 3 produces a fresh `StateRecording` even though the goal is verification. The verifier in Stage 4 compares *that* recording against the spec — it does not re-sample the world directly.
- **Finalize is pure.** It takes the recording in, returns a spec out. No I/O. This is what makes `RecordingFinalizerTest` a JUnit test rather than a gametest (see [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md)).
- **Mode only affects two stages.** Finalize uses it to decide which entries to emit; Verify uses it to decide which entries are required. Stages 1 and 3 don't read `SpecMode` at all.
