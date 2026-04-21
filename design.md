# RedstoneSpecs — Design Document

## Overview

RedstoneSpecs is a Fabric mod that brings spec-driven design and test-driven development (TDD) to Minecraft redstone engineering. It provides in-world tools to define, run, and publish reproducible tests for redstone circuits and contraptions, enabling iterative and verifiable redstone development.

---

## Goals

- Allow players to formally specify the expected behavior of a redstone circuit.
- Automate the execution of those specifications as in-world tests.
- Make specs shareable and version-controllable (publishable as items or data).
- Lower the barrier for collaborative redstone development by providing a common interface and vocabulary.

---

## Core Concepts

### RedstoneSpec

A **RedstoneSpec** is a self-contained, runnable specification that defines the expected behavior of a redstone circuit within a bounded region.

- Must be contained within a **Structure Box** anchored by a **SpecOrigin** block.
- Contains one or more **Spec Cases**.
- Has a **one-shot** flag (see Test Execution).

### SpecOrigin Block

The **SpecOrigin** is the only physical custom block in the system. It:
- Defines and displays the structure bounds (bounding box overlay).
- Stores the full `RedstoneSpec` in its block entity, including all spec entries.
- Is the entry point for running, editing, and exporting the spec.
- Right-clicking opens the **Spec Overview UI**.

---

## Spec Entries

All spec entries — **InputSpec**, **OutputSpec**, **BreakpointSpec**, and **AutoSpec** — are **pure data** stored inside the SpecOrigin block entity. There are no custom blocks or block entities for these types. Each entry includes its world position (as a `BlockPos` relative to the SpecOrigin). A custom renderer draws colored frame overlays over those positions in the world.

All spec entry types share a common base:

| Field  | Description |
|--------|-------------|
| pos    | `BlockPos` relative to the SpecOrigin |
| label  | Human-readable name |
| color  | Color of the rendered frame overlay |

### InputSpec

Marks a **signal source position**. During a test run, the runner drives the state of the block at `pos` according to the `stateSpec`.

| Field     | Description |
|-----------|-------------|
| stateSpec | `StateSpec` encoding the initial state (`SimTime.INIT`) and all subsequent state changes |

### OutputSpec

Marks an **observable position**. During a test run, the block state at `pos` is checked against the `stateSpec` at each SimTime.

| Field     | Description |
|-----------|-------------|
| stateSpec | `StateSpec` encoding the initial state and expected states at each sub-tick checkpoint |

### BreakpointSpec

Marks a **watch position**. At every sub-tick phase boundary, the block state at `pos` is checked against the `condition`. When satisfied, the game tick is immediately frozen.

| Field     | Description |
|-----------|-------------|
| condition | `StateCondition` — a set of property/value pairs that must all match to trigger. Default: `powered = true` |
| enabled   | Toggle to disable without removing the entry |

**Behavior:** Active both during and outside test runs. On trigger: freeze ticks, notify nearby players via HUD with the position and `SimTime`.

### AutoSpec

Marks a **recording trigger position**. When the block at `pos` satisfies the `condition`, the AutoSpec begins recording state changes across all InputSpec and OutputSpec positions into a new or updated Spec Case.

| Field     | Description |
|-----------|-------------|
| label     | Name for the recorded Spec Case (empty = auto-generate; non-empty = upsert by name) |
| condition | `StateCondition` controlling when recording starts and stops. Rising edge = start; falling edge = end |

**Recording behavior:** While the condition is satisfied, at every sub-tick phase boundary the states of all InputSpec and OutputSpec positions are diffed against the previous snapshot. Changed states are recorded as `StateSpec` entries at `SimTime(currentTick, phase)`.

---

## StateCondition

A **StateCondition** is a composable predicate evaluated against a position in the world. It is a sealed hierarchy supporting multiple check types and logical composition.

### Condition Types

| Type | Description |
|------|-------------|
| `BlockState` | Checks that one or more properties of the block's `BlockState` match expected values (e.g. `powered = true`, `facing = north`) |
| `ContainerContents` | Checks the contents of a container block (chest, hopper, barrel, etc.) at the position — e.g. slot is non-empty, slot contains a specific item, or total item count meets a threshold |
| *(future)* `Entity` | Checks properties of entities occupying or near the position |

### Logical Composition

