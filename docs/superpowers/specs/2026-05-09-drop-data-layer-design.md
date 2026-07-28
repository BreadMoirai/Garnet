---
title: Drop the data layer; DSL becomes the spec
tags: [refactor, dsl, runner, persistence, scope-reduction]
summary: Replace the GarnetSpec/SpecEntry data model with a deferred-closure DSL; delete the in-world editor; keep slim recorder/runner block UIs.
date: 2026-05-09
---

# Drop the data layer; DSL becomes the spec

## Goal

Eliminate the intermediate `data/` package (`GarnetSpec`, `SpecEntry`, JSON codec, etc.) and the in-world spec editor. The `.spec.kts` DSL becomes the canonical spec form: it directly drives inputs into the world and verifies outputs against the live `StateRecording`. Two block kinds remain — recorder (captures world → emits DSL) and runner (loads DSL, places structure, runs, verifies) — each with a slim config screen.

## Why

The data layer (`GarnetSpec` + `SpecEntry` flat list + JSON codec + emitter/loader round-trip + the in-world editor that mutates it) is overhead the project no longer benefits from. Specs are authored or auto-emitted as `.spec.kts`; tests and the in-world Runner block both consume them; the editor GUI's structural editing is unused in practice. By collapsing the data model into the DSL itself we remove ~5 files of data classes, the JSON codec, `RecordingFinalizer`, `KtsSpecEmitter`, `SpecRunner`/`Coordinator`/`EngineDrivenRun`, the entire editor screen and its widget tree, and the half-dozen edit packets in `network/`.

## Non-goals

