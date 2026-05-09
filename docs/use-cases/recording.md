---
title: Recording use-cases
tags: [recorder, capture, ui, dsl-emit, use-cases]
summary: Author opens recorder block, marks inputs/outputs, captures redstone behavior, finalizes into a spec file.
last_audited_commit: PENDING
---

# Recording use-cases

The recording journey: an author places a recorder block, marks I/O positions, drives the world through a behavior they want to capture, and finalizes the result into a `.spec.kts` + `.nbt` pair.

---

### UC-REC-01 — Place recorder block and open its configuration screen

**Actor:** Author
**Trigger:** Author places a `RedstoneSpecRecorderBlock` in the world and right-clicks it (or right-clicks an existing one).
**Preconditions:** The author holds a `RedstoneSpecRecorderBlock` item and has a server-side `ServerLevel` available.
**Outcome:** The `RecorderScreen` is open on the client, showing the current spec ID, output path, structure ID, and recording state (`idle` or `recording`).

**System interactions:**
- UC-REC-01.a — `RedstoneSpecRecorderBlock.setPlacedBy` derives a default spec ID from the placing player's profile name (`<name>_spec`) and writes it to the `SpecBlockEntity` via `SpecBlockEntity.setSpecId` if the current ID is still the placeholder `"spec"`.
- UC-REC-01.b — `RedstoneSpecRecorderBlock.useWithoutItem` reads `specId`, `specStructure`, and `isRecording` from the `SpecBlockEntity` and sends an `OpenRecorderScreenS2C` packet to the right-clicking player.
- UC-REC-01.c — The client receives the packet and opens `RecorderScreen`, pre-populating the `EditBox` fields (`specIdBox`, `outPathBox`, `structureIdBox`) with the values carried in the packet.
- UC-REC-01.d — Any edit to a field immediately fires `RecorderScreen.sendSetConfig`, which sends a `SetRecorderConfigC2S` packet back to the server so the `SpecBlockEntity` stays in sync with every keystroke.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)
**Edge cases referenced elsewhere:** UC-REC-05.a (open while recording already active)

---

### UC-REC-02 — Mark input and output positions with the spec marker tool

**Actor:** Author
**Trigger:** Author right-clicks a block inside the recorder's bounding region while holding an `InputSpecMarkerItem` or `OutputSpecMarkerItem`.
**Preconditions:** A `SpecBlockEntity` whose bounding region contains the clicked position must be registered in the server-side `SpecBlockEntity` registry (populated via `SpecBlockEntity.findFor`). The clicked block must be within `specBounds` of the recorder origin.
**Outcome:** The clicked position is registered in `SpecBlockEntity.specMarkers` as an `EntryMarker` with kind `INPUT` or `OUTPUT`, an auto-generated label, and a color. Duplicate markers at the same position/kind are silently skipped.

**System interactions:**
- UC-REC-02.a — `SpecMarkerTool.useOn` calls `SpecBlockEntity.findFor(level, hitPos)` to locate the enclosing recorder. If no recorder owns that position, the item falls through with `InteractionResult.PASS`.
- UC-REC-02.b — `SpecMarkerTool.useOn` guards against markers being placed on a `RedstoneSpecRunnerBlock`; only recorder blocks accept new markers.
- UC-REC-02.c — `InputSpecMarkerItem.createMarker` or `OutputSpecMarkerItem.createMarker` constructs an `EntryMarker` with label derived via `nextLabel` (sequential `block_a`, `block_b`, … collision-avoiding against existing labels) and a fixed color (`0xFF4488FF` for inputs, `0xFFFF8800` for outputs).
- UC-REC-02.d — `SpecBlockEntity.addOrUpdateMarker` replaces any existing marker at the same `(pos, kind)` pair or appends a new one, then calls `setChangedAndSync` to persist to NBT and push a block-update packet to all watching clients.
- UC-REC-02.e — `UndoStack.push` records the placed `EntryMarker` keyed by the author's player UUID, capping history at 20 entries per player, so a subsequent undo action can call `UndoStack.pop` and `SpecBlockEntity.removeMarker` to reverse the placement.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)
**Edge cases referenced elsewhere:** UC-REC-05.b (mark attempt when no recorder in range)

