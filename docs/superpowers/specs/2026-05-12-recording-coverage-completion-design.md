---
title: Recording use-case coverage completion
tags: [testing, use-cases, recorder, coverage]
summary: Close the remaining UC-REC gaps with two test additions — one in clientTest (recorder screen), one in gametest (marker tool + config handler + phase event).
---

# Recording use-case coverage completion

## Goal

Close the remaining gaps in `docs/use-cases/recording.md` left after `RecordingLifecycleSpec` landed. All work is test-only; no production-code changes are expected. Two plans, organised by sourceset.

## Gaps targeted

From the recording coverage matrix:

| UC | Slice | Disposition |
|---|---|---|
| UC-REC-01.b | A (clientTest) | New test |
| UC-REC-01.c | A | New test |
| UC-REC-01.d | A | New test |
| UC-REC-02.a | B (gametest) | New test |
| UC-REC-02.b | B | New test |
| UC-REC-02.d | B | New test |
| UC-REC-03.a | A | Covered by 01.d test (one assertion) |
| UC-REC-03.b | B | New test |
| UC-REC-04.d | B | New test (append to `RecordingLifecycleSpec`) |
| UC-REC-04.e | — | Mark covered-indirectly (see below) |
| UC-REC-05.f | — | Mark covered-indirectly (see below) |

**Covered-indirectly rows:** UC-REC-04.e and UC-REC-05.f assert "`setChangedAndSync` is called after activation/finalization". The behavioural effect — clients observe the new state — is already implied by `UC-REC-04.a/c` and `UC-REC-05.a` succeeding, since both read BE state post-call. A dedicated assertion would require either mocking the sync mechanism or reaching into chunk-dirty internals; neither is worth the contrivance. Matrix entries updated to reference the indirect coverage with a one-line note.

## Plan 1 — clientTest sourceset

**New file:** `src/clientTest/kotlin/com/breadmoirai/garnet/test/RecorderScreenSpec.kt`

Base class: `ClientSpec`. Style mirrors `ClientNetworkSpec.kt`. Register in `ClientTestSentinel` (per `feedback_kotest_specs_must_be_registered`).

**Tests:**

1. **UC-REC-01.b/c — `openScreenFor` opens `RecorderScreen` with EditBoxes pre-populated**
   - On server: place recorder, configure `specId`, `specStructure`, `specBounds`; call `GarnetRecorderBlock.openScreenFor(player, be)`.
   - `waitForClientScreen(RecorderScreen::class.java)`.
   - Assert `specIdBox.value`, `outPathBox.value`, `structureIdBox.value` equal the values configured server-side.
   - Note: existing `ClientNetworkSpec` test "UC-NET-01.c" already asserts the screen opens with the right `originPos`; this new test extends to the three EditBox fields, which is what UC-REC-01.b/c specifically claim.

2. **UC-REC-01.d / 03.a — keystroke fires `SetRecorderConfigC2S`**
   - Open screen via same path as test 1.
   - `drainClientPayloads()` (clear queue).
   - On the test thread (via `FabricTestThreadPump.runOnTestThread`), call `specIdBox.value = "edited_id"` — relies on `setValue` firing the responder synchronously (per `feedback_editbox_responder_sync`; do not call `onChange` manually).
   - Drain payloads; filter to `SetRecorderConfigC2S`; assert exactly one and `specId == "edited_id"`.
   - Repeat the same flow with a single combined test that also mutates `outPathBox` and `structureIdBox`, asserting one payload per keystroke and that the latest payload carries all three current field values.

**Key constraints from memory:**
- `setValue` fires responder synchronously — don't double-fire.
- Use `onServer { … }` / `onClient { … }` / `FabricTestThreadPump.runOnTestThread` for thread hops.
- Register the spec in `ClientTestSentinel`.
- New spec base must be `ClientSpec`, not `GarnetTestSpec`.

**Docs to update:**
- `docs/use-cases/recording.md` — rows UC-REC-01.b/c/d and UC-REC-03.a now point to `RecorderScreenSpec.…`. Update `last_audited_commit`.

## Plan 2 — gametest sourceset

**New file:** `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/MarkerToolSpec.kt`
**Modified file:** `src/gametest/kotlin/com/breadmoirai/garnet/test/recorder/RecordingLifecycleSpec.kt`

