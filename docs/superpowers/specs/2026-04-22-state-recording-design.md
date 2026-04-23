# State Recording System — Design Spec

**Date:** 2026-04-22  
**Scope:** v1 — block state change recording with sub-tick precision  
**Out of scope:** replay UI, entity state (v2+)

---

## Overview

A foundational system that records all block state changes within a spec's bounds during a spec run or AutoSpec session. Captures the exact sub-tick phase and global update order within each tick. Replaces the ad-hoc state capture/check logic in `AutoSpecRecorder` and `SpecRunner` with queries against the recording.

---

## Core Data Model

```kotlin
data class PropertyDiff(val name: String, val to: String)

data class BlockStateChange(
    val pos: BlockPos,               // local to bounds: (0,0,0) = bounds min corner, extends +x/+y/+z
    val simTime: SimTime,
    val toBlock: ResourceLocation?,  // null if block type unchanged
    val diffs: List<PropertyDiff>,   // only changed properties
)

data class StateRecording(
    val specId: UUID,
    val timestamp: Long,
    val initialSnapshot: Map<BlockPos, BlockState>,  // keyed by local pos (bounds min corner = origin)
    val changes: List<BlockStateChange>,             // ordered by simTime naturally
)
```

`SimTime.order` is a monotonically increasing counter, reset at `START_OF_TICK`, incremented for each in-bounds `Level.setBlock` call where the state actually changed. It reflects relative update order among recorded changes within a tick.

A typical Redstone state change (same block, one property flipped) stores: `pos`, `simTime`, `toBlock=null`, one `PropertyDiff`. The initial block type is recoverable from `initialSnapshot`.

---

## Query API — `StateRecordingView`

All consumers query through a view class, never touching `StateRecording` directly. The view encapsulates all reconstruction logic (replaying diffs forward from `initialSnapshot`).

```kotlin
class StateRecordingView(val recording: StateRecording) {
    fun stateAt(pos: BlockPos, simTime: SimTime): BlockState
    fun changesAt(pos: BlockPos): List<BlockStateChange>
    fun changesInPhase(tick: Int, phase: Phase): List<BlockStateChange>
    fun changesAt(pos: BlockPos, tick: Int, phase: Phase): List<BlockStateChange>
}
```

All positions use a local coordinate space where `(0, 0, 0)` is the bounds min corner and the box extends in the `+x`, `+y`, `+z` directions. The `SpecOriginBlockEntity` position plays no role in this space — it is not the origin and is not factored into any local position. To convert a world position to local: `localPos = worldPos - boundsMin`.

`stateAt` returns the `to` state of the last `BlockStateChange` at `pos` where `change.simTime <= simTime`, falling back to `initialSnapshot` if no changes precede that time.

---

## Mixin — `LevelSetBlockMixin`

Single injection on `Level.setBlock`. On each call:

1. Read `BlockState` at `pos` before the call (`from`)
2. Invoke original method
3. Read `BlockState` at `pos` after (`to`)
4. If `StateRecorder` not active or `pos` not within bounds (O(1) AABB check): skip
5. If `from == to`: skip
6. Increment tick order counter (reflects relative update order among in-bounds changes)
   - Compute `toBlock` (null if block type unchanged)
   - Compute `PropertyDiff` list (only changed properties, using `captureBlockStateProps()`)
   - Append `BlockStateChange` to recorder

---

## `StateRecorder`

Owned by `SpecRunnerCoordinator`. One active instance at a time.

```kotlin
class StateRecorder(val bounds: AABB, val specId: UUID) {
    var tickOrder: Int = 0
    var currentPhase: Phase = Phase.START_OF_TICK
    val changes: MutableList<BlockStateChange> = mutableListOf()

    fun onTickStart() { tickOrder = 0 }
    fun onPhaseStart(phase: Phase) { currentPhase = phase }
    fun record(pos, toBlock, diffs) { ... }
    fun toRecording(initialSnapshot, timestamp): StateRecording
}
```

---

## Lifecycle Integration

`SpecRunnerCoordinator` always owns the single active `StateRecorder`. This includes AutoSpec sessions — `AutoSpecRecorder` signals the coordinator to activate/deactivate the recorder rather than owning one itself. This ensures `onTickStart` and `onPhaseStart` are always called in one place.

| Event | Action |
|---|---|
| `SpecRunnerCoordinator.startRun()` | Snapshot all blocks in bounds → `initialSnapshot`; create and activate `StateRecorder` |
| `SubTickPhaseEvents.START_OF_TICK` | `recorder.onTickStart()` |
| Each phase event | `recorder.onPhaseStart(phase)` |
| `SpecRunnerCoordinator.finishRun()` | `recorder.toRecording(initialSnapshot, now)`, persist via `StateRecordingStorage`, deactivate |
| `AutoSpecRecorder.start()` (via coordinator) | Snapshot all blocks in bounds → `initialSnapshot`; create and activate `StateRecorder` |
| `AutoSpecRecorder.commit()` (via coordinator) | `recorder.toRecording(initialSnapshot, now)`, persist, deactivate |

---

## Persistence — `StateRecordingStorage`

Files stored at: `world/data/redstonespecs/<specUUID>.dat`

Uses `NbtIo` (same as player data). Each spec run overwrites the previous recording for that spec. AutoSpec sessions write to the same file keyed by `specId`.

```kotlin
object StateRecordingStorage {
    fun save(level: ServerLevel, recording: StateRecording)
    fun load(level: ServerLevel, specId: UUID): StateRecording?
    fun delete(level: ServerLevel, specId: UUID)
}
```

**NBT schema:**
```
{
  "specId": <uuid string>,
  "timestamp": <long>,
  "initialSnapshot": [ { "pos": [x,y,z], "state": <blockstate string> }, ... ],  // pos local to bounds (min corner = 0,0,0)
  "changes": [
    {
      "pos": [x, y, z],              // local: (0,0,0) = bounds min corner, extends +x/+y/+z
      "tick": <int>,
      "phase": <string>,
      "order": <int>,
      "toBlock": <resource location | absent>,
      "diffs": [ { "name": <string>, "to": <string> }, ... ]
    },
    ...
  ]
}
```

---

## Replacing Existing Logic

### `AutoSpecRecorder`

- `start()` — no longer snapshots marked positions; `StateRecorder` handles all capture via Mixin
- `onPhase()` — removed (no more manual diffs per phase)
- `commit()` — queries `StateRecordingView.changesAt(worldPos)` for each marked input/output position, builds `SpecCase` entries from results

### `SpecRunner`

- `checkOutputsAt(simTime)` — queries `StateRecordingView.stateAt(pos, simTime)` instead of `level.getBlockState(pos)`
- `applyInputsAt()` — unchanged; inputs still write to live world

### `ConditionEvaluator`

Gains a new overload for recording-based evaluation:

```kotlin
fun evaluateCondition(condition: StateCondition, view: StateRecordingView, worldPos: BlockPos, simTime: SimTime): Boolean
```

Existing `evaluateCondition(condition, level, worldPos)` is retained for non-recording contexts.

---

## What Is Not Changed

- `SpecSnapshot` — still used for circuit backup/restore around spec runs; `initialSnapshot` in `StateRecording` is a separate capture of the same data
- `SimTime` data class — `order` field already exists; semantics clarified to global-per-tick
- Network layer — recording is server-side only in v1; no new S2C payloads
- Replay UI — out of scope for v1
