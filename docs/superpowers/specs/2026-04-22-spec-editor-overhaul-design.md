# Design: SpecEditorScreen Overhaul

Date: 2026-04-22

## Overview

A comprehensive overhaul of `SpecEditorScreen` (the editor for `InputSpec` and `OutputSpec` entries) covering:

1. **Data model unification** — replace `StateSpec` + `Map<String, String>` with a typed `StateCondition` tree
2. **Mod configuration** — YACL config screen with auto-save-on-exit option
3. **Color picker widget** — 4×4 dye color grid + custom hex input
4. **Entry table** — scrollable TICK | PHASE | STATE table with ✎ and ✕ per row
5. **`StateEntryEditorScreen`** — sub-screen for editing a single `(SimTime, StateCondition)` entry
6. **`BlockStateFormBuilder`** — generic typed form builder from live block state introspection
7. **Unsaved changes guard** — ESC prompts save/discard (or auto-saves if configured)
8. **Capture State button** — replaces "Capture Init State"; creates a diff entry 1 tick after the last

Clean break: existing serialized data using `StateSpec`/`Map<String, String>` will not load. No migration.

---

## 1. Data Model

### `StateCondition` (extended)

`StateCondition.BlockState(Map<String, String>)` is removed. Three typed leaf variants replace it:

```kotlin
sealed class StateCondition {
    // Typed property leaves (new)
    data class BlockType(val blockId: Identifier) : StateCondition()
    data class BoolProperty(val name: String, val value: Boolean) : StateCondition()
    data class IntProperty(val name: String, val value: Int) : StateCondition()
    data class EnumProperty(val name: String, val value: String) : StateCondition()

    // Logical combinators (unchanged)
    data class All(val conditions: List<StateCondition>) : StateCondition()
    data class Any(val conditions: List<StateCondition>) : StateCondition()
    data class Not(val condition: StateCondition) : StateCondition()

    // Container check (unchanged)
    data class ContainerContents(val slot: Int?, val item: Identifier?, val minCount: Int) : StateCondition()
}
```

### `StateSpec` removed

`StateSpec` class is deleted entirely. All references replaced with `StateCondition` directly.

### `InputSpec` / `OutputSpec`

```kotlin
data class InputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val entries: List<Pair<SimTime, StateCondition>>,
) : SpecEntry()

// OutputSpec: identical shape
```

The `entries` list must contain exactly one `SimTime.INIT` entry (validated at construction / save time). Multiple property checks at a single time point are expressed as `All(listOf(...))`.

### `ConditionEvaluator` updates

`evaluateCondition` gains cases for the three new leaf types, reading typed block state properties via MC's `Property<T>` API:

- `BlockType` → compare `level.getBlockState(worldPos).block` registry key against `blockId`
- `BoolProperty` → read `BooleanProperty` by name, compare boolean
- `IntProperty` → read `IntegerProperty` by name, compare int
- `EnumProperty` → read `EnumProperty` / `DirectionProperty` by name, compare `getName()` string

---

## 2. Mod Configuration (YACL + Mod Menu)

**New file:** `src/client/kotlin/.../client/config/ModConfig.kt`

```kotlin
object ModConfig {
    var autoSaveOnExit: Boolean = false
}
```

Serialized to JSON in the standard Fabric config directory via YACL's serialization.

**New file:** `src/client/kotlin/.../client/config/ModMenuIntegration.kt`

Implements `ModMenuApi`, returns a YACL-built `Screen` as the config screen factory. Registered as a `modmenu` entrypoint in `fabric.mod.json`.

**Config screen contents:**

| Option | Type | Default | Description |
|---|---|---|---|
| Auto-save on exit | Toggle | Off | When on, closing SpecEditorScreen with unsaved changes saves silently instead of prompting |

---

## 3. `ColorPickerWidget`

**New file:** `src/client/kotlin/.../client/widget/ColorPickerWidget.kt`

A custom widget that renders as a button showing the current color (colored square + hex label). Clicking it opens an inline dropdown panel containing:

- **4×4 grid** of 16 Minecraft dye color squares (colored rectangles, no text labels). Hovering shows the color name as a tooltip. Clicking sets the color and closes the panel.
- **Hex input row** below the grid: a 6-digit `EditBox` + a live preview swatch square. Typing a valid 6-digit hex updates the color in real time. Invalid input is ignored on close.

The 16 dye colors and their RGB values are stored as a companion object constant list on `ColorPickerWidget`.

The widget stores `var color: Int` and exposes it for `SpecEditorScreen` to read on Save.

---

## 4. `SpecEditorScreen` Overhaul

### Entry table

The state entries section (for `InputSpec`/`OutputSpec`) is rendered as a scrollable table. Column layout:

