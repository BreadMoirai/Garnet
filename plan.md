# RedstoneSpecs — Implementation Plan

## Tech Stack

- **Language**: Kotlin (Fabric Language Kotlin)
- **Platform**: Fabric, Minecraft 1.21.x / snapshot 26.x (Stonecutter multi-version)
- **UI**: YACL for config; custom `Screen` subclasses for spec editing
- **Config/Mod Menu**: ModMenu + YACL
- **Data persistence**: NBT on the SpecOrigin block entity; JSON/datapack for published specs

---

## Phase 1 — Data Model

Pure data — no world interaction. All types have full Codec + NBT serialization.

### 1.1 SimTime

```kotlin
enum class Phase { START_OF_TICK, BLOCK_EVENTS, TILE_ENTITY_TICK, SCHEDULED_TICKS, RANDOM_TICKS, END_OF_TICK }

data class SimTime(val tick: Int, val phase: Phase, val order: Int = 0) : Comparable<SimTime> {
    companion object {
        val INIT = SimTime(-1, Phase.START_OF_TICK, 0)
    }
}
```

Natural ordering: `INIT` sorts first; then tick → phase ordinal → order.

### 1.2 StateSpec

```kotlin
@JvmInline
value class StateSpec(val entries: List<Pair<SimTime, Map<String, String>>>)
```

- Sorted by `SimTime`.
- `SimTime.INIT` entry is required; defines initial state before test start.
- All property types serialized as `String`.
- Codec + NBT.

### 1.3 StateCondition

```kotlin
sealed class StateCondition {
    // Logical combinators
    data class All(val conditions: List<StateCondition>) : StateCondition()
    data class Any(val conditions: List<StateCondition>) : StateCondition()
    data class Not(val condition: StateCondition) : StateCondition()

    // Block state property check: all entries must match
    data class BlockState(val properties: Map<String, String>) : StateCondition()

    // Container contents check at the position
    data class ContainerContents(
        val slot: Int? = null,           // null = any slot
        val item: ResourceLocation? = null,  // null = any item (just non-empty)
        val minCount: Int = 1,
    ) : StateCondition()

    // Future: Entity (placeholder, not implemented in v1)
}

val DEFAULT_CONDITION: StateCondition = StateCondition.BlockState(mapOf("powered" to "true"))
```

Each leaf condition is evaluated against the world state at the spec entry's `pos`. Codec + NBT (recursive).

### 1.4 Spec Entry Base

```kotlin
sealed class SpecEntry {
    abstract val pos: BlockPos      // relative to SpecOrigin
    abstract val label: String
    abstract val color: Int
}
```

### 1.5 InputSpec / OutputSpec

```kotlin
data class InputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val stateSpec: StateSpec,
) : SpecEntry()

data class OutputSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val stateSpec: StateSpec,
) : SpecEntry()
```

### 1.6 BreakpointSpec

```kotlin
data class BreakpointSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
    val enabled: Boolean = true,
) : SpecEntry()
```

### 1.7 AutoSpec

```kotlin
data class AutoSpec(
    override val pos: BlockPos,
    override val label: String,
    override val color: Int,
    val condition: StateCondition = DEFAULT_CONDITION,
) : SpecEntry()
```

`label` drives recording behavior: empty = always new auto-named Spec Case; non-empty = upsert by name.

### 1.8 SpecCase

```kotlin
data class SpecCase(
    val name: String,
    val lifespan: Int,
    val inputs: List<InputSpec>,
    val outputs: List<OutputSpec>,
    val breakpoints: List<BreakpointSpec>,
    val autoSpecs: List<AutoSpec>,
)
```

### 1.9 RedstoneSpec

```kotlin
data class RedstoneSpec(
    val id: UUID,
    val name: String,
    val bounds: BlockBox,        // relative to SpecOrigin
    val oneShot: Boolean,
    val specCases: List<SpecCase>,
)
```

### 1.10 TestResult

