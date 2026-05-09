---
title: The record → emit → run → verify pipeline
tags: [pipeline, lifecycle, recording, finalization, runner, verification, dataflow]
summary: End-to-end flow of a spec from world capture to validation, naming the handoff types and the invariants that hold at each boundary.
---

# The record → emit → run → verify pipeline

This is the system's spine. Every other concern (UI, persistence, networking) feeds in or out of one of the four stages below. Each stage is documented in detail elsewhere; this article exists to show how they compose.

```
   world          recorder            dsl emitter           runner              assertions
    │                │                     │                   │                    │
    │  StateRecorder │  StateRecording  → .spec.kts  →  runRedstoneSpec  →  inline Kotest shouldBe
    │   .start       │   (per-phase        (DSL source    (invokes spec        (failures thrown at
    │   .onPhaseStart│    snapshots)        text derived   lambda, fires        end of run as
    │                │                     from changes)  inputs, asserts)     AssertionError)
    └────────────────┴─── SpecBlockEntity (origin, bounds) ─────────────────────────┘
```

## Stage 1 — Record (capture)

**Owner:** `runner/StateRecorder.kt`, driven by `RedstoneSpecRecorderBlock` + `event/SubTickPhaseEvents.kt`.

Per server tick, for each `Phase` of interest, the recorder samples block states inside its bounding region and appends them to a `StateRecording`. Output is in-memory; nothing is written to disk yet.

**Invariants leaving this stage:**
- Snapshot exists for every `END_OF_TICK` boundary covered by the run.
- Coordinates are origin-relative (the BE's `originPos` is `(0,0,0)`; bounds are a `Vec3i` extent).
- All inputs the user marked are present at their captured `SimTime`.

## Stage 2 — Emit (derive DSL source)

**Owner:** `runner/RecordingDslEmitter.kt`. Pure function: `(recording) → String` (`.spec.kts` source text).

Walks the recording, diffs adjacent snapshots, and emits `input(…) { … }` / `output(…) { … }` DSL blocks — one call-to `at(tick)` per change-tick at each I/O position.

**Invariants leaving this stage:**
- The emitted `.spec.kts` evaluates to a `RedstoneSpec` whose `block` lambda, when invoked by the runner, registers the same inputs and assertions as the recording observed.
- Bounds remain `Vec3i` size; positions are origin-local in all emitted coordinates.

This is the point where the in-flight `StateRecording` becomes a persistable `.spec.kts` file. After emit, the recording is no longer needed for replay.

## Stage 3 — Run (replay)

**Owner:** `runner/runRedstoneSpec.kt`.

`runRedstoneSpec(level, origin, spec)` is a suspend fun that:
1. Takes a `SpecSnapshot` of the region, then restores it so the run starts from a known state.
2. Invokes `spec.block` once with a `SpecRun` receiver — this populates `inputActions` and `assertions` callback maps.
3. Loops `0 until spec.lifespan` ticks, firing `START_OF_TICK` input callbacks then `awaitTickEnd()` then `END_OF_TICK` assertion callbacks.
4. If `spec.strict`, scans for unexpected change-ticks at declared output positions and appends failures.
5. If any failures were collected, throws `AssertionError`.

Button-style inputs route through `ButtonBlock.press` via `tryApplyAsPlayerInteraction` (see [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md)) — raw `setBlock` would skip the depower scheduled tick.

**Invariants leaving this stage:**
- Assertions fired inline: failures are Kotest `shouldBe` violations or explicit `AssertionError`s accumulated in `SpecRun.failures`.
- Determinism: a spec replayed twice produces the same failure set, modulo MC-side scheduled-tick ordering.

## Stage 4 — Verify (assert)

Verification is **inline** in Stage 3 — there is no separate verify stage. Assertion callbacks registered by `output(…) { … }` blocks execute inside the tick loop. If `runRedstoneSpec` throws, the caller (runner block or gametest) sees it as a test failure.

**Output:** `AssertionError` on failure, or a `StateRecording` of the replay on success — consumed by the runner block's UI feedback and the HTML/JUnit reports.

## Where each stage lives on disk and over the wire

| Stage | In memory | On disk | Over the network |
|---|---|---|---|
| Record | `StateRecording` | _(transient)_ | _(server-only)_ |
| Emit | `String` (DSL text) → `RedstoneSpec` | `<id>.spec.kts` + `<id>.nbt` (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)) | _(server-only save)_ |
| Run | `StateRecording` (replay) | _(transient)_ | _(server-only)_ |
| Verify | `AssertionError` / `StateRecording` | _(transient)_ | _(displayed via S2C)_ |

## Common confusions

- **The runner records too.** Stage 3 produces a fresh `StateRecording` even though the goal is verification — it returns this recording as the function result for optional diagnostics.
- **Emit is pure.** It takes the recording in, returns DSL source text out. No I/O.
- **The spec lambda runs once, not once per tick.** `spec.block` is invoked once before the tick loop to register callbacks. The callbacks themselves fire during the loop.
