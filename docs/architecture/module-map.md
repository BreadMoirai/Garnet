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

## `block/` — two world-anchor blocks + their BE

- `RedstoneSpecRecorderBlock.kt` — places a recorder; records world state into a `StateRecording` and emits `.spec.kts` via `RecordingDslEmitter`.
- `RedstoneSpecRunnerBlock.kt` — places a runner; loads the `.spec.kts` and calls `runRedstoneSpec`.
- `SpecBlockEntity.kt` — the BE used by both blocks. Holds the active `RedstoneSpec`, the bounding region, and the spec-id reference. The `originPos` of the BE is the trust anchor for every C2S payload (see persistence/network-payload-contract.md).
- `SpecBlockKind.kt` — the enum used inside the BE to remember which block kind owns it.

## `dsl/` — the spec DSL (the spec *is* the lambda)

`RedstoneSpec` no longer holds a flat entry list. Instead it holds a `SpecRun.() -> Unit` lambda — the user's declared inputs and assertions. Construction is via `redstoneSpec(id) { … }`.

- `RedstoneSpec.kt` — top-level value: id, `Vec3i` bounds, lifespan, optional structure id, strict flag, and the `block: SpecRun.() -> Unit` lambda.
- `SpecRun.kt` — execution context for one `block` invocation. `input(x,y,z) { … }` and `output(x,y,z) { … }` register tick-keyed callbacks into sorted maps; `SpecFailure` records assertion failures.
- `InputScope.kt` / `OutputScope.kt` — DSL receiver types; schedule actions / assertions against `SimTime` keys.
- `ConditionScope.kt` — condition leaf builders (`powered`, `prop`, `range`, `block`, `containerHas`) and combinators (`all` / `any` / `not`).
- `StateCondition.kt` — recursive predicate AST; `ConditionEvaluator.kt` evaluates it against a live `BlockState`.
- `SpecTime.kt` — `SimTime(tick, phase, order)` with `START` / `END` sentinels; `Phase` enum. Order within a phase is load-bearing for sequencing multiple actions.

The `dsl/` package depends on nothing else in the project and is the foundation everything else builds on.

## `runner/` — the record → emit → run → verify pipeline

- `StateRecorder.kt` — captures per-phase block-state snapshots while the recorder is active.
- `StateRecording.kt` / `StateRecordingStorage.kt` / `StateRecordingView.kt` / `SpecSnapshot.kt` — the recorded data in flight + persisted form.
- `RecordingDslEmitter.kt` — derives `.spec.kts` source text from a `StateRecording`. Walks the recording, diffs adjacent snapshots, emits `input(…) { … }` and `output(…) { … }` blocks.
- `runRedstoneSpec.kt` — top-level suspend fun; snapshots the region, restores it, invokes `spec.block` once to populate callbacks, drives the tick loop, fires assertions inline, and throws `AssertionError` on failure.
- `PlayerInteractionDispatch.kt` — `tryApplyAsPlayerInteraction`: buttons go through `ButtonBlock.press` so scheduled-tick paths fire correctly; other blocks fall through to `setBlock`. See [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md).

## `persistence/` — disk I/O

- `SpecPersistence.kt` — `.spec.kts` save/load via `RecordingDslEmitter` / `KtsSpecLoader` + emitter-flow auto-save. JSON is **not** used on disk.
- `StructurePersistence.kt` — compressed-NBT structure template (the recorded world snapshot). Takes `Vec3i` size; positions are origin-local.
- `KtsSpecLoader.kt` — evaluates a `.spec.kts` source via `BasicJvmScriptingHost` and unwraps the result as `RedstoneSpec`. Pins the script's `baseClassLoader` to `RedstoneSpec`'s loader so the cast works under Fabric's mod ("knot") classloader. See [persistence/kts-script-host.md](../persistence/kts-script-host.md).
- `SpecScript.kt` — `@KotlinScript` type + `ScriptCompilationConfiguration` (pre-imports the DSL package).

Both are used by the recorder block and the runner block.

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
dsl/  ←  runner/  ←  persistence/  ←  block/  ←  network/  ←  client/
```

`dsl/` is the leaf — no other package may depend up the chain. `runner/` consumes only `dsl/`. `persistence/` consumes `dsl/` + `runner/` (for `RecordingDslEmitter`). The client never reaches into `runner/` directly; it goes through `network/Packets.kt`.

## Where to start reading

- *"How does a spec get from the world onto disk?"* → start at `block/RedstoneSpecRecorderBlock.kt`, then `runner/StateRecorder.kt` → `runner/RecordingDslEmitter.kt` → `persistence/SpecPersistence.kt`. See also [recording-pipeline.md](recording-pipeline.md).
- *"How is a spec replayed and verified?"* → `block/RedstoneSpecRunnerBlock.kt` → `runner/runRedstoneSpec.kt`; the spec's `block` lambda fires inputs and asserts outputs inline. See [runner/engine-driven-verification.md](../runner/engine-driven-verification.md).
- *"Why is the GUI structured this way?"* → the legacy `RecorderScreen`/`RunnerScreen`/`ProjectScreen` were hard-cut in favor of a full-window Compose dock; start at [ui/dock-framework.md](../ui/dock-framework.md) and [ui/dock-input-routing.md](../ui/dock-input-routing.md).