Base class for new spec: `GarnetTestSpec`. Register in `GametestSentinel` (per `feedback_kotest_specs_must_be_registered`).

**Tests in `MarkerToolSpec`:**

1. **UC-REC-02.a — `useOn` outside any recorder's bounds returns `PASS`**
   - Build a `UseOnContext` (or directly call `useOn`) with a hit position not enclosed by any registered `SpecBlockEntity`.
   - Assert `InteractionResult.PASS` and no markers added anywhere.

2. **UC-REC-02.b — guard rejects markers on runner blocks**
   - `setBlock` a `GarnetRunnerBlock` at a fresh position; obtain its `SpecBlockEntity`; configure `specBounds` so `findFor` resolves a hit inside its region.
   - Call `useOn` with hit pos inside those bounds → assert `InteractionResult.PASS`, runner's `specMarkers` stays empty.
   - Use `ModRegistries.INPUT_SPEC_MARKER_ITEM` (per `feedback_item_construction_in_tests`).

3. **UC-REC-02.d — `addOrUpdateMarker` replaces same `(pos, kind)`, appends different**
   - Two `addOrUpdateMarker` calls at the same `(relPos, INPUT)` with different labels/colors → `specMarkers` size stays 1, second entry retained.
   - Add a second marker at the same `pos` but `OUTPUT` kind → size becomes 2.
   - Add a third at a different `pos` → size becomes 3.

4. **UC-REC-03.b — server `SetRecorderConfigC2S` handler applies all three fields**
   - Place recorder; obtain BE.
   - Invoke the server-side handler for `SetRecorderConfigC2S` (the same path the C2S receiver registers in network setup) with a payload carrying `specId`, `specStructure`, and bounds.
   - Assert each of `be.specId`, `be.specStructure`, and `be.specBounds` reflect the payload, and `be.isConfigured` is `true`.
   - Run body wrapped in `withContext(McDispatchers.Server)` (per `feedback_redstonetestspec_server_thread`).

**Test appended to `RecordingLifecycleSpec`:**

5. **UC-REC-04.d — `onPhaseForActiveRecorders` advances tick state**
   - Start a recording so a `StateRecorder` is in `activeRecorders`.
   - Call `StateRecorder.onPhaseForActiveRecorders(level, Phase.START_OF_TICK)` → assert `currentTick` incremented by 1 and `currentPhase == START_OF_TICK`.
   - Call with another phase value → assert `currentPhase` updated, `currentTick` unchanged.
   - Stop the recording in a `finally` (or end-of-test cleanup) so global state stays clean for other tests.

**Docs to update:**
- `docs/use-cases/recording.md` — rows UC-REC-02.a/b/d, UC-REC-03.b, UC-REC-04.d now point to the new tests; UC-REC-04.e and UC-REC-05.f gain a note "covered indirectly via UC-REC-04.a/c and UC-REC-05.a respectively (BE state readable post-call implies sync was performed)". Update `last_audited_commit`.

## Verification

For each plan:
- Run `cmd.exe /c "./gradlew.bat :26.1:clientClasses :26.1:classes :26.1:gametestClasses :26.1:clientTestClasses :26.1:testClasses"` (per `feedback_build_command`) to confirm compilation across all five sourcesets.
- Run the relevant test sourceset:
  - Plan 1: client test launch (Kotest via `ClientTestSentinel`).
  - Plan 2: `cmd.exe /c "./gradlew.bat :26.1:test"` for the gametest Kotest run (per `feedback_kotest_test_filter` — read the XML report rather than relying on `--tests`).
- Verify the new tests appear in the report and pass; verify the recording coverage matrix is updated and `last_audited_commit` matches the commit landing the tests.

## Out of scope

- Production-code changes. If any test surfaces a bug, file it separately and continue.
- New test infrastructure. Use existing helpers (`ClientSpec`, `onServer`, `FabricTestThreadPump`, `GarnetTestSpec`, `McDispatchers.Server`).
- The covered-indirectly rows (UC-REC-04.e, UC-REC-05.f) — explicitly deferred.

## Execution

Per project workflow (per `feedback_main_branch_workflow`, `feedback_subagent_execution`):
- Both plans execute on `main`, no feature branches.
- Use `superpowers:subagent-driven-development` to run each plan.
- Land Plan 1, then Plan 2 (independent — order doesn't strictly matter, but client tests are smaller; ship that first).
