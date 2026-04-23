# Design: Workflow Redesign v1

Date: 2026-04-22

## Overview

A ground-up redesign of the Redstone Specs user workflow with the goal of minimising click
count. Key changes:

- `SpecCase` is removed — each `RedstoneSpec` block is a single test execution
- Three modes (Simple, Tick-Aware, Update-Aware) replace the per-entry timing complexity
- Specs are identified by a plain string id and persisted as VCS-friendly JSON
- Structure (physical blocks) is saved separately as an NBT file and can be shared across specs
- The overview screen becomes the primary hub with a scrollable entry list; `SpecCasesScreen` is deleted

---

## 1. Data Model

### `RedstoneSpec`

```kotlin
data class RedstoneSpec(
    val id: String,                   // e.g. "lever_lamp"
    val mode: SpecMode,               // SIMPLE | TICK_AWARE | UPDATE_AWARE
    val bounds: BoundingBox,
    val lifespan: Int,
    val structure: String?,           // null = not linked; or id of a .nbt file
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
)
```

`SpecCase` is removed entirely. The old `name: String` and `id: UUID` fields are replaced
by `id: String`. `oneShot` is removed.

### `SpecMode`

```kotlin
enum class SpecMode { SIMPLE, TICK_AWARE, UPDATE_AWARE }
```

| Mode | Input timing | Output timing | Phase/order visible |
|---|---|---|---|
| SIMPLE | `SimTime.INIT` only | `SimTime(lifespan, END_OF_TICK)` only | No |
| TICK_AWARE | per tick | per tick | No (always `END_OF_TICK`) |
| UPDATE_AWARE | full `SimTime(tick, phase, order)` | full `SimTime` | Yes |

On Save in SIMPLE mode: if any entry carries per-tick `SimTime` values, the user is warned
that they will be collapsed to init/end. The warning is shown only at save time, not during
mode switching.

### `InputSpec` / `OutputSpec` (shape unchanged, constraint relaxed)

```kotlin
data class InputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val entries: List<Pair<SimTime, StateCondition>>,
) : SpecEntry()
```

`InputSpec` retains the requirement of exactly one `SimTime.INIT` entry — it represents the
state the input block is driven to at the start of the test. Additional entries in
TICK_AWARE / UPDATE_AWARE mode represent subsequent state changes.

`OutputSpec` drops the existing INIT constraint. In SIMPLE mode it has one entry at
`SimTime(lifespan, END_OF_TICK)`. In TICK_AWARE / UPDATE_AWARE mode it may have entries at
any `SimTime`.

`BreakpointSpec` and `AutoSpec` are unchanged.

### Simple Mode collapse on Save

When saving in SIMPLE mode and any entry has per-tick `SimTime` values:
- `InputSpec`: all entries except the single INIT entry are dropped
- `OutputSpec`: all entries are replaced with a single entry at `SimTime(lifespan, END_OF_TICK)`
  carrying the `StateCondition` of the last existing entry (by `SimTime` order)

The user is warned before this destructive collapse and can cancel.

---

## 2. UI — Spec Overview Screen

The overview screen is the primary hub. `SpecCasesScreen` is deleted.

```
┌──────────────────────────────────────────┐
│           Redstone Spec                  │
│                                          │
│  ID:     [lever_lamp              ] [✎]  │
│  Mode:   [ Simple ◀▶ ]                   │
│  Life:   [−][ 8 ][+]  ticks             │
│  Struct: lever_lamp               [✎]   │
│                                          │
│  ┌────────────────────────────────────┐  │
│  │ ▶ IN   lever       (0,1,0)        │  │
│  │ ▶ IN   button      (2,1,0)        │  │
│  │ ▶ OUT  lamp        (0,1,2)        │  │
│  │ ▶ OUT  counter     (3,1,2)        │  │
│  │ ▶ BP   watchpoint  (1,1,1)        │  │
│  └────────────────────────────────────┘  │
│                                          │
│  ✓ 3/3 checks passed                    │
│                                          │
│  [Run]  [Load]  [Save]  [Done]          │
└──────────────────────────────────────────┘
```

### Fields

- **ID** — plain string, editable via `[✎]` pencil button; validated non-empty on save
- **Mode** — cycle button: Simple → Tick-Aware → Update-Aware → Simple
- **Life** — tick stepper (`−` / number / `+`), always visible
- **Struct** — shows linked structure id or `(none)` if null; `[✎]` opens a dropdown (see below)

### Entry List

Scrollable list of all `InputSpec`, `OutputSpec`, `BreakpointSpec`, and `AutoSpec` entries.
Each row: type tag (`IN` / `OUT` / `BP` / `AUTO`), label, relative position. Clicking a row
opens the entry editor screen for that entry.

