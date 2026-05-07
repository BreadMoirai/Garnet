---
title: Writing strict-contract gametest scenarios per SpecMode
tags: [testing, gametest, spec-mode, contracts, recorder, runner]
summary: How RedstonespecsGameTests structures positive and negative scenarios that cover all three SpecModes through the record→finalize→runner pipeline.
---

# Writing strict-contract gametest scenarios per SpecMode

The server-side gametests in
`src/gametest/kotlin/com/breadmoirai/redstonespecs/test/RedstonespecsGameTests.kt`
exercise the *contract* between the recorder, finalizer, and runner:
record a circuit, finalize a spec, transform to a runner block, replay,
and assert `lastTestResult` matches expectations. Every scenario must
work at all relevant `SpecMode` values, because each mode's finalizer
derives different output entries from the same recording.

## The `RecorderScenario` data class

A scenario is a value, not a method. This lets one factory
(`leverLampDirect(mode)`) produce three `@GameTest` methods —
SIMPLE / TICK_AWARE / UPDATE_AWARE — without duplication. Fields:

- `mode` — drives finalizer behavior; affects expected entries.
- `recorderRelPos` — helper-local position of the recorder block.
- `placeBlocks(helper)` — places inputs/outputs in helper-local coords.
- `inputs` / `outputs` — helper-local positions of marker blocks.
- `drive(helper)` — the world mutation during recording (typically
  `helper.useBlock(...)`, which goes through the same player-interaction
  path the runner replays — see *button-press extension point* doc).
- `recordingTicks` — idle ticks between drive and finalize; raise this
  whenever the circuit has scheduled ticks still in flight (button
  depower = 10t, piston cycle = ~6t, comparator loop = hundreds).
- `bounds` — defaults to `RedstoneSpec.DEFAULT_BOUNDS`; widen when an
  output sits outside the default spec volume.
- `expectedOutputs(mode)` — per-output expected entries (parallel to
  `outputs`). When `null`, the runner asserts only that each output
  has at least one entry — useful for circuits with hard-to-predict
  per-tick patterns (e.g. comparator feedback loops).

## Positive case — `runRecorderScenario`

Sequence (via `helper.startSequence().thenExecute { ... }`):

1. Place recorder block + circuit; configure mode + bounds; install
   input/output markers via `applyMarkers`; call `startRecording()`.
2. `thenIdle(2)` so the BE settles, then `drive(helper)`.
3. `thenIdle(scenario.recordingTicks)`, then
   `stopRecordingAndFinalize()`. Assert finalized entries match
   `expectedOutputs(mode)` BEFORE transforming to the runner — this
   catches finalizer regressions independently of replay.
4. `transformTo(EDITOR)` → `transformTo(RUNNER)` →
   `SpecRunnerCoordinator.startRun(be)`.
5. `thenWaitUntil { be.lastTestResult != null }`, then assert all
   checks pass.

Note the editor hop: a recorder cannot transform straight to a runner.
Even unit tests against the pipeline must go through the editor.

## Negative case — `runRecorderScenarioExpectingFailure`

Same flow, but a `mutateSpec` lambda runs *between* the editor hop and
the runner transform. The test then asserts `result.pass == false`
**and** at least one failing check. Mode-specific failure shapes:

- **SIMPLE** — only the END sentinel exists, so flip the END expectation
  (e.g. `lit=true` → `lit=false`).
- **TICK_AWARE** — entries exist at exact `SimTime`s; flip values while
  keeping times intact to trigger a wrong-value check.
- **UPDATE_AWARE** — change the `Phase` of every entry. The verifier
  emits both *missing* (recorded change has no matching entry) and
  *unexpected* (entry has no matching recorded change) diagnostics.

Asserting `pass == false` alone is not enough — also assert at least one
failing check, otherwise an empty-checks bug would silently pass.

## `applyMarkers` convention

Inputs are seeded with a `SimTime.START` condition derived from the
block's *initial* state; outputs are seeded with a placeholder
`SimTime.END` condition. The finalizer rederives the real entries —
the seeded ones exist purely to mark the positions. Labels follow
`in_<blockpath>_<i>` / `out_<blockpath>_<i>`.

## Encoding gotchas to verify in your scenario

- `DiodeBlock.FACING` is the *back* direction. "Comparator pointing
  east" is `FACING=WEST` in code.
- Wall torches: `FACING` is where the torch points, not its anchor.
- Place comparators *before* the wires that feed them so the wires'
  setBlock fires neighbor updates onto live comparators.
- Stone button stays pressed for 10 ticks; wood for 30. Set
  `recordingTicks` long enough for the depower to land in the recording
  or replay timing will diverge.
