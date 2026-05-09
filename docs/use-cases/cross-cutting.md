---
title: Cross-cutting use-cases
tags: [e2e, integration, regression, use-cases]
summary: End-to-end journeys that span ≥3 subsystems; reference parent UCs from other articles.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Cross-cutting use-cases

These UCs describe full end-to-end flows touching record, persist, network, run, and verify in one motion. They introduce no independent test gaps; coverage points back at the parent UCs in the per-journey articles.

---

### UC-X2X-01 — Singleplayer record, persist, restart, reload, and verify pass

**Actor:** Author (singleplayer)
**Trigger:** Author completes a recording session and later reopens the world to run the spec.
**Preconditions:** A recorder block is placed and configured in a singleplayer world; the spec directory is on disk.
**Outcome:** The spec file written after finalization is loaded without modification after a world restart and produces a passing run result.

**References:**
- UC-REC-04, UC-REC-05 — recording session started and finalized to `.spec.kts`
- UC-PER-01, UC-PER-02, UC-PER-05 — atomic write, scripting-host load, round-trip equality
- UC-RUN-01, UC-RUN-02, UC-RUN-04 — spec selected in runner, executed, result observed

---

### UC-X2X-02 — Dedicated-server record, client confirmation, server persist, second-client run

**Actor:** Author on client A; Player on client B; dedicated server
**Trigger:** Author initiates a recording on a dedicated server; a second client later loads the saved spec via a runner block.
**Preconditions:** Both clients are connected to the same dedicated server; a recorder block and runner block are placed; the spec directory is accessible server-side.
**Outcome:** Client A receives S2C confirmation after finalization; the spec is saved to the server's disk; client B opens the runner, selects the spec, executes it, and observes a passing result.

**References:**
- UC-REC-04, UC-REC-05 — recording started and finalized on the server
- UC-NET-01, UC-NET-03, UC-NET-05 — client screen-state request, S2C confirmation, unauthorized-command rejection
- UC-PER-01, UC-PER-03 — atomic write after finalization; directory scan exposes spec to runner
- UC-RUN-01, UC-RUN-02, UC-RUN-04 — second client selects spec, runs it, observes result

---

### UC-X2X-03 — Hand-authored spec placed in managed root: grid projection, cell edit, save-back

**Actor:** Author (filesystem + in-game)
**Trigger:** Author drops a hand-written `.spec.kts` into a managed root folder, then opens the managed world to inspect and modify the cell.
**Preconditions:** A managed root is registered in `ManagedRootsConfig`; the managed world can be booted; the new spec file passes scripting-host evaluation.
**Outcome:** The spec appears as a cell in the grid; the author edits block state inside the cell; saving back overwrites the original `.spec.kts` on disk; reloading the spec confirms the changes round-trip correctly.

**References:**
- UC-PER-02, UC-PER-04, UC-PER-05 — scripting-host loads the hand-authored file; malformed files are rejected; round-trip equality holds after save-back
- UC-MAN-02, UC-MAN-03, UC-MAN-04 — managed world boots, folder tree scanned, cell placed in grid
- UC-MAN-07, UC-MAN-08 — cell edits saved back to disk; session ends cleanly
- UC-CMD-01, UC-CMD-03 — `/redstonespecs managed` opens the folder UI; root resolved via priority chain

---

### UC-X2X-04 — Kotest spec registered, harness drives runRedstoneSpec, diagnostic recording on failure

**Actor:** Test author (CI / local `:26.1:test` run)
**Trigger:** A new Kotest spec is added to `GametestSentinel` and `:26.1:test` is executed.
**Preconditions:** The spec class is registered in the sentinel's explicit list; `runRedstoneSpec` references a valid `.spec.kts` fixture; the fixture encodes at least one verifiable output condition.
**Outcome:** When the spec passes, the test run reports green. When an output value diverges, the harness captures a diagnostic recording that identifies the failing tick and output position; the Kotest report surfaces the recording path.

**References:**
- UC-GT-01, UC-GT-02, UC-GT-04 — sentinel registration, `runRedstoneSpec` dispatch, diagnostic recording on failure
- UC-REC-05, UC-REC-06 — finalization path and failure recovery mirror the diagnostic capture path
- UC-RUN-02, UC-RUN-05 — replay execution and abort recovery exercised by the harness
- UC-PER-02, UC-PER-05 — scripting host loads the fixture; round-trip fidelity of the recorded diagnostic

---

### UC-X2X-05 — Overwrite-prompt handshake guards re-recording an existing spec

**Actor:** Author (singleplayer or dedicated server)
**Trigger:** Author attempts to finalize a recording whose target filename already exists on disk.
**Preconditions:** A `.spec.kts` file with the intended name exists in the spec directory; the author has completed a new recording session.
**Outcome:** The client receives an overwrite-prompt S2C packet; after the author confirms, the server atomically replaces the old file; the updated spec is immediately available via directory scan and produces a passing run.

**References:**
- UC-REC-05 — finalization triggers the overwrite check before writing
- UC-NET-03, UC-NET-04, UC-NET-05 — S2C confirmation after state mutation; overwrite-prompt handshake; rejection of unauthorized commands
- UC-PER-01, UC-PER-03 — atomic write replaces the old file; directory scan reflects the update
- UC-RUN-01, UC-RUN-02 — runner re-scans the directory and executes the updated spec

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-X2X-01 | Singleplayer record → persist → restart → reload → verify pass | see UC-REC-04, UC-REC-05, UC-PER-01, UC-PER-02, UC-PER-05, UC-RUN-01, UC-RUN-02, UC-RUN-04 | see refs |
| UC-X2X-02 | Dedicated-server record → client confirmation → server persist → second-client run | see UC-REC-04, UC-REC-05, UC-NET-01, UC-NET-03, UC-NET-05, UC-PER-01, UC-PER-03, UC-RUN-01, UC-RUN-02, UC-RUN-04 | see refs |
| UC-X2X-03 | Hand-authored spec → managed root → grid projection → cell edit → save-back | see UC-PER-02, UC-PER-04, UC-PER-05, UC-MAN-02, UC-MAN-03, UC-MAN-04, UC-MAN-07, UC-MAN-08, UC-CMD-01, UC-CMD-03 | see refs |
| UC-X2X-04 | Kotest spec registered → harness drives runRedstoneSpec → diagnostic recording on failure | see UC-GT-01, UC-GT-02, UC-GT-04, UC-REC-05, UC-REC-06, UC-RUN-02, UC-RUN-05, UC-PER-02, UC-PER-05 | see refs |
| UC-X2X-05 | Overwrite-prompt handshake guards re-recording an existing spec | see UC-REC-05, UC-NET-03, UC-NET-04, UC-NET-05, UC-PER-01, UC-PER-03, UC-RUN-01, UC-RUN-02 | see refs |