- Changing the `StateRecording` format or its on-disk representation.
- Changing the `StateRecorder` capture mechanism or the `SpecSnapshot` restore path.
- Changing the marker tool, bounds renderer, or HUD overlay.
- Multi-spec orchestration (the editor's "many specs at once" use case disappears with the editor).

## Surface (what users write)

```kotlin
// foo.spec.kts
garnetSpec(
    id        = "foo",
    bounds    = Vec3i(5, 5, 5),
    lifespan  = 20,
    structure = "garnet:foo",
    strict    = true,
) {
    input(0, 0, 0, label = "A") {
        at(0) { setPowered(true) }
        at(5) { setPowered(false) }
    }

    output(3, 1, 0, label = "X") {
        at(2) { powered() }
        at(7) { not { powered() } }
    }
}
```

Configuration (id, bounds, lifespan, structure id, strict flag) lives on the `garnetSpec(...)` argument list — not inside the lambda. The lambda's only job is to register tick-keyed input applications and tick-keyed output assertions.

**Inputs are direct setters.** `setPowered(true)` / `setLit(true)` / `setProp("facing", "east")` / `setBlock(state)` produce a target `BlockState` and schedule its application at the matching `SimTime`. No condition→state inversion.

**Outputs are predicates.** `at(tick) { <ConditionScope> }` builds a `StateCondition` AST that is evaluated against the recording at the matching `SimTime`. The AST is kept (transient, never persisted) so failure messages can use `describeCondition` / `describeStateForCondition`.

## Core types

```kotlin
// dsl/GarnetSpec.kt — the only surviving "data" class
data class GarnetSpec(
    val id: String,
    val bounds: Vec3i,
    val lifespan: Int,
    val structure: String?,
    val strict: Boolean = false,
    val block: SpecRun.() -> Unit,
)

// dsl/SpecRun.kt — execution context, populated when block runs once
class SpecRun internal constructor(
    private val level: ServerLevel,
    private val origin: BlockPos,
    private val recording: StateRecording,
) {
    internal val inputActions = TreeMap<SimTime, MutableList<() -> Unit>>()
    internal val assertions   = TreeMap<SimTime, MutableList<() -> Unit>>()
    internal val outputPositions = mutableMapOf<BlockPos, MutableSet<Int>>() // pos -> declared ticks

    fun input(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: InputScope.() -> Unit)
    fun output(x: Int, y: Int, z: Int, label: String = "", color: Int = -1, block: OutputScope.() -> Unit)
}
```

`InputScope.at(tick) { setPowered(true) }` resolves to a `BlockState` immediately and registers a `() -> Unit` callback in `inputActions[SimTime(tick, START_OF_TICK)]`. The callback applies the state via the existing player-interaction dispatch helper (`tryApplyAsPlayerInteraction`-equivalent, relocated from `SpecRunner`).

`OutputScope.at(tick) { powered() }` builds a `StateCondition`, registers an assertion in `assertions[SimTime(tick, END_OF_TICK)]`, and adds `tick` to `outputPositions[pos]`. The assertion closes over the `recording` reference; when fired, it calls `view.stateAt(pos, time)` and `evaluateConditionOnState`.

## Execution loop

```kotlin
suspend fun runGarnetSpec(level: ServerLevel, origin: BlockPos, spec: GarnetSpec): StateRecording {
    val snapshot = SpecSnapshot.capture(level, origin, spec.bounds)
    val recorder = StateRecorder.forSpec(UUID.randomUUID(), origin, spec.bounds)
    val recording: StateRecording

    try {
        recorder.start(level, origin, spec.bounds)
        StateRecorder.activate(recorder)
        snapshot.restore(level)

        val run = SpecRun(level, origin, recorder.liveRecording())
        spec.block(run)   // single registration pass

        val failures = mutableListOf<String>()
        for (tick in 0 until spec.lifespan) {
            run.inputActions[SimTime(tick, START_OF_TICK)]?.forEach { it() }
            awaitTickEnd()
            run.assertions[SimTime(tick, END_OF_TICK)]?.forEach { it() }
        }

        recording = recorder.toRecording()

        if (spec.strict) {
            scanForUnexpectedChanges(recording, run.outputPositions, spec.lifespan, failures)
        }

        if (failures.isNotEmpty()) throw AssertionError("assertOutputsMatch failed:\n" + failures.joinToString("\n"))
    } finally {
        StateRecorder.deactivate(recorder)
        snapshot.restore(level)
    }
    return recording
}
```

`SpecRunner`, `SpecRunnerCoordinator`, and `EngineDrivenRun` collapse into this single function. The coordinator existed to multiplex many runners against a single tick-event stream — without the editor's "many specs at once" flow, there is at most one in-flight run per server, driven by the suspend function above.

The lambda runs **once**, before the tick loop starts, with the level state already restored from the snapshot. Input/output registration must be side-effect-free against the level; the contract is enforced by convention (the DSL methods only mutate scheduler state).

## Strict mode

When `strict = true`, after the loop the engine walks each declared output position and flags any tick where the post-state changed but the user did not declare an `at(tick)` for that position. This is today's "unexpected change" behavior, opt-in instead of always-on.

## Recorder block: world → DSL text

`RecordingDslEmitter` replaces `RecordingFinalizer` (recording → `GarnetSpec`) plus `KtsSpecEmitter` (`GarnetSpec` → text). It walks the `StateRecording` and emits `.spec.kts` text in one pass:

1. Read `(pos, kind, label, color)` markers off the BE state at finalize time.
2. For each input position, find the change-ticks in the recording and emit `at(tick) { setProp(...) }` lines (or `setPowered`/`setLit` when applicable).
3. For each output position, find the change-ticks and emit `at(tick) { <derived predicate> }` lines.
4. Wrap with the `garnetSpec(id = …, bounds = …, lifespan = …, structure = …) { … }` shell, with meta values pulled from BE config.

The condition-derivation rules used by `RecordingFinalizer` carry over directly (e.g. `RedstoneTorch.LIT` → `lit()`, `DiodeBlock.POWERED` → `powered()`, generic property → `prop("name", "value")`).

## Block UI

Two slim screens, one per block kind. Both are config screens, not editors.

**RecorderScreen** (right-click recorder block):
- Read-only: bounds (set by marker tool), recording state.
- Editable text fields: `specId`, `outPath` (default `garnet/<id>.spec.kts`), `structureId` (default `garnet:<id>`).
- Buttons: `Start recording`, `Stop & emit`, `Discard`.

**RunnerScreen** (right-click runner block):
- Spec picker: dropdown populated from a server-side scan of `<world>/garnet/*.spec.kts`. Server pushes the list when the screen opens; client selects.
- Read-only after-load: id, bounds, lifespan, structure (read directly off the loaded `GarnetSpec` properties — these are plain fields, not derived from a list of entries).
- Buttons: `Place structure`, `Run`, `Restore snapshot`. Place and Run are deliberately separate (place → inspect → run is a useful debug flow).
- Result area: pass/fail + first failure line. Full failure list goes to server log; `RunnerStatus` S2C carries the summary.

## Network surface

| Direction | Payload | Purpose |
|---|---|---|
| C2S | `SetRecorderConfig(originPos, specId, outPath, structureId)` | Recorder field edits |
| C2S | `RecorderCommand(originPos, Start \| Stop \| Discard)` | Recorder buttons |
| C2S | `SetRunnerConfig(originPos, specPath)` | Runner load-spec |
| C2S | `RunnerCommand(originPos, PlaceStructure \| Run \| Restore)` | Runner buttons |
| S2C | `OpenRecorderScreen(originPos, snapshot)` | Server pushes BE state on right-click |
| S2C | `OpenRunnerScreen(originPos, snapshot, specList)` | Server pushes BE state + scanned spec dir |
| S2C | `RunnerStatus(originPos, state, summary)` | Result/state push during/after run |

All `originPos`-bearing payloads validate through the existing BE-lookup pivot (per `persistence/network-payload-contract.md`). Spec contents never flow over the wire — server reads/writes `.spec.kts` files; clients only see meta and result summaries. `SpecJsonCodec` is deleted.

## Package layout

```
com.breadmoirai.garnet/
├── garnet.kt
├── ModRegistries.kt
│
├── dsl/                              # NEW (the DSL is the spec)
│   ├── GarnetSpec.kt               # data class { id, bounds, lifespan, structure, strict, block }
│   ├── SpecRun.kt                    # execution context with input()/output() schedulers
│   ├── InputScope.kt                 # setPowered/setLit/setProp/setBlock + at(tick)
│   ├── OutputScope.kt                # at(tick) { <ConditionScope> }
│   ├── ConditionDsl.kt               # MOVED from data/dsl/ — predicates unchanged
│   ├── StateCondition.kt             # MOVED from data/
│   ├── ConditionEvaluator.kt         # MOVED from runner/
│   ├── SimTime.kt                    # MOVED from data/
│   └── Phase.kt                      # MOVED from data/
│
├── runner/                           # ENGINE only
│   ├── runGarnetSpec.kt            # the loop — replaces SpecRunner + Coordinator + EngineDrivenRun
│   ├── StateRecorder.kt              # unchanged
│   ├── StateRecording.kt             # unchanged
│   ├── StateRecordingView.kt         # unchanged
│   ├── SpecSnapshot.kt               # unchanged
│   └── RecordingDslEmitter.kt        # NEW — replaces RecordingFinalizer + KtsSpecEmitter
│
├── persistence/
│   ├── SpecPersistence.kt            # save/load .spec.kts (script returns GarnetSpec)
│   ├── SpecScript.kt                 # MOVED from data/serial/
│   ├── KtsSpecLoader.kt              # MOVED from data/serial/, return type now new GarnetSpec
│   ├── StructurePersistence.kt       # unchanged
│   └── SpecDirectoryScan.kt          # NEW — list .spec.kts files for runner picker
│
├── block/
│   ├── GarnetRecorderBlock.kt
│   ├── GarnetRunnerBlock.kt
│   └── SpecBlockEntity.kt            # holds {specPath, structureId, bounds, label} — no editor branch
│
├── network/
│   └── Packets.kt                    # only the 7 payloads from above
│
├── event/, config/, item/            # unchanged
│
└── client/
    ├── screen/
    │   ├── RecorderScreen.kt         # NEW
    │   └── RunnerScreen.kt           # NEW
    └── render/, hud/                 # unchanged
```

**Dependency direction:**

```
dsl/  ←  runner/  ←  persistence/  ←  block/  ←  network/  ←  client/
```

`dsl/` is the new leaf (was `data/`). The rest of the chain is structurally unchanged.

## What gets deleted

**Files:**
- `data/GarnetSpec.kt`, `SpecEntry.kt`, `EntryKind.kt`, `TestResult.kt`
- `data/dsl/SpecDsl.kt`, `EntryDsl.kt` (old "build a `GarnetSpec`" form)
- `data/serial/SpecJsonCodec.kt`, `KtsSpecEmitter.kt`
- `runner/RecordingFinalizer.kt`, `SpecRunner.kt`, `SpecRunnerCoordinator.kt`, `EngineDrivenRun.kt`
- `block/GarnetEditorBlock.kt`, `block/SpecBlockKind.kt`
- The entire `client/screen/SpecEditorScreen.kt` and every widget under it that exists only for the editor (per-tick row, condition dropdowns, entry-list panel, etc.)
- All edit-related payloads in `network/Packets.kt`

**Whole `data/` package** ends up empty and gets removed.

## Migration phases

Each phase ends with a clean build (`clientClasses classes gametestClasses clientTestClasses testClasses` across all five sourcesets) and all tests green.

**Phase 1 — Carve out `dsl/` package.**
Move `SimTime`, `Phase`, `StateCondition`, `ConditionEvaluator`, `ConditionDsl` into a new `dsl/` package. Update imports project-wide. No behavior change.

**Phase 2 — New imperative DSL alongside the old.**
Add `dsl/GarnetSpec.kt` (new shape with `block` field), `dsl/SpecRun.kt`, `InputScope`, `OutputScope`, `runner/runGarnetSpec.kt`. Old `data/GarnetSpec` and old DSL stay untouched. Add unit tests for the new DSL: scheduler population, ordering, strict mode.

**Phase 3 — Cutover to the new DSL.**
Flip the `runGarnetSpec` testing-runner suspend fn to drive the new engine. Add `RecordingDslEmitter`; recorder block calls it instead of `RecordingFinalizer` + `KtsSpecEmitter`. Runner block invokes `runGarnetSpec` directly (no coordinator). `KtsSpecLoader`'s script type returns the new `GarnetSpec`. After this phase the old code is dead but still compiled.

**Phase 4 — Slim block UI.**
Add `RecorderScreen`, `RunnerScreen`, the 7 new payloads, `SpecDirectoryScan`. Replace `SpecEditorScreen` open-on-rightclick with the kind-specific screens. Old editor screen + packets still present but unreachable.

**Phase 5 — Delete the editor stack.**
Remove `GarnetEditorBlock`, `SpecEditorScreen` and its widget tree, the dead packets in `Packets.kt`, `SpecBlockKind`, the editor branch in `SpecBlockEntity`. Subtractive only.

**Phase 6 — Delete `data/` and the dead engine code.**
Remove the entire `data/` package, `SpecRunner`/`Coordinator`/`EngineDrivenRun`, `RecordingFinalizer`. Final clean state.

## Risk and rollback

- **Phase 3 is the only "flip" step.** If it regresses gametests, revert the cutover commits; old code is still present.
- **Phases 5 and 6 are pure deletions** and can be split per-file if review pressure demands.
- **`KtsSpecLoader` script-type change in Phase 3** is a wire-format change for any existing `.spec.kts` files: the new script must return the new `GarnetSpec` shape. Existing in-tree gametest specs are placeholder stubs (per `gametest/INDEX.md`'s retirement note) so the migration cost is low; any user-authored `.spec.kts` files in saves get a one-time migration note.

## Testing

- Phase 2 adds pure-JVM unit tests under `src/test/` for `SpecRun` (registration, ordering, strict-mode scan).
- Phase 3 reuses the existing gametest harness under `src/gametest/` and `src/clientTest/`; the placeholder stubs get re-authored against the new DSL.
- Failure-message regression tests: confirm `describeCondition` output is preserved for assertion failures.

## Open items deferred to implementation

- **Mid-run recording reads.** Assertion callbacks fire after each `awaitTickEnd()`, but today `StateRecording` is built at `recorder.toRecording()` (finalize time). Phase 2 must expose a live read API on `StateRecorder` (e.g. `recorder.liveView(): StateRecordingView` reading from the active per-phase buffer). Alternative: defer all assertions to a single sweep at end-of-run, identical to today's `assertOutputsMatch`. Recommend the live-view approach so failures surface at the failing tick rather than at end-of-run.
- Exact codec format on the wire for `RunnerStatus` summary text (likely just a `String`).
- Whether `RecordingDslEmitter` emits `setProp("name", "value")` always, or specializes (`setPowered(true)` / `setLit(true)`) where the property is well-known. Recommend specializing; falls back to `setProp` for unknown blocks.
- Whether the runner screen's spec picker auto-refreshes on directory mtime, or only on screen open. Recommend on-open only; users can reopen to refresh.
