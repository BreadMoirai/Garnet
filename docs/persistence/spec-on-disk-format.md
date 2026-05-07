---
title: Spec on-disk format
tags: [storage, scripting]
summary: .spec.kts files in the world directory; JSON is network-only; structure NBT remains in .nbt.
---

# Spec on-disk format

Specs live as **`.spec.kts` Kotlin script files** in
`<world>/redstonespecs/<id>.spec.kts`.

Each file evaluates (via `KtsSpecLoader`) to a `RedstoneSpec`. The standard
form is:

```kotlin
redstoneSpec("door_latch") {
    bounds(5, 4, 5)
    lifespan = 40
    structure = "redstonespecs:door_latch"

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