### Structure Dropdown

Clicking `[✎]` next to Struct opens a dropdown listing:
- All `.nbt` files found in the spec save directory
- `<new>` at the top

Selecting an existing file links the spec to it (no file written). Selecting `<new>` opens
an id input pre-filled with the spec's current id, then immediately saves a new `.nbt` from
the current bounds and sets `spec.structure` to the new id.

If `structure` is null, the field shows `(none)` and the same dropdown is available.

### Buttons

- **[Run]** — executes test against current world state (no auto-reset)
- **[Load]** — loads spec config + structure (see Load Flow)
- **[Save]** — saves spec config + handles structure (see Save Flow)
- **[Done]** — closes screen

---

## 3. Save Flow

1. Validate id is non-empty. If in SIMPLE mode and any entry has per-tick `SimTime`, warn
   user that entries will be collapsed; user confirms or cancels.
2. Write spec config to `<save-dir>/<id>.json`.
3. Structure handling:
   - **`structure` is set** → compare live blocks in bounds to `<structure>.nbt`
     - Identical: done
     - Different: prompt **"Structure changed. Save to `<structure>` or fork to new id?"**
       - Save → overwrite `<structure>.nbt`
       - Fork → open id input (pre-filled with current structure id) → write `<new-id>.nbt`,
         set `spec.structure = new-id`
   - **`structure` is null AND `<id>.nbt` does not exist** → auto-save structure as `<id>.nbt`,
     set `spec.structure = id`, no prompt
   - **`structure` is null AND `<id>.nbt` already exists** → prompt **"Save structure as
     `<id>` (overwrites existing) or fork to new id?"**
     - Save → overwrite `<id>.nbt`, set `spec.structure = id`
     - Fork → open id input (pre-filled with spec id) → write `<new-id>.nbt`,
       set `spec.structure = new-id`

---

## 4. Load Flow

1. Read `<id>.json` → populate spec config into block entity.
2. If `structure` is set, read `<structure>.nbt`:
   - If any block inside bounds is non-air: prompt **"Overwrite existing blocks?"**
     - Yes → clear bounds → place structure
     - No → skip structure load

---

## 5. Marker Item Workflow

Mechanics are unchanged. Mode controls what timing the UI exposes:

- **SIMPLE** — input marker captures `SimTime.INIT`; output marker captures
  `SimTime(lifespan, END_OF_TICK)`. SimTime is not shown to the user.
- **TICK_AWARE** — entry editor shows tick stepper; phase is hidden (fixed to `END_OF_TICK`).
- **UPDATE_AWARE** — entry editor shows full tick + phase + order controls.

---

## 6. File Persistence

### Layout

```
<world-folder>/redstonespecs/
  lever_lamp.json
  lever_lamp.nbt
  shared_counter.nbt    ← can be referenced by multiple specs
```

### Spec JSON format

```json
{
  "id": "lever_lamp",
  "mode": "SIMPLE",
  "bounds": { "minX": 1, "minY": 0, "minZ": 1, "maxX": 5, "maxY": 4, "maxZ": 5 },
  "lifespan": 8,
  "structure": "lever_lamp",
  "inputs": [],
  "outputs": [],
  "breakpoints": [],
  "auto_specs": []
}
```

VCS-friendly: deterministic key order, no UUIDs, human-readable mode string.

### Structure NBT

Standard Minecraft `StructureTemplate` format. Compatible with structure blocks.

### Config (v1)

One new config option added to the YACL screen:

| Option | Type | Default | Description |
|---|---|---|---|
| Spec save directory | String | `redstonespecs` (relative to world folder) | Root folder for `.json` and `.nbt` files |

---

## 7. Runner Impact

`SpecRunner` and `SpecRunnerCoordinator` currently iterate over `spec.specCases`. With
`SpecCase` removed, the runner works directly on `RedstoneSpec.inputs`, `.outputs`, etc.
The run lifecycle (init inputs → tick → check outputs) is unchanged; the outer case-loop
is simply removed.

---

## 8. Removed / Out of Scope

| Item | Status |
|---|---|
| `SpecCase` | Removed |
| `SpecCasesScreen` | Removed |
| `RedstoneSpec.id: UUID` | Replaced by `id: String` |
| `RedstoneSpec.name: String` | Replaced by `id: String` |
| `RedstoneSpec.oneShot` | Removed |
| Namespaced IDs | Deferred to v2 |
| Default namespace config option | Deferred to v2 |
| Auto-minimize lifespan | Deferred to v2 |
| Configurable save directory | In config but path logic is v1; UI label reserved for v2 |
