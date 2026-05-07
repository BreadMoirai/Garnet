---
title: Spec data-model invariants
tags: [data-model, serialization, sealed-types, invariants]
summary: The non-obvious invariants enforced by the SpecEntry/StateCondition sealed hierarchies and the SimTime sentinel ordering — what the codec cannot catch.
---

# Spec data-model invariants

The sealed types in `data/` look like a plain ADT, but several invariants are enforced outside the codec and would silently round-trip if violated.

## SpecEntry: sealed dispatch with a string tag

`SpecEntry` is a sealed class with four subtypes (`InputSpec`, `OutputSpec`, `BreakpointSpec`, `AutoSpec`). Serialisation uses `Codec.STRING.dispatch("type", ..., ...)` with tags `"input" | "output" | "breakpoint" | "auto"`. Adding a new subtype requires updating *both* lambdas — the encoder side has no exhaustiveness warning because the dispatcher passes the entry through a `when`, but a missing case in the decoder lambda throws at parse time.

Each subtype's `MAP_CODEC` is shared between the JSON file (`RedstoneSpec.CODEC`) and the network wire (`SaveSpecEntryC2SPayload` via `ByteBufCodecs.fromCodec(SpecEntry.CODEC)`), so the same field names appear on disk and on the wire.

## InputSpec requires exactly one START entry

`InputSpec.init` enforces:

```kotlin
require(entries.count { it.first == SimTime.START } == 1)
```

`SimTime.START` is the sentinel `SimTime(-1, START_OF_TICK, 0)` — a synthetic time that sorts before every real tick. The START entry is the input's *initial condition* before the run begins; everything else is a scheduled change. Without this entry a runner has no defined input value at tick 0.

`OutputSpec` has no equivalent invariant: outputs may have zero entries (e.g. a TICK_AWARE spec that asserts nothing) or a START/END pair (SIMPLE mode end-of-run assertion). See `runner/RecordingFinalizer.kt` for the SIMPLE-mode finalize logic.

`discardForRerecord` on `SpecBlockEntity` preserves this invariant explicitly:

```kotlin
inputs = s.inputs.map { InputSpec(it.pos, it.label, it.color, it.entries.filter { e -> e.first == SimTime.START }) }
```

It keeps only the START entry from each input. Filtering to `emptyList()` would crash on the next `addOrUpdateEntry`.

## SimTime ordering and the END sentinel

`SimTime` sorts by `(tick, phase.ordinal, order)`. The `Phase` enum order is *load-bearing* for run scheduling — `START_OF_TICK < BLOCK_EVENTS < TILE_ENTITY_TICK < SCHEDULED_TICKS < RANDOM_TICKS < END_OF_TICK < USER_INTERACTION`. `USER_INTERACTION` is the trailing phase but is hidden in the Advanced UI; in practice inputs fire at `START_OF_TICK` and outputs check at `END_OF_TICK`.

`SimTime.END = SimTime(Int.MAX_VALUE, END_OF_TICK, 0)` is the post-run sentinel for SIMPLE-mode end-of-run output assertions. Any real tick number compares less than `END.tick`, so an `END` entry always sorts last. The editor renders `START`/`END` rows as non-editable labels (commit `81953ca`).

## StateCondition: lazy codec and self-reference

`StateCondition.CODEC` is wrapped in `Codec.lazyInitialized { ... }` because the recursive constructors (`All`, `Any`, `Not`) reference `CODEC` from inside their own `MapCodec` definitions. Without the lazy wrapper, the JVM would NPE during class initialisation.

The dispatch tag map is built once per call inside the lazy block and cached by the codec framework. The 9 condition types are registered by string tag (`block_type`, `bool_property`, ... `int_range`); adding a new variant requires updating the encoder `when`, the codec map, and any UI code that switches on type.

`DEFAULT_CONDITION = BoolProperty("powered", true)` is the default for `BreakpointSpec` and `AutoSpec`. It is also what the editor shows as a placeholder when a breakpoint is first placed.

## RedstoneSpec defaults are the migration story

There is no `version` field. Backward compatibility is achieved entirely via `optionalFieldOf` defaults in `RedstoneSpec.CODEC`:

- `mode` defaults to `SpecMode.SIMPLE`
- `lifespan` defaults to `20`
- `inputs`/`outputs`/`breakpoints`/`auto_specs` all default to `emptyList()`
- `structure` is a true `Optional<String>` (null means "use spec id as structure id")

A spec written before a field existed loads cleanly with its default. The reverse is not true: removing a field from the codec strands old data. Treat field removal as a breaking change.
