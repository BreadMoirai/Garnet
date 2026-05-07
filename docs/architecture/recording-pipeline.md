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
    │   .start       │   (per-phase        (flat SpecEntry    .start                .verify
    │   .onPhaseStart│    snapshots)       rows derived       .onPhase              (per-entry
    │                │                     from changes)      applies inputs,        post-state
    │                │                                        samples outputs)       checks +
    │                │                                                              unexpected-
    │                │                                                              change
    │                │                                                              detection)
    └────────────────┴─── SpecBlockEntity (origin, bounds) ─────────────────────────┘
```

## Stage 1 — Record (capture)

**Owner:** `runner/StateRecorder.kt`, driven by `RedstoneSpecRecorderBlock` + `event/SubTickPhaseEvents.kt`.

Per server tick, for each `Phase` of interest, the recorder samples block states inside its bounding region and appends them to a `StateRecording`. Output is in-memory; nothing is written to disk yet.

**Invariants leaving this stage:**
- Snapshot exists for every `END_OF_TICK` boundary covered by the run.
- Coordinates are origin-relative (the BE's `originPos` is `(0,0,0)`; bounds are a `Vec3i` extent).
- All inputs the user marked are present at their captured `SimTime`.

## Stage 2 — Finalize (derive entries)

**Owner:** `runner/RecordingFinalizer.kt`. Pure function: `(baseSpec, recording) → RedstoneSpec?`.

Walks the recording, diffs adjacent snapshots, and emits **flat `SpecEntry` rows** — one per change-tick at each I/O position:

- For each input position with marker entries: emits one `START`-time entry capturing the initial state, then one entry per later change-tick.
- For each output position: emits one entry per change-tick observed in the recording.

`(pos, kind, label, color)` headers come from the base spec's marker entries — those provided by the user before recording. Marker times/conditions are discarded; everything is re-derived from the recording.

**Invariants leaving this stage:**
- Every `entry.pos` lies inside `bounds` (enforced by `RedstoneSpec.init {}`).
- Output entries within a `(pos, kind)` group are sorted by `SimTime`.
- Bounds remain `Vec3i` size; entry positions are origin-local.

This is the point where the in-flight `StateRecording` becomes a persistable `RedstoneSpec`. After finalize, the recording is no longer needed.

## Stage 3 — Run (replay)

**Owner:** `runner/SpecRunner.kt`, dispatched by `runner/SpecRunnerCoordinator.kt`.

Given a finalized spec, `SpecRunner` walks each `Phase` and:
1. Applies any input `SpecEntry` whose `time` matches the current `SimTime`. Button-style inputs route through `ButtonBlock.press` via `tryApplyAsPlayerInteraction` (see [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md)) — raw `setBlock` would skip the depower scheduled tick.
2. Samples block states and accumulates a fresh `StateRecording` (yes — running a spec also records).

**Invariants leaving this stage:**
- The runner-produced recording covers the same SimTime range as the original.
- Determinism: a spec replayed twice produces the same recording, modulo MC-side scheduled-tick ordering.

## Stage 4 — Verify (assert)

**Owner:** `runner/OutputVerifier.kt`. Post-run, *not* during `onPhase`.

For each output `SpecEntry`, evaluates its condition against the recorded post-tick state at the entry's `SimTime`. Additionally, for each output position, walks the per-tick post-state series and emits a failing `TickCheck` for any change tick that no entry declared (the "unexpected change" diagnostic).

**Output:** a `TestResult` consumed by the runner block's UI feedback and (in tests) the gametest harness.

## Where each stage lives on disk and over the wire

| Stage | In memory | On disk | Over the network |
|---|---|---|---|
| Record | `StateRecording` | _(transient)_ | _(server-only)_ |
| Finalize | `RedstoneSpec` | `<id>.spec.kts` + `<id>.nbt` (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)) | `SaveSpecEntryC2SPayload` for individual edits (encoded via `SpecJsonCodec`) |
| Run | `StateRecording` (replay) | _(transient)_ | _(server-only)_ |
| Verify | `TestResult` | _(transient)_ | _(displayed via S2C)_ |

## Common confusions

- **The runner records too.** Stage 3 produces a fresh `StateRecording` even though the goal is verification. The verifier in Stage 4 compares *that* recording against the spec — it does not re-sample the world directly.
- **Finalize is pure.** It takes the recording in, returns a spec out. No I/O.
- **A `SpecEntry` is one (time, condition) pair.** Multi-step input or output sequences are represented by *multiple* entries at the same `(pos, kind)` — there is no nested timeline list.
