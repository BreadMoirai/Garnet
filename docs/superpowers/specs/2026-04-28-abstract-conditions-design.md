# Abstract Conditions: Per-Mode Verification Semantics

## Goal

Make each `SpecMode` enforce a distinct, well-defined contract on output block-state changes during a test run. Today every mode treats `OutputSpec` entries as spot checks at specific `SimTime`s. This design replaces that with mode-specific contracts:

- **SIMPLE** — only the end state matters; inputs apply once at start.
- **TICK_AWARE** — output transitions are scheduled at tick granularity; any tick with a change must be in the spec, and any tick listed in the spec must produce the expected post-tick state.
- **UPDATE_AWARE** — output transitions must match the spec at full `(tick, phase, order)` granularity, exactly. Any unlisted change fails the test.

Inputs remain a *driven schedule* (applied to the world at their listed SimTime); only outputs are subject to the strict transition-list contract.

## Non-Goals

- Renaming `UPDATE_AWARE`. The codebase keeps the existing identifier.
- Migrating saved specs. The on-disk and wire formats for `SimTime` are unchanged.
- Changing breakpoint semantics. Breakpoints stay live-evaluated by `SpecRunner`, mode-independent.

## Data Model Changes

### `SimTime` sentinels

- Rename `SimTime.INIT` → `SimTime.START`. Underlying value unchanged: `SimTime(-1, START_OF_TICK, 0)`.
- Add `SimTime.END`. Sentinel value sorts after every real tick within `lifespan`; concretely `SimTime(Int.MAX_VALUE, END_OF_TICK, 0)`.
- Both sentinels round-trip through the existing `SimTime.CODEC` and `STREAM_CODEC` with no schema change.

Existing data files that contain `SimTime(-1, START_OF_TICK, 0)` decode unchanged after the rename — only the Kotlin constant identifier changes.

### `SpecMode`

Unchanged: `SIMPLE`, `TICK_AWARE`, `UPDATE_AWARE`.

### `InputSpec` and `OutputSpec`

Both keep their current shape: `entries: List<Pair<SimTime, StateCondition>>`.

The `InputSpec` initializer's existing requirement of "exactly one INIT entry" is renamed to "exactly one `START` entry", and applies in all three modes.

Per-mode SimTime constraints (validated at editor save / load time, not in the data class init):

| Mode | InputSpec entries | OutputSpec entries |
|---|---|---|
| SIMPLE | Only `START` (the required one). No other entries. | At most one entry, pinned to `END`. |
| TICK_AWARE | `START` plus zero or more non-sentinel entries canonicalized to `(tick, START_OF_TICK, 0)`. | `START`, `END`, or non-sentinel `(tick, START_OF_TICK, 0)`. |
| UPDATE_AWARE | `START` plus zero or more entries at any `SimTime`. | `START`, `END`, or any `SimTime`. |

`OutputSpec` has no required entries — an output may be unconstrained.

## Runner Semantics

### Inputs (all modes) — driven schedule

Unchanged in spirit. At each SimTime the runner processes, find input entries whose SimTime equals the current one (with the existing `USER_INTERACTION` fold-in for `START_OF_TICK` and `END_OF_TICK`) and apply the condition to the world.

