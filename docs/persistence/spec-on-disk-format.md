---
title: Spec on-disk format
tags: [storage, serialization, codec, json, nbt]
summary: How a spec is split between a JSON spec file and a compressed NBT structure file, and the auto-save pipeline that writes them.
---

# Spec on-disk format

A "saved spec" is actually two sibling files in `<world>/<SharedSettings.specSaveDir>/`:

| File | Format | Contents |
|---|---|---|
| `<id>.json` | pretty-printed JSON via `RedstoneSpec.CODEC` + `JsonOps.INSTANCE` | The data model: bounds, mode, lifespan, inputs, outputs, breakpoints, auto specs |
| `<id>.nbt` | gzipped NBT (`NbtIo.writeCompressed`) of a `StructureTemplate` | The block snapshot inside `bounds`, captured via `template.fillFromWorld` |

The split is deliberate: the JSON is human-readable and version-controllable (you can hand-edit a spec); the NBT is the world data that vanilla `StructureTemplate` already knows how to round-trip across MC versions. There is no schema version field on either — the codec's `optionalFieldOf` defaults are the migration story.

## Why structure id can differ from spec id

`RedstoneSpec.structure: String?` lets one structure file back many specs (or share a circuit between specs at different difficulty modes). `NetworkRegistry` resolves the actual file with `spec.structure ?: spec.id` everywhere it touches structure persistence. When the spec id is renamed via `SetSpecIdC2SPayload`, the handler co-renames the structure only if it was implicit (`structure == null` or `structure == oldId`) — explicit structure references are preserved.

See `/mnt/h/Repo/RedstoneSpecs/src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt` (`SetSpecIdC2SPayload` handler).

## Auto-save: emitter flow, not call sites

`SpecBlockEntity` does not call `SpecPersistence.save` on every mutation. Instead it wraps the spec in a `RedstoneSpecEmitter` (a `StateFlow`-like emitter generated from `@AutoEmit`) and starts one `collectorJob` per BE that drains the flow on `Dispatchers.IO`:

```kotlin
collectorJob = coroutineScope.launch {
    e.drop(1).collect { spec ->
        withContext(Dispatchers.IO) { SpecPersistence.save(saveDir, spec) }
    }
}
```

Three non-obvious consequences:

1. `drop(1)` skips the initial value — a `setSpec` immediately following emitter construction would otherwise double-write. The first save is performed eagerly by `triggerSave` so a brand-new spec hits disk before the collector starts.
2. Mutations through the granular setters (`setSpecId`, `setMode`, `setLifespan`, `setStructure`, `addOrUpdateEntry`, `removeEntry`) update emitter fields directly; the emitter coalesces them into one `RedstoneSpec` value per emission. Saves are not synchronous with the network handler.
3. `loadAdditional` (BE chunk-load) cancels any prior collector and rebuilds the emitter. Without that, a freshly loaded chunk would carry a stale collector pointing at a defunct emitter.

## Structure save is gated by run, not by edit

Structure NBT is written only on `RunSpecC2SPayload` (auto-save before run) — never on entry edits. Reload from disk is gated by `StructurePersistence.hasNonAirBlocks`: if the bounds region already contains blocks, the server defers and sends `OverwritePromptS2CPayload` to ask the client; only after `OverwriteDecisionC2SPayload(overwrite=true)` does the server `clearBounds` then `placeInWorld`. This is the only round-trip handshake in the persistence layer.

## What is NOT persisted

- `lastTestResult` lives on `SpecBlockEntity` NBT (chunk save) but not in the JSON file — it is run state, not spec data.
- `StateRecorder` (the recording-in-progress) is purely transient and lives in a static activation registry on `StateRecorder`; it is not serialised. Stopping the world mid-recording loses it.
- BE-side coordinates (`blockPos`) are not persisted into the spec file; see `spec-block-entity-anchoring.md` for why.