```
TICK   | PHASE           | STATE (preview)           | ✎ | ✕
-------+-----------------+---------------------------+---+---
INIT   | START_OF_TICK   | powered=false             | ✎ | ✕
t0     | END_OF_TICK     | powered=true, lit=true    | ✎ | ✕
```

- **TICK** — `"INIT"` for `SimTime.INIT`, else `"t<n>"`
- **PHASE** — full `Phase` name (truncated to column width)
- **STATE** — flat preview of the `StateCondition`: leaf conditions rendered as `name=value`, `All`/`Any` joined with `,`, truncated with `…` at column edge
- **✎** — opens `StateEntryEditorScreen` pre-filled with the existing `(SimTime, StateCondition)`. On confirm, the entry is replaced in `workingEntries`.
- **✕** — removes the entry from `workingEntries` and calls `rebuildWidgets()`

`workingEntries: MutableList<Pair<SimTime, StateCondition>>?` persists across `rebuildWidgets()`, initialized from the entry on first `init()` or first `tick()` after server sync.

Max ~5 rows visible; a scroll offset int tracks the visible window when there are more entries.

### Unsaved changes guard

`isDirty: Boolean` is computed by comparing `workingEntries`, `labelEditBox.value`, and `colorPickerWidget.color` to the last-saved entry values.

On `onClose()`:
- If `!isDirty` → close normally
- If `ModConfig.autoSaveOnExit` → call `save()` then close
- Else → open vanilla `ConfirmScreen` with **Save** and **Discard** buttons

ESC triggers `onClose()` naturally via the base `Screen` implementation.

### Capture State button

Replaces "Capture Init State". Label: **"Capture State"**.

On click:
1. Read current live block state at `originPos + entryRelPos` using `captureBlockStateProps()`
2. If `workingEntries` is empty → create a full INIT entry (`SimTime.INIT`, `All(allProps)`)
3. Otherwise → find the last entry by `SimTime` order; diff current state against it; build a `StateCondition` from the block type (if changed) plus only changed properties; insert a new entry at `SimTime(lastTick + 1, Phase.END_OF_TICK)`

Diff is computed by comparing raw string values from `captureBlockStateProps` against the existing condition values. To extract the "last known values" from the last entry's `StateCondition`, flatten it: walk `All(conditions)` leaves and single leaves, collecting `name→value` from `BoolProperty`/`IntProperty`/`EnumProperty`; ignore `Any`/`Not`/`ContainerContents`. Properties not mentioned in the last condition are treated as unchanged. The typed `StateCondition` variant for each changed property is inferred from the block's `stateDefinition` property type (same logic as `BlockStateFormBuilder`).

### "+ Add Entry" button

Opens `StateEntryEditorScreen` with a blank entry (tick=0, phase=END_OF_TICK, empty condition). On confirm, the new entry is appended to `workingEntries` and `rebuildWidgets()` is called.

---

## 5. `StateEntryEditorScreen`

**New file:** `src/client/kotlin/.../client/screen/StateEntryEditorScreen.kt`

Opened from `SpecEditorScreen` via ✎ or "+ Add Entry". Constructor:

```kotlin
class StateEntryEditorScreen(
    private val originPos: BlockPos,
    private val entryRelPos: BlockPos,
    private val initial: Pair<SimTime, StateCondition>?,  // null = new entry
    private val onConfirm: (SimTime, StateCondition) -> Unit,
) : Screen(...)
```

### Layout

```
Title: "Edit Entry"

Tick:  [−] [___] [+]     Phase: [ END_OF_TICK ▾ ]

── Conditions ──────────────────────────────────────
  [✓] powered      [ false ▾ ]
  [✓] power        [−][ 4 ][+]
  [ ] waterlogged  [ false ▾ ]
────────────────────────────────────────────────────

              [Confirm]   [Cancel]
```

### Tick field

Three widgets: `−` button, center `EditBox` (int or blank for INIT), `+` button. `−` decrements (floor: -1 = INIT), `+` increments. Blank or `-1` value → `SimTime.INIT`.

### Phase dropdown

A custom `DropdownWidget<Phase>` (or adapted `CycleButton` styled as a dropdown). Clicking opens a list of all 6 `Phase` values; clicking one selects it. Default: `END_OF_TICK`.

### Condition rows

Generated by `BlockStateFormBuilder` from the live block state at `entryRelPos`. Each row:
- Checkbox (include in result?)
- Property name label
- Value widget (type-appropriate)

When `initial` is non-null, `BlockStateFormBuilder` walks the existing `StateCondition` to pre-check and pre-fill rows:
- `All(conditions)` → check each matching leaf row
- Single leaf → check that one property row
- `Any`/`Not` → rows are shown unchecked; the complex condition is preserved as-is and not editable in this screen (displayed as a read-only note)

