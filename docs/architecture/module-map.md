---
title: Module map
tags: [modules, layout, dependencies, entry-points]
summary: Tour of the source tree — which package owns what, the dependency direction, and where to start reading for a given concern.
---

# Module map

A guided tour of `src/main/kotlin/com/breadmoirai/redstonespecs/`. Use this as the entry point when you don't yet know which file to open.

## Top level

- `Redstonespecs.kt` — Fabric mod entry, registers blocks/items/network, wires lifecycle hooks.
- `ModRegistries.kt` — registry handles for blocks, items, block-entity types, payload types.

## `block/` — three world-anchor blocks + their BE

- `RedstoneSpecRecorderBlock.kt` — places a recorder; records world state into a `StateRecording`.
- `RedstoneSpecEditorBlock.kt` — places an editor; opens the GUI for editing a saved spec.
- `RedstoneSpecRunnerBlock.kt` — places a runner; replays a spec and verifies it.
- `SpecBlockEntity.kt` — the BE used by all three. Holds the active `RedstoneSpec`, the bounding region, and the spec-id reference. The `originPos` of the BE is the trust anchor for every C2S payload (see persistence/network-payload-contract.md).
- `SpecBlockKind.kt` — the enum used inside the BE to remember which block kind owns it.

## `data/` — the spec data model (pure values, no logic)

- `RedstoneSpec.kt` — top-level container: id, `Vec3i` size bounds, lifespan, optional structure id, flat `entries: List<SpecEntry>`.
- `SpecEntry.kt` — single data class: `(pos, label, color, kind, time, condition)` where `kind` is `EntryKind.INPUT | OUTPUT`. Multiple entries at the same `(pos, kind)` represent a multi-step sequence.
- `EntryKind.kt` — `INPUT` / `OUTPUT` discriminator.
- `SimTime.kt` — `(tick, phase)` with the `START` / `END` sentinels. Phase enum order is load-bearing for ordering.
- `StateCondition.kt` — recursive predicate AST over block states.
- `TestResult.kt` — runner output: pass/fail + per-entry verdicts.

### `data/dsl/` — the authoring DSL

- `SpecDsl.kt` — top-level `redstoneSpec(id) { ... }` builder; returns `RedstoneSpec`.
- `EntryDsl.kt` — `input(...)` / `output(...)` blocks, `at(tick)` / `atStart` time anchors.
- `ConditionDsl.kt` — condition leaves (`powered`, `lit`, `prop`, `intProp`, `range`, `block`, `containerHas`) and combinators (`all` / `any` / `not`).

### `data/serial/` — JSON codec, .spec.kts loader & emitter

- `SpecJsonCodec.kt` — DFU codecs for `RedstoneSpec` / `SpecEntry`. Used **only** by network payloads.
- `SpecScript.kt` — `@KotlinScript` type + `ScriptCompilationConfiguration` (pre-imports the DSL package).
- `KtsSpecLoader.kt` — evaluates a `.spec.kts` source via `BasicJvmScriptingHost` and unwraps the result as `RedstoneSpec`. Pins the script's `baseClassLoader` to `RedstoneSpec`'s loader so the cast works under Fabric's mod ("knot") classloader. See [persistence/kts-script-host.md](../persistence/kts-script-host.md).
- `KtsSpecEmitter.kt` — `RedstoneSpec` → `.spec.kts` source via KotlinPoet. Groups entries by `(pos, kind, label, color)` for readability; sorts by `time` for deterministic output.

The `data/` package depends on nothing else in the project and is the foundation everything else builds on.

## `runner/` — the recording → finalize → run → verify pipeline

