---
title: Spec DSL invariants
tags: [data-model, dsl, design]
summary: What GarnetSpec / SpecRun guarantee at construction time and what callers can rely on.
---

# Spec DSL invariants

## GarnetSpec

- `id: String` — stable identifier; used as the filename stem for `.spec.kts` and `.nbt`.
- `bounds: Vec3i` — every axis ≥ 1 (enforced in `init {}`).
- `lifespan: Int` — ticks ≥ 1; `runGarnetSpec` loops `0 until lifespan`.
- `structure: String?` — optional structure resource id; supplies initial block state at run start if set.
- `strict: Boolean` — if true, `runGarnetSpec` scans for unexpected change-ticks at declared output positions.
- `block: SpecRun.() -> Unit` — the spec lambda. **This is the spec.** There is no flat entry list.

## SpecRun (execution context)

Constructed once per run by `runGarnetSpec`; passed as receiver to `spec.block`.

- `inputActions: TreeMap<SimTime, List<() -> Unit>>` — callbacks scheduled by `input { at(tick) { … } }`.
- `assertions: TreeMap<SimTime, List<() -> Unit>>` — callbacks scheduled by `output { at(tick) { … } }`.
- `failures: MutableList<SpecFailure>` — collected by `OutputScope.reportFailure`; thrown as `AssertionError` at run end.
- `outputDeclaredTicks: Map<BlockPos, Set<Int>>` — tracks which ticks each output position declared, for strict-mode scanning.

## SimTime ordering

`SimTime(tick, phase, order)` is `Comparable`: sorts by `tick` → `phase.ordinal` → `order`. Phase enum order is load-bearing:
`START_OF_TICK < BLOCK_EVENTS < TILE_ENTITY_TICK < SCHEDULED_TICKS < RANDOM_TICKS < END_OF_TICK`.

`SimTime.START = SimTime(-1, START_OF_TICK)` sorts before all real ticks.
`SimTime.END = SimTime(Int.MAX_VALUE, END_OF_TICK)` sorts after all real ticks.

## What's gone (post drop-data-layer refactor)

- `SpecEntry` data class — replaced by the DSL lambda approach; inputs/outputs are callbacks, not rows.
- `EntryKind.INPUT` / `OUTPUT` discriminator.
- `TestResult` — replaced by `SpecRun.failures` + `AssertionError`.
- `SpecMode`, `BreakpointSpec`, `AutoSpec` — removed.
- `SpecJsonCodec` — removed; JSON is not used for spec storage or network sync.
- `KtsSpecEmitter` / `RecordingFinalizer` — replaced by `RecordingDslEmitter`.
- `GarnetSpec.withEntryAddedOrUpdated` — no longer needed; the spec is a lambda, not a list.
