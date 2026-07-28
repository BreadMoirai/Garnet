---
title: Spec on-disk format
tags: [storage, scripting]
summary: .spec.kts files in the world directory; JSON is network-only; structure NBT remains in .nbt; standalone .nbt structures also live directly in the Explorer. Unsaved structure edits are captured to adjacent .nbt.unsaved dirty buffers on world-save.
---

# Spec on-disk format

Specs live as **`.spec.kts` Kotlin script files** in
`<world>/garnet/<id>.spec.kts`.

Each file evaluates (via `KtsSpecLoader`) to a `GarnetSpec`. The standard
form is:

```kotlin
garnetSpec("door_latch") {
    bounds(5, 4, 5)
    lifespan = 40
    structure = "garnet:door_latch"

    input(2, 0, 2, label = "lever", color = 0xFFFF4444.toInt()) {
        atStart { powered() }
        at(tick = 10) { not { powered() } }
    }
    output(4, 0, 4, label = "lamp", color = -1) {
        at(tick = 11) { lit() }
    }
}
```

## What's NOT on disk

- **JSON.** No `.json` spec files. JSON survives in the codebase as
  `SpecJsonCodec`, used **only** for C2S/S2C network payloads. There is no
  on-disk JSON read or write path.

## Companion files

- **`<id>.nbt`** — compressed-NBT structure file (the circuit under test).
  Saved/loaded by `StructurePersistence`. Independent of the `.spec.kts`
  file; the spec references it by `structure = "<id>"`.
- **`<name>.nbt.unsaved`** — dirty-buffer sidecar written adjacent to a standalone `<name>.nbt`
  whenever Minecraft saves the world (`ServerLifecycleEvents.BEFORE_SAVE`) and the placed
  region's auto-fit capture differs from the committed `.nbt` (`StructurePersistence.flushUnsavedSidecar`).
  Placing a structure loads this sidecar when present (resuming unsaved edits and reporting
  `hasUnsaved = true`); **Save Structure** writes the committed `.nbt` and deletes the sidecar;
  **Discard** deletes it and re-places the committed version. The Explorer hides `*.nbt.unsaved`
  files (`scanFolder`) and shows a dirty dot on the owning `.nbt`.

`.nbt` files are also standalone Explorer citizens, not just spec sidecars: they can be
placed/captured/created directly from the tree without an owning spec. See
[architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files).

## Editor save flow

When the in-game editor saves, `SpecPersistence.save` calls
`KtsSpecEmitter.emit(spec)` (KotlinPoet-generated text) and writes the
result as `<id>.spec.kts`. Reload reads the file via `KtsSpecLoader`.

## Migration

Pre-redesign worlds may have `.json` spec files. The redesign does **not**
read these — they're left alone. Re-record or re-author such specs as
`.spec.kts`. (Decision: scoping a one-shot migration tool is out of scope
for this redesign.)

See: [`.spec.kts script host`](kts-script-host.md) for the loader internals.
