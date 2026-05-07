---
title: Spec Modes (SIMPLE / TICK_AWARE / UPDATE_AWARE)
tags: [execution, modes, verification, finalization]
summary: What each SpecMode enforces, what it ignores, and where the conditional branches live.
---

# Spec Modes

`SpecMode` (`data/SpecMode.kt`) selects the contract that `RecordingFinalizer` derives and that `OutputVerifier` enforces. The mode lives on `RedstoneSpec.mode`; it changes the shape of `OutputSpec.entries` and the strictness of post-run checks. **Inputs are mode-independent** — `RecordingFinalizer.deriveInputEntries` always emits a `START` entry plus one entry per changed tick, regardless of mode.

The branching happens in two places only:

- `RecordingFinalizer.deriveOutputEntries` — `if (mode == SpecMode.SIMPLE) { … return listOf(END to …) }`. Non-SIMPLE modes derive one entry per changed tick.
- `OutputVerifier.verify` — `when (spec.mode) { … }` dispatches to `verifySimple` / `verifyTickAware` / `verifyUpdateAware`.

## SIMPLE

**Contract:** the output's settled state at the end of the run matches the recorded final state. Nothing else.

- Finalize: emits a single entry `SimTime.END → propsToCondition(finalState)`. Earlier transitions are deliberately dropped.
- Verify (`verifySimple`): reads `view.stateAt(pos, SimTime(lifespan-1, END_OF_TICK, MAX_VALUE))` and asserts the END entry's condition.
- **What it ignores:** intermediate transitions, glitches, ordering, update flow.

Why no START check? Earlier history (commit `81953ca`) emitted both START and END entries; commit `a47c432` dropped the START output check because the input `START` entry already pins the pre-run boundary, so a separate output START assertion was redundant. Finalize still only writes END; verify still only checks END.

## TICK_AWARE

**Contract:** the output's post-state at every tick that is *declared* in `entries` matches; *and* every tick where the post-state actually changed must be declared.

- Finalize: one entry per changed tick (relative to `firstTick`), at `END_OF_TICK`.
- Verify (`verifyTickAware`):
  - For each non-sentinel entry, asserts the condition against `postState[tick]`.
  - Computes `changeTicks` by scanning `postState[t] != postState[t-1]` (with `post(-1) := initialSnapshot`).
  - Emits an `unexpected change` failure for any change tick not covered by an entry.
  - START/END sentinels reuse the SIMPLE-style boundary check when present.
- **What it ignores:** sub-tick ordering, the *path* taken within a tick (block events, scheduled ticks). It only sees the END_OF_TICK sample.

## UPDATE_AWARE

**Contract:** every recorded change at the output position matches a declared entry at the same `SimTime` (tick *and* phase *and* order), and the condition holds at that exact `SimTime`.

- Finalize: same as TICK_AWARE — one entry per changed tick at `END_OF_TICK`. (The finalizer currently does not emit sub-tick phase entries; sub-tick ordering must be authored by hand if needed.)
- Verify (`verifyUpdateAware`):
  - Pulls `view.changesAt(pos)` (every recorded `BlockStateChange` at that pos).
  - For each non-sentinel entry: requires `simTime in recordedSimTimes`, then asserts the condition against `view.stateAt(pos, simTime)`.
  - Emits `missing change` if the entry's `SimTime` had no recorded change.
  - Emits `unexpected change` for any recorded change whose `SimTime` is not in the entry set.
- **Why it's strictest:** SimTime equality compares `tick`, `phase`, *and* `order`. A change that drifts by one phase (e.g. `BLOCK_EVENTS` vs `END_OF_TICK`) fails verification even if the END_OF_TICK sample is identical.

## Picking a mode

- SIMPLE — "does this circuit eventually settle to X?" Tolerates timing drift, glitches, route changes.
- TICK_AWARE — "does this circuit transition on the right ticks?" Catches extra/missing pulses; tolerates intra-tick reordering.
- UPDATE_AWARE — "does this circuit fire updates in the same order?" Catches subtle redstone-ordering regressions; brittle to MC-version changes that reorder neighbor updates.

## Related

- `docs/runner/output-verifier-post-run.md` — why verification was lifted out of `SpecRunner`.
