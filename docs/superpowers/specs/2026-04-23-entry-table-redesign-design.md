# Entry Table Redesign — Design Spec

**Date:** 2026-04-23
**Scope:** Inline entry table for Input/Output specs, spec marker default naming, IntRange condition type

---

## Overview

Four related changes:
1. Spec markers auto-name new entries using the block name + letter suffix.
2. A new `StateCondition.IntRange` data model type for integer range conditions.
3. `SpecEditorScreen` entries replaced with an inline scrollable table; `EntryEditorScreen` removed entirely.
4. A new reusable `DropdownButton<T>` widget.

---

## Section 1 — Spec Marker Default Naming

When `SpecMarkerTool.createEntry` is called, the entry label defaults to `<blockPath>_<letter>` (e.g., `lever_a`, `stone_button_b`).

**Algorithm** (shared helper on `SpecMarkerTool`):
```
blockName = blockId.path           // e.g. "lever"
existing  = spec.allEntries.map { it.label }.toSet()
suffix    = 'a'
while "${blockName}_${suffix}" in existing → suffix++   // a, b, …, z, aa, ab, …
label = "${blockName}_${suffix}"
```

All four concrete marker items (`InputSpecMarkerItem`, `OutputSpecMarkerItem`, `BreakpointSpecMarkerItem`, `AutoSpecMarkerItem`) use this helper instead of passing `""`.

---

## Section 2 — `StateCondition.IntRange`

New variant added to the `StateCondition` sealed class:

```kotlin
data class IntRange(val name: String, val min: Int, val max: Int) : StateCondition()
```

**Semantics:** `min <= blockState[name] <= max`.

**Codec:** key `"int_range"`, fields `name` (STRING), `min` (INT), `max` (INT). Added to the existing dispatch codec map alongside existing types.

Runner evaluation of `IntRange` is out of scope for this change.

---

## Section 3 — Flat Working Data Model

### `RowProp` sealed class

Replaces `PropState` for inline editing. No `included` field — every row in the table is active.

```kotlin
sealed class RowProp {
    abstract val name: String
    abstract fun toCondition(): StateCondition

    data class Block(val blockId: Identifier) : RowProp() { override val name = "block" }
    data class Bool(override val name: String, var value: Boolean) : RowProp()
    data class ExactInt(override val name: String, var value: Int, val min: Int, val max: Int) : RowProp()
    data class RangeInt(override val name: String, var lo: Int, var hi: Int, val absMin: Int, val absMax: Int) : RowProp()
    data class Enum(override val name: String, var value: String, val options: List<String>) : RowProp()
}

data class FlatRow(var simTime: SimTime, val prop: RowProp)
```

### Flattening (load)

`StateCondition.All(...)` is recursively expanded into individual `FlatRow`s all sharing the same `SimTime`. Supported leaf types: `BlockType`, `BoolProperty`, `IntProperty`, `IntRange`, `EnumProperty`.

Complex conditions (`Any`, `Not`, `ContainerContents`) are not editable inline. They are collected into a passthrough list, preserved in memory, and re-appended unchanged on save.

### Reconstituting (save)

Flat rows grouped by `SimTime`:
- 1 row in group → stored unwrapped as `Pair<SimTime, SingleCondition>`.
- N rows in group → stored as `Pair<SimTime, StateCondition.All(conditions)>`.

Passthrough conditions appended after the reconstituted list.

---

## Section 4 — `DropdownButton<T>` Widget

A new reusable widget in the `screen` package:

- Renders as a button showing the current selection's label.
- On click: opens an overlay list of options (rendered on top of all other widgets).
- Selecting an option closes the overlay and fires an `onChange: (T) -> Unit` callback.
- Options are a `List<T>` with a `toComponent: (T) -> Component` label function.
- Only one dropdown overlay is open at a time (clicking another button closes the previous one).

Used by both the PROPERTY and PHASE columns in the entry table.

---

## Section 5 — `SpecEditorScreen` Inline Entry Table

### Table columns

| Column | Fixed Width | Visibility | Widget |
|---|---|---|---|
| TICK | 60px | TICK_AWARE and UPDATE_AWARE only | `IntEditBox` (-1 = INIT) |
| PHASE | 110px | UPDATE_AWARE only | `DropdownButton<Phase>` |
| PROPERTY | 100px | always | `DropdownButton<String>` |
| VALUE | 110px | always | see below |
| Remove | 20px | always | `LowProfileButtonWidget("×")` |

### VALUE widget per `RowProp` type

| Type | Widget |
|---|---|
| `Block` | `StringWidget` showing block path (read-only) |
| `Bool` | `CycleButton<Boolean>` (false / true) |
| `ExactInt` | `IntEditBox` + small `~` toggle button → switches row to `RangeInt` |
| `RangeInt` | two `IntEditBox` (lo, hi) + small `=` toggle button → switches back to `ExactInt` |
| `Enum` | `CycleButton<String>` over property values |

### PROPERTY dropdown

Options: all property names available on the block at that world position, derived from `BlockState.block.stateDefinition.properties`, plus `"block"` for block-type matching.

Switching property resets VALUE to the current world-state default for the new property type.

### Sorting

Rows are re-sorted by `SimTime` (stable) on two triggers:
- TICK `IntEditBox` loses hover (scroll-edit commits).
- PHASE dropdown selection confirmed.

### "+ Add Row" button

The last row of the table is always an "+ Add Row" button spanning full width. When clicked, appends a new `FlatRow` with:
- `simTime.tick = lastRow.tick + 1` (if last was INIT → tick 0).
- `simTime.phase` copied from last row (or `Phase.END_OF_TICK` if table empty).
- PROPERTY: first property of the block at that position.
- VALUE: current world-state value for that property.

### Scrollable container

The table is wrapped in a `ScrollableLayout` capped at 140px height so the overall `SpecEditorScreen` never overflows vertically.

---

## Section 6 — Remove `EntryEditorScreen`

`EntryEditorScreen.kt` is deleted. `SpecEditorScreen.openEntryEditor` and all call sites are removed.

Helper functions currently in `EntryEditorScreen.kt` are handled as follows:
- `buildPropStates`, `prePopulate`, `previewEntry`, `previewCondition` → deleted (no longer needed).
- `flattenConditionToMap` → kept; moved into `SpecEditorScreen.kt` directly (still used by `captureState`).

---

## Files Changed

| File | Change |
|---|---|
| `data/StateCondition.kt` | Add `IntRange` variant and codec entry |
| `item/SpecMarkerTool.kt` | Add `nextLabel()` helper; update all `createEntry` impls |
| `screen/EntryEditorScreen.kt` | **Deleted** |
| `screen/EntryEditorScreen.kt` helper fns | `flattenConditionToMap` moved into `SpecEditorScreen.kt`; rest deleted |
| `screen/DropdownButton.kt` | **New** — reusable dropdown widget |
| `screen/SpecEditorScreen.kt` | Replace entry list with inline `FlatRow` table |