- `START` entries fire before tick 0 (today's INIT behavior, just renamed).
- `END` entries on inputs are allowed for symmetry but treated as a no-op in typical use; if present, they fire at `(lifespan, END_OF_TICK)`. The SIMPLE editor UI does not surface END for inputs.

### Outputs — strict transition-list contract

Output verification runs **after** the test run completes, against the captured `StateRecording`. During the run, `SpecRunner` no longer emits per-tick `TickCheck`s for outputs; it just lets the recording accumulate. Verification is centralized in a new `OutputVerifier` (see Architecture).

#### SIMPLE

For each `OutputSpec`:

1. If the `END` entry is present, evaluate its condition against the output position's state at `(lifespan, END_OF_TICK)`. Fail if false.
2. If the `END` entry is absent, the output is unconstrained — no checks emitted.
3. Intermediate changes are not checked.

SIMPLE outputs do not assert the start state. The initial conditions of the circuit are driven by `InputSpec` entries pinned to `START`, not validated against outputs.

#### TICK_AWARE

For each `OutputSpec`:

1. From the recording, derive the **post-tick state** of the output position for every tick `t` in `0..lifespan` (i.e., the state after `(t, END_OF_TICK)` is processed). Tick `-1`'s post-state is the initial state.
2. The set of **change ticks** is `{ t : post(t) != post(t-1) }`.
3. For each entry whose SimTime is a non-sentinel tick `t`: `post(t)` must satisfy the entry's condition. Otherwise fail with "wrong value at tick t".
4. For each change tick `t`: there must be an entry at tick `t`. Otherwise fail with "unexpected change at tick t".
5. `START` and `END` are evaluated as in SIMPLE. `END` is redundant if a per-tick entry already covers `lifespan`, but allowed.

#### UPDATE_AWARE

For each `OutputSpec`:

1. Walk every `BlockStateChange` in the recording whose position equals the output position, in SimTime order.
2. For each change at SimTime `s`: there must be an entry whose SimTime equals `s` exactly, and the entry's condition must be satisfied by the post-change state. Otherwise fail with "unexpected change at s" or "wrong value at s".
3. For each non-sentinel entry at SimTime `s`: there must be a recorded change at `s`. Otherwise fail with "expected change missing at s".
4. `START` and `END` evaluated as in SIMPLE.

### Breakpoints — unchanged

`SpecRunner` continues to evaluate breakpoint conditions live during the run. Mode does not affect breakpoint behavior.

## Architecture

### New: `OutputVerifier`

Lives alongside `SpecRunner` in `runner/`. Pure function over data:

```
OutputVerifier.verify(spec: RedstoneSpec, recording: StateRecording): VerificationResult
```

`VerificationResult` carries a list of structured pass/fail records — one per asserted entry plus one per "unexpected change" diagnostic — sufficient to render in the existing test result UI. No mutation, no world access.

### `SpecRunner` changes

- Stop calling `checkOutputsAt` for TICK_AWARE / UPDATE_AWARE during the live run.
- For SIMPLE, the runner could in principle still spot-check START at tick 0 and END at lifespan, but for symmetry we route SIMPLE through `OutputVerifier` too.
- The end-of-run hook that today returns `checks: List<TickCheck>` instead returns the verifier's structured result.

### `RecordingFinalizer` changes

The finalizer turns a captured recording into a `RedstoneSpec` skeleton (used by the recorder → editor flow). Update its mode-specific output emission:

| Mode | Output entries emitted |
|---|---|
| SIMPLE | One `END` entry (final state condition) per output position. |
| TICK_AWARE | One entry per change tick, SimTime canonicalized to `(tick, START_OF_TICK, 0)`, condition derived from the post-tick state. |
| UPDATE_AWARE | One entry per recorded `BlockStateChange`, at full SimTime, condition derived from the post-change state. (Closest to today's behavior.) |

Inputs continue to be emitted from the recorder's input-snapshot logic with the `START` rename and the SIMPLE collapse to START + END.

### Editor UI (`SpecEditorScreen`)

The entry table behavior diverges by mode:

- **SIMPLE** — render two rows per input/output, labeled `START` and `END`. SimTime cells are not editable; only the condition is. If an entry is missing END, show an "add END" affordance.
- **TICK_AWARE** — SimTime column displays just the tick number (phase/order hidden). `START`/`END` rows are pinned at top and bottom of each entry list.
- **UPDATE_AWARE** — full `(tick, phase, order)` UI as today.

The mode-aware grouping in `SpecEditorScreen.kt:361-363` (`SpecMode.SIMPLE -> true`, `TICK_AWARE -> a.tick == b.tick`, `UPDATE_AWARE -> a == b`) is preserved as the deduplication rule.

`RecorderSetupScreen` is unaffected aside from string consistency; it only selects the mode for the recording.

### Persistence and packets

No format changes. `SimTime.CODEC`, `SimTime.STREAM_CODEC`, and the codecs for `InputSpec` / `OutputSpec` / `RedstoneSpec` are untouched. Only the Kotlin constant `INIT` → `START` rename and the new `END` constant.

## Tests

### Unit

- New `OutputVerifierTest` with synthetic `RedstoneSpec` + synthetic `StateRecording` fixtures. One case per contract bullet:
  - SIMPLE: start mismatch, end mismatch, both pass.
  - TICK_AWARE: wrong value at listed tick, unexpected change at unlisted tick, missing change vs. an entry's expected value, all pass.
  - UPDATE_AWARE: wrong value at SimTime, unexpected change at unlisted SimTime, missing change at listed SimTime, all pass.
- Update `RecordingFinalizerTest` to cover the new SIMPLE START+END emission and the TICK_AWARE per-change-tick emission.
- Update `SpecPersistenceTest` and `RedstoneSpecTest` for the `INIT` → `START` rename and any new SIMPLE shape assertions.
- Update `InputSpec`'s `require { ... INIT ... }` test to expect `START`.

### Game tests

- For each of `SIMPLE`, `TICK_AWARE`, `UPDATE_AWARE`, add a positive recorder → runner round-trip on a known-good circuit.
- For each mode, add one negative case where the circuit deviates from the spec and the test must fail with the appropriate diagnostic.

## Open Questions

None — all clarifications were resolved in the brainstorming pass.

## Out of Scope

- Renaming `UPDATE_AWARE`.
- Migrating saved spec files (no on-disk schema change).
- Visual or breakpoint changes.
- Reworking input semantics in TICK_AWARE / UPDATE_AWARE; inputs remain a driven schedule.