---

### UC-REC-03 — Configure bounds and spec metadata before starting a recording

**Actor:** Author
**Trigger:** Author edits the Spec ID, Output Path, or Structure ID fields in `RecorderScreen`, or sends a `SetRecorderConfigC2S` packet directly (e.g. a command-line workflow).
**Preconditions:** `RecorderScreen` is open; `SpecBlockEntity.isRecording` is `false` (configuration changes during an active recording are accepted but take effect only on the next recording).
**Outcome:** `SpecBlockEntity` fields `specId`, `specStructure` (and implicitly `specBounds` if changed via a separate setter call) reflect the author's intent. The block entity is marked changed and synced to all clients.

**System interactions:**
- UC-REC-03.a — `RecorderScreen.sendSetConfig` fires on every keystroke in any `EditBox` (the `setResponder` callback) and sends a `SetRecorderConfigC2S` packet carrying the current values of all three fields.
- UC-REC-03.b — The server-side handler for `SetRecorderConfigC2S` calls `SpecBlockEntity.setSpecId`, `SpecBlockEntity.setStructure`, and `SpecBlockEntity.setSpecBounds` as appropriate, each of which calls `setChangedAndSync`.
- UC-REC-03.c — `SpecBlockEntity.isConfigured` evaluates to `true` only when `specId` is non-blank and all three bounds dimensions are at least 1; `startRecording` will refuse to proceed if this guard fails.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md) — bounds are stored as `Vec3i`; all position math uses origin-relative coordinates with `(0,0,0)` at the recorder block itself.
**Edge cases referenced elsewhere:** UC-REC-05.a (start attempted with blank ID or zero-volume bounds)

---

### UC-REC-04 — Start a recording session

**Actor:** Author (or a redstone signal)
**Trigger:** Author clicks the "Start" button in `RecorderScreen`, sending a `RecorderCommandC2S(RecorderCmd.START)` packet — or a redstone signal powers the `RedstoneSpecRecorderBlock` (neighbor-changed event).
**Preconditions:** `SpecBlockEntity.isConfigured` is `true`; `SpecBlockEntity.isRecording` is `false`; the block entity's `level` is a `ServerLevel`.
**Outcome:** A `StateRecorder` is created and activated in the global `StateRecorder.activeRecorders` set. Every subsequent `setBlock` call inside the bounds is intercepted and recorded. `SpecBlockEntity.isRecording` becomes `true`.

**System interactions:**
- UC-REC-04.a — `SpecBlockEntity.startRecording` validates non-blank `specId`, positive bounds, and server-side level, then calls `StateRecorder.forSpec` to construct a recorder with a fresh `UUID`, the BE's `originPos`, and `specBounds`.
- UC-REC-04.b — `StateRecorder.start` takes an initial snapshot of every block inside the bounding region and stores it as `initialSnapshot` (keyed by origin-relative `BlockPos`), establishing the baseline for change detection.
- UC-REC-04.c — `StateRecorder.activate` adds the recorder to the global `activeRecorders` `ConcurrentHashMap`-backed set, making it visible to the `setBlock` mixin and the `SubTickPhaseEvents.PHASE` listener.
- UC-REC-04.d — `StateRecorder.onPhaseForActiveRecorders` is called on every `SubTickPhaseEvent`, advancing `currentTick` (on `Phase.START_OF_TICK`) and `currentPhase` for all active recorders so that each `BlockStateChange` records the correct `SimTime`.
- UC-REC-04.e — `SpecBlockEntity.setChangedAndSync` is called after activation so the client's `RecorderScreen` (if open) can reflect the new `"recording"` state on next open.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md) — multiple recorders may be active concurrently; the mixin dispatches each `setBlock` to every recorder whose bounds contain the world position.
**Edge cases referenced elsewhere:** UC-REC-05.a (start with blank ID), UC-REC-05.c (redstone-triggered start while screen is open)

---

### UC-REC-05 — Finalize a recording into `.spec.kts`

