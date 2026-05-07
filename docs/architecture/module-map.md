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

- `RedstoneSpec.kt` — top-level container: id, mode, bounds, list of entries.
- `SpecEntry.kt` — sealed class: `InputSpec` / `OutputSpec` / `BreakpointSpec` / `AutoSpec`. Polymorphic over a string tag for codec dispatch.
- `SpecMode.kt` — `SIMPLE` / `TICK_AWARE` / `UPDATE_AWARE`. The single knob that drives finalization and verification semantics (see runner/spec-modes.md).
- `SimTime.kt` — `(tick, phase)` with the `START` / `END` sentinels. Phase enum order is load-bearing for ordering.
- `StateCondition.kt` — recursive predicate AST over block states, encoded with a lazy codec.
- `TestResult.kt` — runner output: pass/fail + per-entry verdicts.

This package depends on nothing else in the project and is the foundation everything else builds on.

## `runner/` — the recording → finalize → run → verify pipeline

- `StateRecorder.kt` — captures per-phase block-state snapshots while the recorder is active.
- `StateRecording.kt` / `StateRecordingStorage.kt` / `StateRecordingView.kt` / `SpecSnapshot.kt` — the recorded data in flight + persisted form.
- `RecordingFinalizer.kt` — derives a `RedstoneSpec` (input + output entries) from a `StateRecording`, mode-aware.
- `SpecRunner.kt` — single-run engine: applies inputs at scheduled times, samples outputs, returns per-phase progress. Includes `tryApplyAsPlayerInteraction` for player-style input dispatch (see runner/player-interaction-dispatch.md).
- `SpecRunnerCoordinator.kt` — singleton that owns active runners, dispatches `onPhase`, and triggers post-run verification.
- `ConditionEvaluator.kt` — evaluates `StateCondition` against a sampled block state.
- `OutputVerifier.kt` — post-run validator (see runner/output-verifier-post-run.md).

## `persistence/` — disk I/O

- `SpecPersistence.kt` — JSON codec for `RedstoneSpec` + emitter-flow auto-save.
- `StructurePersistence.kt` — compressed-NBT structure template (the recorded world snapshot).

Both are used by the recorder block and the editor screen.

## `network/` — wire protocol

- `Packets.kt` — every C2S/S2C payload as a Kotlin data class with a stream codec. All transforms are server-authoritative; clients only ever propose. Server validation pivots on the `originPos` → BE lookup (see persistence/network-payload-contract.md).
- `NetworkRegistry.kt` — payload-type registration.
- `BoundsNudge.kt` — small helper for bounds-edit payloads.

## `event/`, `config/`, `item/`

- `event/SubTickPhaseEvents.kt` — Phase-emitter wiring (level tick → recorder/runner `onPhase`).
- `config/SharedSettings.kt` — config loaded by both client and server (YACL on the client).
- `item/SpecMarkerTool.kt` — the in-world bounds-marking tool; `UndoStack.kt` is its undo history.

## Client and tests

- `src/client/kotlin/...` — every screen widget and the client-side payload sender. See [ui/INDEX.md](../ui/INDEX.md).
- `src/test/kotlin/...` — pure JUnit (currently `RecordingFinalizerTest` and friends). See [gametest/unit-vs-gametest-split.md](../gametest/unit-vs-gametest-split.md).
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
- *"How is a spec replayed?"* → `block/RedstoneSpecRunnerBlock.kt` → `runner/SpecRunnerCoordinator.kt` → `runner/SpecRunner.kt` → `runner/OutputVerifier.kt`.
- *"Why is the GUI structured this way?"* → `src/client/kotlin/.../screen/SpecEditorScreen.kt` plus [ui/dropdown-host-popup-stratum.md](../ui/dropdown-host-popup-stratum.md).