### Confirm button

Enabled only when at least one condition row is checked. On click:
- 1 checked row → single typed leaf condition
- 2+ checked rows → `All(checkedConditions)`
- Calls `onConfirm(simTime, condition)` then closes this screen

---

## 6. `BlockStateFormBuilder`

**New file:** `src/client/kotlin/.../client/widget/BlockStateFormBuilder.kt`

Stateless utility object. Takes a live `BlockState` and produces typed `PropertyRow` objects:

```kotlin
object BlockStateFormBuilder {
    fun buildRows(
        state: BlockState,
        existingCondition: StateCondition?,
        font: Font,
        x: Int, y: Int, rowHeight: Int,
    ): List<PropertyRow>
}
```

### `PropertyRow` hierarchy

```kotlin
sealed class PropertyRow {
    abstract val name: String
    abstract var included: Boolean
    abstract fun currentCondition(): StateCondition
    abstract fun addWidgetsTo(screen: Screen)

    class BlockTypeRow(val blockId: Identifier, ...) : PropertyRow()
    // value widget: read-only label showing block registry ID (value fixed to the live block)

    class BoolRow(val name: String, val property: BooleanProperty, ...) : PropertyRow()
    // value widget: CycleButton<Boolean> or checkbox toggle

    class IntRow(val name: String, val property: IntegerProperty, ...) : PropertyRow()
    // value widget: int stepper (−/editbox/+), bounded by property.min/max

    class EnumRow(val name: String, val property: EnumProperty<*>, ...) : PropertyRow()
    // value widget: DropdownWidget of property.possibleValues mapped to getName()
}
```

`buildRows` always prepends a `BlockTypeRow` as the first row (name = `"block"`, value = the current block's registry ID). This lets users assert the block type itself in addition to its properties — useful when a piston or other mechanism moves a different block to the position.

### Property type inference

`BlockState.block.stateDefinition.properties` returns `Collection<Property<*>>`. Each property is inspected:
- `is BooleanProperty` → `BoolRow`
- `is IntegerProperty` → `IntRow`
- otherwise → `EnumRow` (covers `DirectionProperty`, `EnumProperty<Enum>`, etc.)

### Pre-filling from existing condition

```kotlin
fun prePopulate(rows: List<PropertyRow>, condition: StateCondition) {
    when (condition) {
        is StateCondition.All -> condition.conditions.forEach { prePopulate(rows, it) }
        is StateCondition.BoolProperty -> rows.filterIsInstance<BoolRow>()
            .find { it.name == condition.name }?.apply { included = true; value = condition.value }
        is StateCondition.IntProperty -> rows.filterIsInstance<IntRow>()
            .find { it.name == condition.name }?.apply { included = true; value = condition.value }
        is StateCondition.EnumProperty -> rows.filterIsInstance<EnumRow>()
            .find { it.name == condition.name }?.apply { included = true; value = condition.value }
        else -> { /* Any/Not/ContainerContents: ignored, rows stay unchecked */ }
    }
}
```

---

## 7. New Files Summary

| File | Purpose |
|---|---|
| `client/config/ModConfig.kt` | YACL-backed config singleton |
| `client/config/ModMenuIntegration.kt` | Mod Menu entrypoint |
| `client/widget/ColorPickerWidget.kt` | 4×4 color grid + hex input dropdown widget |
| `client/widget/BlockStateFormBuilder.kt` | Typed property row factory |
| `client/screen/StateEntryEditorScreen.kt` | Sub-screen for editing one `(SimTime, StateCondition)` |

### Modified files

| File | Change |
|---|---|
| `data/StateCondition.kt` | Add `BoolProperty`, `IntProperty`, `EnumProperty`; remove `BlockState` |
| `data/SpecEntry.kt` | Replace `stateSpec: StateSpec` with `entries: List<Pair<SimTime, StateCondition>>` |
| `data/StateSpec.kt` | **Deleted** |
| `runner/ConditionEvaluator.kt` | Handle new typed leaf conditions |
| `client/screen/SpecEditorScreen.kt` | Table, color picker, dirty guard, Capture State, YACL wiring |
| `fabric.mod.json` | Add `modmenu` entrypoint |

---

## 8. Out of Scope

- Editing `Any`/`Not` condition trees in `StateEntryEditorScreen` (rows show unchecked; complex conditions round-trip through save unchanged)
- `ContainerContents` condition UI (no widget row generated for it)
- `BreakpointSpec`/`AutoSpec` condition editing (separate concern, unchanged by this design)
- Scrolling in `StateEntryEditorScreen` condition list (assumed to fit within panel for typical block state sizes)
