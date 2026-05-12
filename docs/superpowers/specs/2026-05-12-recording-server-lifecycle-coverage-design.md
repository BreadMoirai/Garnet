---
title: Recording server-lifecycle test coverage
date: 2026-05-12
status: approved
related_docs:
  - docs/use-cases/recording.md
  - docs/architecture/recording-pipeline.md
---

# Recording server-lifecycle test coverage

## Goal

Close the server-side gaps in the recording lifecycle catalog (`docs/use-cases/recording.md`):

- **UC-REC-04** (start) — full
- **UC-REC-05.a / .e / .f** (finalize: deactivate, file write, sync)
- **UC-REC-06.a / .c** (discard, empty-marker no-op)
- **UC-REC-02.c, 02.e, 03.c, 06.b, 06.d** — cheap unit-level rows

Net: ~15 new tests in one new gametest spec file. Updates the coverage matrix in `recording.md` to reflect new coverage.

## Out of scope

| UC rows | Why deferred |
|---|---|
| UC-REC-01 (screen open S2C) | Already covered by `RecorderRunnerNetworkRegistrySpec` UC-NET-01.a; client receive needs ClientSpec. |
| UC-REC-01.c, 01.d, 03.a, 03.b | Client `RecorderScreen` / packet-handler rows — separate ClientSpec plan. |
| UC-REC-02.a, 02.b, 02.d | `SpecMarkerTool.useOn` integration with `findFor` and runner-block guard — needs item-use harness; separate plan. |
| UC-REC-04.d | `onPhaseForActiveRecorders` driven by `SubTickPhaseEvent` — needs deeper mixin/event integration. |

## File layout

One new file:

```
src/gametest/kotlin/com/breadmoirai/redstonespecs/test/recorder/RecordingLifecycleSpec.kt
```

Style mirrors `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/network/RecorderRunnerNetworkRegistrySpec.kt`:

- `class RecordingLifecycleSpec : RedstoneTestSpec({ ... })`
- Each `test("UC-REC-XX.y: …")` name embeds the UC ID for traceability.
- Uses existing helpers: `withTempRoot`, `placeRecorderBE`, `onServer`, `makeMockServerPlayer`, `drainPayloads`.

Register the new class in `GametestSentinel.runAll`'s explicit specs list (autoscan is off — see memory note).

## Test list

| # | UC | Test name (abbreviated) | Layer |
|---|----|-------------------------|-------|
| 1 | 03.c | `isConfigured returns false for blank id` | unit |
| 2 | 03.c | `isConfigured returns false for any zero-dim bound` | unit |
| 3 | 06.b | `startRecording returns false for blank specId` | onServer |
| 4 | 06.b | `startRecording returns false for zero-volume bounds` | onServer |
| 5 | 04.a / 04.c | `startRecording happy path: returns true, isRecording=true, recorder added to StateRecorder.activeRecorders()` | onServer |
| 6 | 04.b | `StateRecorder.start captures initial snapshot keyed by origin-relative BlockPos` | onServer |
| 7 | 05.a | `stopRecordingAndFinalize deactivates recorder, clears stateRecorder, isRecording=false` | onServer |
| 8 | 05.e | `stopRecordingAndFinalize with markers writes .spec.kts under SharedSettings.specSaveDir` | onServer + async poll |
| 9 | 05.e | `stopRecordingAndFinalize with managedSourcePath set writes to that path instead of saveDir` | onServer + async poll |
| 10 | 05.f / 06.a | `DISCARD command clears stateRecorder, isRecording=false, no file written` | onServer |
| 11 | 06.c | `stopRecordingAndFinalize with no markers writes no file but still clears recorder` | onServer |
| 12 | 02.c | `InputSpecMarkerItem.createMarker yields block_a then block_b with color 0xFF4488FF` | unit |
| 13 | 02.c | `OutputSpecMarkerItem.createMarker uses color 0xFFFF8800` | unit |
| 14 | 02.e | `UndoStack push then pop returns the marker; cap at 20 entries per UUID` | unit |
| 15 | 06.d | `discardForRerecord collapses duplicate (pos,kind) entries to one each` | unit |

## Design notes

### Async file-write handling (#8, #9)

`SpecBlockEntity.stopRecordingAndFinalize` launches the file write on `coroutineScope.launch(Dispatchers.IO)`. Tests must wait for completion before asserting.

Approach: small inline poll-with-timeout helper at the top of the spec file:

```kotlin
private suspend fun awaitFile(path: java.nio.file.Path, timeoutMs: Long = 2000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (path.toFile().exists()) return
        kotlinx.coroutines.delay(20)
    }
    error("file did not appear within ${timeoutMs}ms: $path")
}
```

Not promoted to a shared helper unless a second spec needs it.

### Discard test (#10)

Drives `DISCARD` via `handleRecorderCommand(server, player, RecorderCommandC2S(originPos, RecorderCmd.DISCARD))` — same entry point exercised by `RecorderRunnerNetworkRegistrySpec`. Pre-condition: a recording must already be active (call `startRecording()` first).

Assert: `be.isRecording == false`, `StateRecorder.activeRecorders()` no longer contains the recorder, no file appears in `SharedSettings.specSaveDir` after a brief wait.

### Save-dir isolation

Every server-touching test runs inside `withTempRoot("rec-uc<id>") { ... }` so writes land in a unique temp directory and don't bleed between tests.

### Marker-helper unit tests (#12, #13)

`InputSpecMarkerItem.createMarker` and `OutputSpecMarkerItem.createMarker` take a `BlockPos` and a "labels-already-used" set. They are pure functions of their inputs; verify in plain Kotest blocks (no `onServer`).

### UndoStack test (#14)

`UndoStack` is keyed by player UUID with a per-key cap of 20. Verify push/pop round-trip and that the 21st push evicts the oldest.

## Coverage-matrix update

After tests land, edit the matrix at the foot of `docs/use-cases/recording.md`:

- Flip the targeted rows from `**GAP**` to either `covered` or `**GAP-PARTIAL**` (when the test exercises one of several invariants in the row).
- Test reference column: full `RecordingLifecycleSpec."UC-REC-XX.y: …"` form, matching the existing convention.

## Risks / unknowns

- **Test #6 (initial snapshot)** depends on placing a known non-air block inside the recorder bounds before calling `startRecording`. `placeRecorderBE` places the recorder; the snapshot then includes whatever blocks the test put in adjacent positions. Need to call `level.setBlock(...)` for one cell of the bounds and assert the snapshot reflects it. Straightforward but worth calling out.
- **Test #5** depends on `StateRecorder.activeRecorders()` exposing the live set. It does (line 97 of `StateRecorder.kt`).
- **Polling timeout (2s)** is generous for an `IO`-dispatched single-file write; if flaky on CI, bump or switch to a deterministic seam.
