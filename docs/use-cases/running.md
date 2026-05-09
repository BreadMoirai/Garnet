---
title: Running use-cases
tags: [runner, replay, verification, ui, use-cases]
summary: Player or author runs a saved spec via the runner block; verification surfaces in the UI.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Running use-cases

The running journey: a saved `.spec.kts` is loaded by the runner block, the spec lambda is replayed against the world, and assertions fire inline. Verification results surface in `RunnerScreen` and the timeline widget.

---

### UC-RUN-01 — Open runner block and select a spec to load

**Actor:** Player
**Trigger:** Player right-clicks a placed `RedstoneSpecRunnerBlock`.
**Preconditions:** The block exists in a `ServerLevel`; at least one `.spec.kts` file is present under `SharedSettings.specSaveDir` on the server. The block entity may or may not have a previously configured spec ID.
**Outcome:** `RunnerScreen` is open on the client, showing the spec picker (`CycleButton`) populated with every available spec path, the currently selected spec's metadata panel (`id`, `bounds`, `lifespan`, `structure`), and action buttons (Place structure, Run, Restore snapshot).

**System interactions:**
- UC-RUN-01.a — `RedstoneSpecRunnerBlock.useWithoutItem` resolves the save directory via `level.server.getWorldPath(LevelResource.ROOT).resolve(SharedSettings.specSaveDir)` and calls `SpecDirectoryScan.list` to enumerate available spec paths.
- UC-RUN-01.b — If the block entity's `isConfigured` is `true`, `SpecPersistence.load` is called with the stored `specId`, and the resulting `RedstoneSpec` is converted to a `RunnerMetaSnapshot` carrying `id`, `boundsX/Y/Z`, `lifespan`, and `structure`.
- UC-RUN-01.c — `ServerPlayNetworking.send` dispatches an `OpenRunnerScreenS2C` packet to the requesting player, carrying `blockPos`, the first available spec path as the default selection, the full `specList`, and the optional `RunnerMetaSnapshot`.
- UC-RUN-01.d — The client opens `RunnerScreen` using the packet data; if `meta` is non-null, the metadata panel renders the four fields; if null, a `"(no spec loaded)"` placeholder is shown instead.
- UC-RUN-01.e — When the player cycles the spec picker, `RunnerScreen` sends a `SetRunnerConfigC2S` packet so the server `SpecBlockEntity` tracks the new selection, and the server responds with a refreshed `OpenRunnerScreenS2C` that calls `RunnerScreen.updateMeta`.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)

---

### UC-RUN-02 — Execute a single replay run

**Actor:** Player
**Trigger:** Player clicks the "Run" button in `RunnerScreen`, sending a `RunnerCommandC2S(RunnerCmd.RUN)` packet.
**Preconditions:** The `SpecBlockEntity` has a configured spec ID; `SpecPersistence.load` can resolve the `.spec.kts` file to a `RedstoneSpec`; the runner block's `origin` and `bounds` are valid for the current `ServerLevel`.
**Outcome:** The spec lambda has executed, all tick-keyed input and assertion callbacks have fired, and the run has either completed without failure (returning a `StateRecording`) or thrown an `AssertionError` capturing every inline failure message.

**System interactions:**
- UC-RUN-02.a — The server-side command handler calls `runRedstoneSpec(level, origin, spec)` as a suspend function within a server coroutine; `SpecSnapshot.capture` snapshots every `BlockState` in the `origin + bounds` region into a `Map<BlockPos, BlockState>`.
- UC-RUN-02.b — `StateRecorder.forSpec` constructs a fresh recorder keyed by a new `UUID`; `StateRecorder.activate` adds it to the global active set so the `setBlock` mixin intercepts changes inside bounds; `snapshot.restore(level)` resets the region to the captured baseline before the lambda runs.
- UC-RUN-02.c — `spec.block` is invoked once with a `SpecRun` receiver; this call registers all tick-keyed closures into `SpecRun.inputActions` and `SpecRun.assertions` (both `TreeMap<SimTime, MutableList<() -> Unit>>`). No world interaction occurs at this point.
- UC-RUN-02.d — The tick loop iterates `0 until spec.lifespan`: for each tick it fires all `START_OF_TICK` entries from `inputActions`, then suspends via `awaitTickEnd()`, then fires all `END_OF_TICK` entries from `assertions`.
- UC-RUN-02.e — If `spec.strict` is `true`, `scanForUnexpectedChanges` walks every declared output position in `SpecRun.outputDeclaredTicks` and appends a `SpecFailure` for any change-tick not declared in the spec.
- UC-RUN-02.f — In the `finally` block, `StateRecorder.deactivate` removes the recorder from the active set and `snapshot.restore(level)` resets the region to its pre-run state regardless of success or failure.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md), [runner/engine-driven-verification.md](../runner/engine-driven-verification.md)

---

### UC-RUN-03 — Replay button input via player-interaction dispatch