**Actor:** Author (or a redstone signal)
**Trigger:** Author clicks the "Stop & Emit" button in `RecorderScreen`, sending a `RecorderCommandC2S(RecorderCmd.STOP)` packet — or the redstone signal powering the block goes low (neighbor-changed event).
**Preconditions:** `SpecBlockEntity.isRecording` is `true`; a `StateRecorder` is referenced by `stateRecorder`.
**Outcome:** The in-memory `StateRecording` is converted to `.spec.kts` DSL source text by `RecordingDslEmitter.emit` and written to disk under `SharedSettings.specSaveDir` (or, in managed contexts, to `managedSourcePath`). `SpecBlockEntity.isRecording` becomes `false`.

**System interactions:**
- UC-REC-05.a — `SpecBlockEntity.stopRecordingAndFinalize` calls `StateRecorder.deactivate` to remove the recorder from `activeRecorders`, then calls `rec.toRecording()` to obtain an immutable `StateRecording` snapshot.
- UC-REC-05.b — `specMarkers` is de-duplicated by `(pos, kind)` before being passed to `RecordingDslEmitter.emit`. If the de-duplicated list is empty, no file is written; the recording is silently discarded.
- UC-REC-05.c — `RecordingDslEmitter.emit` walks the `StateRecording`, computes the I/O activity span via `ioActivitySpan`, and emits `input(…) { atStart { … } at(t) { … } }` / `output(…) { atStart { … } at(t) { … } }` DSL blocks. Input setters specialise `setPowered` / `setLit` for known boolean properties; output conditions specialise `powered()` / `lit()`. Unknown properties fall back to `setProp` / `prop`.
- UC-REC-05.d — If no I/O block changed state during the recording span, `RecordingDslEmitter.emit` falls back to `buildEmptySpec`, producing a valid but body-less `redstoneSpec(…) {}` stub rather than an invalid file.
- UC-REC-05.e — The resulting DSL source text is written asynchronously (via `Dispatchers.IO` coroutine on the `SpecBlockEntity`'s `coroutineScope`) using `SpecPersistence.writeSpecKts` for normal worlds, or directly via `managedSourcePath.writeText` for managed-dimension contexts.
- UC-REC-05.f — `SpecBlockEntity.setChangedAndSync` is called after finalization so clients observe the transition back to `"idle"` state.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md), [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)
**Edge cases referenced elsewhere:** UC-REC-06.a (discard without finalizing), UC-REC-06.b (finalization with no markers produces stub)

---

### UC-REC-06 — Recover from or discard a failed recording

**Actor:** Author
**Trigger:** Author clicks the "Discard" button in `RecorderScreen`, sending a `RecorderCommandC2S(RecorderCmd.DISCARD)` packet; or validation inside `startRecording` / `stopRecordingAndFinalize` refuses to proceed.
**Preconditions:** `SpecBlockEntity` exists and the author has the `RecorderScreen` open or is otherwise interacting with the block.
**Outcome:** Any in-progress recording is abandoned without writing to disk, or the user receives no visible file output and the block returns to `"idle"`. Markers are optionally reset via `discardForRerecord` so the author can re-record cleanly.

**System interactions:**
- UC-REC-06.a — The `DISCARD` command handler calls `StateRecorder.deactivate` on the active recorder (if any), sets `stateRecorder = null`, and calls `setChangedAndSync` — no `RecordingDslEmitter.emit` call is made, so no file is written.
- UC-REC-06.b — If `SpecBlockEntity.startRecording` finds `specId.isBlank()`, zero-volume bounds, or a non-`ServerLevel`, it returns `false` and logs a `DEBUG` message; the block stays in `"idle"` and the author must correct the configuration before retrying.
- UC-REC-06.c — If `stopRecordingAndFinalize` finds that the de-duplicated marker list is empty, it skips `RecordingDslEmitter.emit` and `SpecPersistence.writeSpecKts` entirely; no file is created and no exception is thrown — the block silently returns to `"idle"`.
- UC-REC-06.d — `SpecBlockEntity.discardForRerecord` resets `specMarkers` to a de-duplicated placeholder set (one entry per unique `(pos, kind)`) so the author can start a fresh recording without losing the spatial layout of their I/O assignments.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)
**Edge cases referenced elsewhere:** UC-REC-03.c (start guard), UC-REC-05.b (empty-marker finalize)

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