Conditions can be combined:

| Combinator | Description |
|------------|-------------|
| `All` | All child conditions must be satisfied (AND) |
| `Any` | At least one child condition must be satisfied (OR) |
| `Not` | Inverts a single child condition |

### Default

The default `StateCondition` for `BreakpointSpec` and `AutoSpec` is `BlockState(powered = true)`.

---

### StateSpec

A **StateSpec** is an ordered list of `(SimTime, partial state)` entries.

- **Last-write, hold-forward semantics** — a property value holds until the next entry that changes it.
- The `SimTime.INIT` entry defines the initial state applied before the test starts. Every StateSpec must contain one.
- All block state property types supported: **boolean**, **integer**, **enum/string**.

**UI views** (same underlying data):
- **Step mode**: scrollable table of `(SimTime → property values)` rows.
- **Graph mode**: per-property step-curve tracks. Booleans = high/low waveform, enums = labeled step track, integers = numeric curve. Toggle between **Tick view** and **Phase view**.

---

## Sub-Tick Phases and Update Ordering

### Tick Phases (in execution order)

| Phase | Name | Description |
|-------|------|-------------|
| 0 | `START_OF_TICK` | Before any scheduled updates |
| 1 | `BLOCK_EVENTS` | Piston arms, note blocks — `Level.runBlockEvents()` |
| 2 | `TILE_ENTITY_TICK` | Block entity ticks |
| 3 | `SCHEDULED_TICKS` | Redstone wire, repeaters, comparators |
| 4 | `RANDOM_TICKS` | Random ticks |
| 5 | `END_OF_TICK` | After all updates; stable state reads |

### SimTime

```
SimTime(tick: Int, phase: Phase, order: Int = 0)
```

`SimTime.INIT` = `SimTime(-1, START_OF_TICK, 0)` — sorts before all in-run entries.

---

## Spec Cases

A **Spec Case** is one independent test scenario within a RedstoneSpec. It contains:
- A **name** and **lifespan** (in ticks).
- Lists of **InputSpec**, **OutputSpec**, **BreakpointSpec**, and **AutoSpec** entries, each carrying its own `pos`.

**Primary use case — truth table for a logic gate:**
- Spec Case 0: `A=0, B=0 → OUT=0`
- Spec Case 1: `A=0, B=1 → OUT=0`
- Spec Case 2: `A=1, B=0 → OUT=0`
- Spec Case 3: `A=1, B=1 → OUT=1`

---

## In-World Interaction

### Overlay Rendering

For the active Spec Case, the renderer draws a **colored frame** over each spec entry's world position:
- InputSpec: solid frame.
- OutputSpec: dashed frame.
- BreakpointSpec: red frame; flashes on trigger.
- AutoSpec: gold frame; pulses while recording.

### Adding / Editing Spec Entries

Spec entries are managed through the **Spec Overview UI** (right-click SpecOrigin) or via the **Spec Marker Tool** — a special item that, when used on a block within the SpecOrigin bounds, opens a dialog to add or edit a spec entry at that position for the active Spec Case.

### Spec Case Cycling

- **Keybind** (configurable, default unbound): cycles the active Spec Case for the last-interacted SpecOrigin.
- **Spec Overview UI**: click to select any Spec Case.

A HUD indicator shows the active Spec Case name and index while a SpecOrigin is selected.

---

## Test Execution

### Normal Mode (default)

1. For each tick and each phase:
   - Applies InputSpec state changes at `(tick, phase)` to real blocks at registered positions.
   - Checks real block states at OutputSpec positions against the StateSpec; records pass/fail.
2. **Does not restore the circuit** after the run.
3. Displays a **test result report** per Spec Case, per OutputSpec, per SimTime.

### One-Shot Mode (flag: `oneShot = true`)

- Runs once per manual reset. SpecOrigin locks after completion.

### Spec Case Execution

- Run **individually** or **all at once** sequentially.
- In run-all mode, the circuit is restored between Spec Cases.

---

## Publishing

- Export `RedstoneSpec` as JSON or a datapack entry.
- Future: in-game book format, external registry.

---

## Non-Goals (v1)

- Automatic circuit generation from specs (synthesis).
- Performance benchmarking.
- Multi-structure or cross-dimension specs.
- Server-side automated CI.