```kotlin
data class TickCheck(val simTime: SimTime, val label: String, val expected: String, val actual: String, val pass: Boolean)
data class SpecCaseResult(val specCaseName: String, val checks: List<TickCheck>)
data class TestResult(val specId: UUID, val timestamp: Long, val results: List<SpecCaseResult>)
```

---

## Phase 2 — SpecOrigin Block & Block Entity

The **SpecOrigin** is the only physical custom block in the system.

### 2.1 SpecOriginBlock

- Custom block; right-click → **Spec Overview Screen**.
- Client-side: renders bounding box around `spec.bounds` via `WorldRenderEvents`.

### 2.2 SpecOriginBlockEntity

- Stores `RedstoneSpec`, `activeSpecCaseIndex: Int`, `lastTestResult: TestResult?`.
- `setActiveSpecCase(index: Int)` — updates and syncs to clients.
- Full NBT serialization; syncs to client via `toUpdatePacket`.

### 2.3 SpecOriginItem

- Places the SpecOrigin block.
- Tooltip shows spec name + active Spec Case if pre-configured NBT is present.

---

## Phase 3 — Spec Marker Tool & Overlay Renderer

There are no custom blocks for InputSpec, OutputSpec, BreakpointSpec, or AutoSpec. All spec entry data lives in the SpecOriginBlockEntity.

### 3.1 Custom Items
- InputSpecMarker
- OutputSpecMarker
- BreakpointSpecMarker
- AutoSpecMarker

A special item. When used on a block within the SpecOrigin bounds:
- If no entry exists at that pos in the active Spec Case: create that spec and then opens the **Spec Editor Screen** for that entry.
- If any entry exists at that pos in the active Spec CaseL Opens the **Spec Editor Screen** for that entry.
- If pickItem is used on any block with a Spec entry while holding any spec marker, swaps to that SpecMarker item using pickItem
- If pickItem + ctrl is use on any block with a Spec entry while holding a spec marker, a SpecMarker item is added to inventory with the nbt data for that entry.
- When that SpecMarker item with nbt data is used to place a new Spec entry on a block, that nbt data is copied into that spec entry

left-click (attack) while holding any SpecMarker: removes the entry at that pos with undo support via (ctrl + z).

### 3.2 Overlay Renderer (client)

`WorldRenderEvents.AFTER_TRANSLUCENT` iterates all loaded `SpecOriginBlockEntity` instances. For the active Spec Case, for each spec entry:
- Computes world pos: `originPos + entry.pos`.
- Draws a colored frame outline over that block space using `entry.color`.
- Frame style: InputSpec = solid, OutputSpec = solid, BreakpointSpec = dashed (glows on trigger), AutoSpec = gold (glows while recording).
- Renders a floating label above.

---

## Phase 4 — Test Runner

Server-side; hooks into the tick pipeline at each phase boundary via mixins.

### 4.1 Execution Model

```
Tick N:
  START_OF_TICK    → apply inputs / check outputs / check breakpoints / check autoSpecs at (N, START_OF_TICK)
  [vanilla block events]
  BLOCK_EVENTS     → ...
  [vanilla block entity ticks]
  TILE_ENTITY_TICK → ...
  [vanilla scheduled ticks]
  SCHEDULED_TICKS  → ...
  [vanilla random ticks]
  RANDOM_TICKS     → ...
  END_OF_TICK      → ...
```

### 4.2 SpecRunner

```kotlin
class SpecRunner(
    val spec: RedstoneSpec,
    val specCase: SpecCase,
    val originPos: BlockPos,
    val level: ServerLevel,
) {
    fun start()
    fun onPhase(tick: Int, phase: Phase)
    fun finish(): SpecCaseResult
}
```

- `start()`: snapshot the region for potential reset.
- `onPhase(tick, phase)`:
  1. Apply `InputSpec` state changes at `SimTime(tick, phase, *)` to real blocks.
  2. Check `OutputSpec` states at `SimTime(tick, phase, *)`. Record `TickCheck`.
  3. Check `BreakpointSpec` conditions; freeze ticks on match.
  4. Check `AutoSpec` conditions; drive recording lifecycle (rising/falling edge).