- `StateRecorder.kt` — captures per-phase block-state snapshots while the recorder is active.
- `StateRecording.kt` / `StateRecordingStorage.kt` / `StateRecordingView.kt` / `SpecSnapshot.kt` — the recorded data in flight + persisted form.
- `RecordingFinalizer.kt` — derives a `RedstoneSpec` (flat `SpecEntry` rows for inputs and outputs) from a `StateRecording`. Reads the `(pos, kind, label, color)` markers off the base spec and emits one entry per recorded change-tick.
- `SpecRunner.kt` — single-run engine: applies inputs at scheduled times, samples outputs, returns per-phase progress. Includes `tryApplyAsPlayerInteraction` for player-style input dispatch (see runner/player-interaction-dispatch.md).
- `SpecRunnerCoordinator.kt` — singleton that owns active runners, dispatches `onPhase`, and hands off to `EngineDrivenRun`.
- `EngineDrivenRun.kt` — per-run object; drives `SpecRunner` from inside a Kotest test body and holds the diagnostic `StateRecording`.
- `ConditionEvaluator.kt` — evaluates `StateCondition` against a sampled block state.

## `persistence/` — disk I/O

- `SpecPersistence.kt` — `.spec.kts` save/load via `KtsSpecEmitter` / `KtsSpecLoader` + emitter-flow auto-save. JSON is **not** used on disk.
- `StructurePersistence.kt` — compressed-NBT structure template (the recorded world snapshot). Takes `Vec3i` size; positions are origin-local.

Both are used by the recorder block and the editor screen.

## `network/` — wire protocol

- `Packets.kt` — every C2S/S2C payload as a Kotlin data class with a stream codec. All transforms are server-authoritative; clients only ever propose. Server validation pivots on the `originPos` → BE lookup (see persistence/network-payload-contract.md).
- `NetworkRegistry.kt` — payload-type registration.

## `event/`, `config/`, `item/`

- `event/SubTickPhaseEvents.kt` — Phase-emitter wiring (level tick → recorder/runner `onPhase`).
- `config/SharedSettings.kt` — config loaded by both client and server (YACL on the client).
- `item/SpecMarkerTool.kt` — the in-world bounds-marking tool; `UndoStack.kt` is its undo history.

## Client and tests

- `src/client/kotlin/...` — every screen widget and the client-side payload sender. See [ui/INDEX.md](../ui/INDEX.md).
- `src/test/kotlin/...` — pure JUnit (DSL, JSON codec, kts loader/emitter, persistence). See [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md).
- `src/gametest/kotlin/...` — server-side `@GameTest` flows (`runGameTest`). See [gametest/INDEX.md](../gametest/INDEX.md).
- `src/clientTest/kotlin/...` — client-side `FabricClientGameTest` flows (`runClientTest`). See [gametest/INDEX.md](../gametest/INDEX.md).

## Dependency direction

```
data/  ←  runner/  ←  block/  ←  network/  ←  client/
   ↑        ↑          ↑
   └─ persistence/ ────┘
```

`data/` is the leaf — no other package may depend up the chain. `runner/` consumes only `data/` + `persistence/`. The client never reaches into `runner/` directly; it goes through `network/Packets.kt`.

## Where to start reading

- *"How does a spec get from the world onto disk?"* → start at `block/RedstoneSpecRecorderBlock.kt`, then `runner/StateRecorder.kt` → `runner/RecordingFinalizer.kt` → `persistence/SpecPersistence.kt`. See also [recording-pipeline.md](recording-pipeline.md).
- *"How is a spec replayed and verified?"* → `block/RedstoneSpecRunnerBlock.kt` → `runner/SpecRunnerCoordinator.kt` → `runner/EngineDrivenRun.kt` → `runner/SpecRunner.kt`; assertions in the test body via `testing/RedstoneSpecAssertions.kt`. See [runner/engine-driven-verification.md](../runner/engine-driven-verification.md).
- *"Why is the GUI structured this way?"* → `src/client/kotlin/.../screen/SpecEditorScreen.kt` plus [ui/dropdown-host-popup-stratum.md](../ui/dropdown-host-popup-stratum.md).