**Actor:** Spec runtime (no direct player action)
**Trigger:** The tick loop in `runRedstoneSpec` fires a `START_OF_TICK` input callback registered for a `ButtonBlock` position.
**Preconditions:** The `InputScope` callback was registered by an `input(x, y, z) { at(tick) { press() } }` DSL call; the target position in the live level currently holds a `ButtonBlock` with `POWERED=false`.
**Outcome:** The button transitions to `POWERED=true` via `ButtonBlock.press`, which sets the state, fires neighbor updates, and — critically — schedules the auto-depower tick via `level.scheduleTick`; downstream redstone components receive updates on the correct cadence as recorded.

**System interactions:**
- UC-RUN-03.a — The `InputScope` callback calls `tryApplyAsPlayerInteraction(level, worldPos, current, target)` with the desired target state; the function inspects `current.block`.
- UC-RUN-03.b — When `current.block` is a `ButtonBlock` and the transition is `POWERED=false → POWERED=true`, `ButtonBlock.press(current, level, pos, null)` is called directly; the `null` player argument is accepted by `press` and does not cause a crash.
- UC-RUN-03.c — For the `POWERED=true → POWERED=false` depower, no explicit action is taken — the auto-depower is already scheduled by `press`; when the schedule fires, the recording's later `POWERED=false` entry arrives at an already-correct state and collapses to a no-op via the `target != current` guard.
- UC-RUN-03.d — For all non-`ButtonBlock` positions, `tryApplyAsPlayerInteraction` falls through to `level.setBlock(pos, target, 3)` when `target != current`.

**Invariants:** [runner/player-interaction-dispatch.md](../runner/player-interaction-dispatch.md)
**Edge cases referenced elsewhere:** UC-RUN-05.a (replay timing drift on first input)

---

### UC-RUN-04 — Observe verification result in RunnerScreen and timeline widget

**Actor:** Player
**Trigger:** `runRedstoneSpec` completes (success or failure); the server sends a `RunnerStatusS2C` packet to the player who issued the run command.
**Preconditions:** `RunnerScreen` is open and registered as `RunnerScreen.active`; `ClientRunnerState` has received the latest summary for this runner's `BlockPos`.
**Outcome:** The status line in `RunnerScreen` updates to reflect the run result (`RunnerState.PASS` / `RunnerState.FAIL` / `RunnerState.ERROR`); if a timeline scrubber screen is open, `TimelineSliderWidget` allows the player to scrub through ticks `0` to `lifespan - 1` and inspect per-tick state.

**System interactions:**
- UC-RUN-04.a — On run completion the server-side handler calls `RunnerScreen.active?.pushStatus(state, summary)` if the screen is open; `pushStatus` sets `statusState` and `statusText`, then calls `rebuildWidgets()` so the layout re-renders with the updated status label.
- UC-RUN-04.b — `ClientRunnerState.put(runnerPos, summary)` stores the result string in a `ConcurrentHashMap<BlockPos, String>` keyed by runner position, making it available to any screen that calls `ClientRunnerState.get(runnerPos)` after the packet arrives.
- UC-RUN-04.c — `TimelineSliderWidget` is a subclass of `AbstractSliderButton` parameterized by `lifespan`; `applyValue` maps the slider's `[0.0, 1.0]` double to an integer tick via `(value * (lifespan - 1)).toInt().coerceIn(0, lifespan - 1)` and calls `onTickChanged(tick)`.
- UC-RUN-04.d — `updateMessage` labels the slider "Tick $tick / ${lifespan - 1}" so the current position is always legible without an external label widget.
- UC-RUN-04.e — `RunnerScreen.updateMeta` is called by the packet handler when a `SetRunnerConfigC2S` acknowledgment arrives; it replaces `meta` and calls `rebuildWidgets()`, updating the metadata panel without closing the screen.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md)

---

### UC-RUN-05 — Handle verification failure and abort recovery

**Actor:** Spec runtime / Player
**Trigger:** One or more `OutputScope` assertion callbacks append entries to `SpecRun.failures` during the tick loop, or `scanForUnexpectedChanges` finds undeclared change-ticks after the loop, causing `runRedstoneSpec` to throw `AssertionError`.
**Preconditions:** A run is in progress; at least one inline assertion produced a `SpecFailure` (e.g. `powered shouldBe true` evaluated to `false` at `END_OF_TICK`), or `spec.strict = true` and a declared output changed at an undeclared tick.
**Outcome:** The `AssertionError` message contains every `SpecFailure` rendered as `"FAIL <label> at tick <t>: <message>"`; the world is restored to its pre-run snapshot; the runner block's `SpecBlockEntity` returns to idle; `RunnerScreen` displays the failure summary.

