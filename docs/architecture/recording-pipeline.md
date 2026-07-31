---
title: The record → emit → run → verify pipeline
tags: [pipeline, lifecycle, recording, finalization, runner, verification, dataflow]
summary: End-to-end flow of a spec from world capture to validation, naming the handoff types and the invariants that hold at each boundary — and why the record/emit half currently has no in-game trigger.
---

# The record → emit → run → verify pipeline

This is the system's spine. Every other concern (UI, persistence, networking) feeds in or out of
one of the four stages below. Each stage is documented in detail elsewhere; this article exists to
show how they compose.

**No stage below has an in-game entry point today.** `GarnetRecorderBlock`, `GarnetRunnerBlock`,
and `SpecBlockEntity` — the blocks that used to drive Stages 1 and 3 — were deleted; there is
currently no product surface that places a recorder, starts a capture, or launches a replay. The
pipeline itself is intact and exercised by unit/gametest coverage (`StateRecorder`,
`RecordingDslEmitter`, `runGarnetSpec` are all directly callable and directly tested), but the only
callers today are tests: the `testSupport` Kotest harness calls `runGarnetSpec` directly, and
nothing calls `StateRecorder.start`/`StateRecorder.stop` outside its own unit test. A future
Compose dock panel is the intended replacement for the deleted blocks; see
[architecture/module-map.md](module-map.md) for where each piece now lives.

```
   world          recorder            dsl emitter           runner              assertions
    │                │                     │                   │                    │
    │  StateRecorder │  StateRecording  → .spec.kts  →  runGarnetSpec  →  inline Kotest shouldBe
    │   .start       │   (per-phase        (DSL source    (invokes spec        (failures thrown at
    │   .onPhaseStart│    snapshots)        text derived   lambda, fires        end of run as
    │                │                     from changes)  inputs, asserts)     AssertionError)
    └────────────────┴──── caller-supplied (level, origin, bounds) ─────────────────┘
```

## Stage 1 — Record (capture)

**Owner:** `playback/recorder/StateRecorder.kt`, driven by `mc/SubTickPhaseEvents.kt` (registered
once in `Garnet.onInitialize`, which forwards every `Phase` tick to
`StateRecorder.onPhaseForActiveRecorders`). **No caller currently activates a recorder** — the
plumbing that would drive it (the recorder block) is gone.

Per server tick, for each `Phase` of interest, an active recorder samples block states inside its
bounding region and appends them to a `StateRecording`. Output is in-memory; nothing is written to
disk yet.

**Invariants leaving this stage:**
- Snapshot exists for every `END_OF_TICK` boundary covered by the run.
- Coordinates are origin-relative (bounds are a `Vec3i` extent from a caller-supplied origin).
- All inputs the caller marked are present at their captured `SimTime`.

## Stage 2 — Emit (derive DSL source)

**Owner:** `playback/recorder/RecordingDslEmitter.kt`. Pure function: `(recording) → String`
(`.spec.kts` source text). Also provides `emitStub(id)`, the minimal-spec text the Explorer's
"New Spec" action (`EditorNewSpec`) writes for a brand-new file — this is the emitter's one live
caller today.

Walks the recording, diffs adjacent snapshots, and emits `input(…) { … }` / `output(…) { … }` DSL
blocks — one call-to `at(tick)` per change-tick at each I/O position.

**Invariants leaving this stage:**
- The emitted `.spec.kts` evaluates to a `GarnetSpec` whose `block` lambda, when invoked by the
  runner, registers the same inputs and assertions as the recording observed.
- Bounds remain `Vec3i` size; positions are origin-local in all emitted coordinates.

This is the point where an in-flight `StateRecording` becomes a persistable `.spec.kts` file.
After emit, the recording is no longer needed for replay.

## Stage 3 — Run (replay)

**Owner:** `testing/runner/runGarnetSpec.kt`. Called today from the `testSupport` Kotest harness
(`GarnetTestSpec`) and directly from gametest/unit specs — not from any UI.

`runGarnetSpec(level, origin, spec)` is a suspend fun that:
1. Takes a `SpecSnapshot` of the region, then restores it so the run starts from a known state.
2. Invokes `spec.block` once with a `SpecRun` receiver — this populates `inputActions` and
   `assertions` callback maps.
3. Loops `0 until spec.lifespan` ticks, firing `START_OF_TICK` input callbacks then
   `awaitTickEnd()` then `END_OF_TICK` assertion callbacks.
4. If `spec.strict`, scans for unexpected change-ticks at declared output positions and appends
   failures.
5. If any failures were collected, throws `AssertionError`.

Button-style inputs route through `ButtonBlock.press` via `tryApplyAsPlayerInteraction` (see
[runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md)) — raw
`setBlock` would skip the depower scheduled tick.

**Invariants leaving this stage:**
- Assertions fired inline: failures are Kotest `shouldBe` violations or explicit `AssertionError`s
  accumulated in `SpecRun.failures`.
- Determinism: a spec replayed twice produces the same failure set, modulo MC-side scheduled-tick
  ordering.

## Stage 4 — Verify (assert)

Verification is **inline** in Stage 3 — there is no separate verify stage. Assertion callbacks
registered by `output(…) { … }` blocks execute inside the tick loop. If `runGarnetSpec` throws,
the caller (a Kotest test today) sees it as a test failure.

**Output:** `AssertionError` on failure, or a `StateRecording` of the replay on success —
currently consumed only by test infrastructure (`DiagnosticRecorderListener`), not by any
in-game UI feedback.

## Where each stage lives on disk and over the wire

| Stage | In memory | On disk | Over the network |
|---|---|---|---|
| Record | `StateRecording` | _(transient)_ | _(no wire path — no product caller)_ |
| Emit | `String` (DSL text) → `GarnetSpec` | `<id>.spec.kts` + `<id>.nbt` (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)) | _(no wire path — no product caller)_ |
| Run | `StateRecording` (replay) | _(transient)_ | _(no wire path — no product caller)_ |
| Verify | `AssertionError` / `StateRecording` | _(transient)_ | _(surfaced only through test reports today)_ |

## Common confusions

- **The runner records too.** Stage 3 produces a fresh `StateRecording` even though the goal is
  verification — it returns this recording as the function result for optional diagnostics.
- **Emit is pure.** It takes the recording in, returns DSL source text out. No I/O.
- **The spec lambda runs once, not once per tick.** `spec.block` is invoked once before the tick
  loop to register callbacks. The callbacks themselves fire during the loop.
- **"Engine intact, no caller" is not a bug.** Stages 1–4 above are fully covered by unit and
  gametest specs that call them directly; the gap is a *product* gap (no dock panel wires a
  recorder/runner UI to them yet), not a broken pipeline.
