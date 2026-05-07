---
title: Spec data model invariants
tags: [data-model, design]
summary: What RedstoneSpec / SpecEntry guarantee at construction time and what callers can rely on.
---

# Spec data model invariants

## RedstoneSpec
- `bounds: Vec3i` — every axis ≥ 1.
- `lifespan: Int` — ticks; runner stops the run once `ticksElapsed >= lifespan`.
- `structure: String?` — optional structure resource id; supplies initial block state at run start.
- `entries: List<SpecEntry>` — flat list. May be empty.
- For every `entry` in `entries`: `entry.pos` lies inside `bounds`
  (`0 <= pos.{x,y,z} < bounds.{x,y,z}`).

## SpecEntry
- `pos`, `label`, `color`, `kind`, `time`, `condition` — all required.
- No required relationship between entries — duplicate `(pos, kind, time)`
  is allowed at construction time, but `RedstoneSpec.withEntryAddedOrUpdated`
  treats `(pos, kind, time)` as the entry's identity for replace-vs-append.

## What's gone (post data-layer redesign)

- `SpecMode` (mode field on RedstoneSpec).
- `BreakpointSpec` and `AutoSpec` sealed-class siblings.
- The "exactly one `SimTime.START` entry per InputSpec" invariant.
- `BoundingBox` — replaced by `Vec3i` size; positions are local.
- The nested `entries: List<Pair<SimTime, StateCondition>>` per InputSpec/OutputSpec
  — every (time, condition) is now its own SpecEntry row.

Initial state for the circuit-under-test now comes from the structure file
referenced by `RedstoneSpec.structure`, NOT from `SimTime.START` entries.