- `finish()`: return `SpecCaseResult`; do not restore circuit by default.

### 4.3 SpecRunnerCoordinator (server singleton)

- Holds active `SpecRunner` list.
- Dispatches phase callbacks from mixins to all runners.
- Also dispatches to active `AutoSpec` recorders and enabled `BreakpointSpec` checks outside of test runs.
- On completion: stores `TestResult` on origin block entity; sends `TestResultS2CPacket`.

### 4.4 AutoSpec Recording

An `AutoSpecRecorder` is created on rising edge of an AutoSpec condition. Each phase it diffs the real block states at all InputSpec and OutputSpec positions against its last snapshot, recording changed states as `StateSpec` entries at `SimTime(currentTick, phase)`. On falling edge, it commits the result to the SpecOrigin.

### 4.5 Circuit Reset

- Manual reset: "Reset" → `ResetSpecC2SPacket` → restore snapshot; clear one-shot lock.
- Run-all mode: automatic restore between each Spec Case.

### 4.6 Phase Hook Mixins

| Mixin Target | Phase |
|---|---|
| `MinecraftServer.tickServer` (before block events) | `START_OF_TICK` |
| `ServerLevel.runBlockEvents` (after) | `BLOCK_EVENTS` |
| `ServerLevel.tickBlockEntities` (after) | `TILE_ENTITY_TICK` |
| `ServerLevel.tickChunk` scheduled tick section (after) | `SCHEDULED_TICKS` |
| `ServerLevel.tickChunk` random tick section (after) | `RANDOM_TICKS` |
| `MinecraftServer.tickServer` (at end) | `END_OF_TICK` |

---

## Phase 5 — UI Screens

### 5.1 Spec Overview Screen

Opened by right-clicking SpecOrigin.

- Header: spec name (editable), one-shot toggle.
- Spec Case list: add, remove, rename, reorder; click to set active.
- Buttons: **Run Selected**, **Run All**, **Reset**, **Export JSON**.
- Result summary panel: per-Spec-Case pass/fail badge.

### 5.2 Spec Editor Screen

Opened by Spec Marker Tool on an existing entry.

- Fields: Label, Color, type-specific fields:
  - InputSpec / OutputSpec: StateSpec editor (Step / Graph mode, Tick / Phase view toggle).
  - BreakpointSpec: condition editor (property/value pairs), enabled toggle.
  - AutoSpec: label field, condition editor.

### 5.3 Test Result Screen