**System interactions:**
- UC-RUN-05.a — `SpecRun.reportFailure(SpecFailure)` appends to `failures: MutableList<SpecFailure>`; callbacks do not throw immediately, allowing all assertions in the current tick to execute before the run terminates.
- UC-RUN-05.b — After the tick loop (and after `scanForUnexpectedChanges` if `strict`), `runRedstoneSpec` checks `run.failures.isNotEmpty()` and throws `AssertionError("assertOutputsMatch failed:\n" + failures.joinToString("\n") { it.render() })`.
- UC-RUN-05.c — The `finally` block in `runRedstoneSpec` unconditionally calls `StateRecorder.deactivate(recorder)` and `snapshot.restore(level)`, guaranteeing the region returns to its baseline state whether the run succeeded, failed, or was interrupted by an unhandled exception.
- UC-RUN-05.d — The server-side runner command handler catches `AssertionError` and maps it to a `RunnerState.FAIL` status packet sent via `RunnerStatusS2C`; the failure message text is truncated if it exceeds the packet's string length cap before transmission.
- UC-RUN-05.e — `StateRecordingStorage.save` is not called on failure; the replay recording is discarded rather than persisted, preventing stale failure recordings from appearing in the timeline scrubber on a subsequent successful run.

**Invariants:** [architecture/recording-pipeline.md](../architecture/recording-pipeline.md), [runner/engine-driven-verification.md](../runner/engine-driven-verification.md)
**Edge cases referenced elsewhere:** UC-RUN-03.a (button dispatch drift on first input)

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-RUN-01 | Open runner block and select a spec to load | — | **GAP** |
| UC-RUN-01.a | `useWithoutItem` resolves save dir and calls `SpecDirectoryScan.list` | — | **GAP** |
| UC-RUN-01.b | If configured, `SpecPersistence.load` builds `RunnerMetaSnapshot` | — | **GAP** |
| UC-RUN-01.c | `ServerPlayNetworking.send` dispatches `OpenRunnerScreenS2C` to player | — | **GAP** |
| UC-RUN-01.d | Client opens `RunnerScreen` with metadata panel or `"(no spec loaded)"` | — | **GAP** |
| UC-RUN-01.e | Cycling picker sends `SetRunnerConfigC2S`; server responds with refreshed `OpenRunnerScreenS2C` | — | **GAP** |
| UC-RUN-02 | Execute a single replay run | `RunRedstoneSpecSmokeTest."runRedstoneSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-RUN-02.a | `SpecSnapshot.capture` snapshots block region before run | `RunRedstoneSpecSmokeTest."runRedstoneSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-RUN-02.b | `StateRecorder.forSpec` + `activate` + `snapshot.restore` sets up run context | `RunRedstoneSpecSmokeTest."runRedstoneSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-RUN-02.c | `spec.block` invoked once to register closures; no world interaction occurs | `SpecRunSchedulerTest."input scope schedules at START_OF_TICK; output at END_OF_TICK"` | covered |
| UC-RUN-02.d | Tick loop fires `inputActions` at `START_OF_TICK` and `assertions` at `END_OF_TICK` | `SpecRunSchedulerTest."input scope schedules at START_OF_TICK; output at END_OF_TICK"` | **GAP-PARTIAL** |
| UC-RUN-02.e | `scanForUnexpectedChanges` appends `SpecFailure` for undeclared change-ticks when `strict = true` | — | **GAP** |
| UC-RUN-02.f | `finally` block deactivates recorder and restores snapshot regardless of outcome | `RunRedstoneSpecSmokeTest."runRedstoneSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-RUN-03 | Replay button input via player-interaction dispatch | — | **GAP** |
| UC-RUN-03.a | `tryApplyAsPlayerInteraction` called with desired target state | — | **GAP** |
| UC-RUN-03.b | `ButtonBlock.press` called for `POWERED=false → true` transition | — | **GAP** |
| UC-RUN-03.c | Depower auto-handled by schedule; no explicit action taken for `true → false` | — | **GAP** |
| UC-RUN-03.d | Non-`ButtonBlock` falls through to `level.setBlock` when `target != current` | — | **GAP** |
| UC-RUN-04 | Observe verification result in RunnerScreen and timeline widget | — | **GAP** |
| UC-RUN-04.a | `RunnerScreen.active?.pushStatus` updates status and calls `rebuildWidgets` | — | **GAP** |
| UC-RUN-04.b | `ClientRunnerState.put` stores result keyed by runner position | — | **GAP** |
| UC-RUN-04.c | `TimelineSliderWidget.applyValue` maps `[0,1]` to tick index via lifespan | — | **GAP** |
| UC-RUN-04.d | `updateMessage` labels slider "Tick $tick / ${lifespan - 1}" | — | **GAP** |
| UC-RUN-04.e | `RunnerScreen.updateMeta` replaces metadata panel without closing screen | — | **GAP** |
| UC-RUN-05 | Handle verification failure and abort recovery | — | **GAP** |
| UC-RUN-05.a | `SpecRun.reportFailure` appends to `failures` list without throwing immediately | — | **GAP** |
| UC-RUN-05.b | After tick loop, non-empty `failures` triggers `AssertionError` with all messages | — | **GAP** |
| UC-RUN-05.c | `finally` block unconditionally deactivates recorder and restores snapshot | `RunRedstoneSpecSmokeTest."runRedstoneSpec completes for a trivial empty spec"` | **GAP-PARTIAL** |
| UC-RUN-05.d | Server handler catches `AssertionError` and sends `RunnerState.FAIL` packet | — | **GAP** |
| UC-RUN-05.e | `StateRecordingStorage.save` not called on failure | — | **GAP** |