- Tab bar: one tab per Spec Case.
- Grid: rows = OutputSpecs, columns = SimTimes grouped by tick/phase.
- Cell colors: green (pass), red (fail), grey (don't care).
- Click cell: tooltip with expected vs actual.

### 5.4 HUD Overlay

When a SpecOrigin is selected (last interacted, within range):
- `[SpecName] — Case 2 / 4: A=1, B=0`
- Last run result badge: ✓ / ✗ / –.

---

## Phase 6 — Network Packets

| Packet | Direction | Purpose |
|--------|-----------|---------|
| `RunSpecC2S` | C→S | Trigger run for selected / all Spec Cases |
| `ResetSpecC2S` | C→S | Manual circuit reset |
| `CycleSpecCaseC2S` | C→S | Keybind Spec Case cycle |
| `SaveSpecC2S` | C→S | Save full `RedstoneSpec` (from Overview editor) |
| `SaveSpecEntryC2S` | C→S | Save a single spec entry (from Spec Editor) |
| `RemoveSpecEntryC2S` | C→S | Remove an entry at a given pos |
| `SaveBreakpointC2S` | C→S | Save BreakpointSpec config |
| `SaveAutoSpecC2S` | C→S | Save AutoSpec config |
| `TestResultS2C` | S→C | Deliver `TestResult` to client |
| `SpecCaseChangedS2C` | S→C | Active Spec Case changed |
| `BreakpointHitS2C` | S→C | Notify nearby players of breakpoint trigger + SimTime |
| `AutoSpecRecordedS2C` | S→C | AutoSpec committed a Spec Case |

---

## Phase 7 — Keybind & Origin Selection

### 7.1 SelectedOriginTracker (client singleton)

- `selectedOriginPos: BlockPos?` — set on right-click or Spec Marker Tool use of any SpecOrigin.

### 7.2 Cycle Keybinds

Two keybinds: **Cycle Forward** / **Cycle Backward** (default unbound). On press: send `CycleSpecCaseC2S`.

---

## Phase 8 — Publishing & Serialization

### 8.1 JSON Export

Serialize `RedstoneSpec` via Codec → `.minecraft/redstonespecs/<name>.json`.

### 8.2 Datapack Support (v2)

Load from `data/<namespace>/redstonespecs/*.json`.

---

## Phase 9 — Config & Polish

### 9.1 YACL Config (via ModMenu)

- Toggle overlay rendering; opacity, label scale.
- Default lifespan, default phase for new StateSpec entries.

### 9.2 Server Permissions

- Operator level required to run specs, add/remove entries, or trigger recordings.
- Non-ops: view overlays, open editors read-only.

---

## File Layout

```
src/
  main/kotlin/com/breadmoirai/redstonespecs/
    Redstonespecs.kt
    data/
      SimTime.kt
      StateSpec.kt
      StateCondition.kt
      SpecEntry.kt              # sealed base class
      InputSpec.kt
      OutputSpec.kt
      BreakpointSpec.kt
      AutoSpec.kt
      SpecCase.kt
      RedstoneSpec.kt
      TestResult.kt
    block/
      SpecOriginBlock.kt
      SpecOriginBlockEntity.kt
    item/
      SpecOriginItem.kt
      SpecMarkerTool.kt
    runner/
      SpecRunner.kt
      SpecRunnerCoordinator.kt
      AutoSpecRecorder.kt
      SpecSnapshot.kt
    mixin/
      PhaseHookMixin.kt
    network/
      packets/
    config/
      RedstonespecsConfig.kt

  client/kotlin/com/breadmoirai/redstonespecs/client/
    RedstonespecsClient.kt
    input/
      SpecCycleKeybind.kt
      SelectedOriginTracker.kt
    render/
      SpecOriginBoundsRenderer.kt
      SpecOverlayRenderer.kt          # colored frame overlays for all spec entries
      HudOverlayRenderer.kt
    screen/
      SpecOverviewScreen.kt
      SpecEditorScreen.kt
      TestResultScreen.kt
      widget/
        StateSpecStepWidget.kt
        StateSpecGraphWidget.kt
        StateConditionWidget.kt         # tree editor for composed StateCondition
        BlockPickerWidget.kt
        SimTimeWidget.kt
    mixin/
      PickBlockMixin.kt
```

---

## Implementation Order

| # | Milestone | Status |
|---|-----------|--------|
| 1 | Data model — all types serialize/deserialize; SimTime ordering verified | ✅ Done |
| 2 | SpecOrigin block + block entity — placed, stores spec, survives reload, bounding box renders | ✅ Done |
| 3 | Spec Marker Tool — adds/edits/removes entries in SpecOrigin; opens placeholder screens | ✅ Done |
| 4 | Overlay renderer — colored frames over registered positions | ✅ Done |
| 5 | Phase hook mixins — `SpecRunnerCoordinator.onPhase()` fires at correct sub-tick boundaries | ✅ Done |
| 6 | Test runner — drives InputSpec changes, records OutputSpec checks for a hardcoded SpecCase |
| 7 | Breakpoint logic — condition check + tick freeze + HUD notification |
| 8 | AutoSpec recording — rising/falling edge, diff-based StateSpec generation |
| 9 | Network packets — all C2S/S2C packets work end-to-end |
| 10 | Spec Editor Screen — full editor saves entries |
| 11 | Test Result Screen — results displayed after a run |
| 12 | Spec Overview Screen — full run → view results flow |
| 13 | Spec Case keybind + SelectedOriginTracker + HUD overlay |
| 14 | Publishing — JSON export/import end-to-end |
| 15 | Config & polish — ModMenu config, permissions, overlay toggles |
```
