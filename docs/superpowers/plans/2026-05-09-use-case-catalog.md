# Use-Case Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Author `docs/use-cases/` — a layered user-journey + system-interaction catalog of every meaningful scenario in RedstoneSpecs, with a per-article test-coverage matrix mapping each UC to existing tests in `src/test/`, `src/gametest/`, and `src/clientTest/`.

**Architecture:** Eight markdown articles under `docs/use-cases/` (one per journey + cross-cutting), each with parent UC entries (`UC-<PREFIX>-NN`), system-interaction sub-IDs (`.a`–`.f`, capped at six per parent), and a coverage table at the foot. Documentation-only — no production code changes, no new tests, no gradle invocations. Catalog is the foundation for two follow-up sub-projects that will fill GAP / GAP-PARTIAL entries.

**Tech Stack:** Markdown only. Reading: existing Kotlin sources under `src/main/kotlin/`, `src/client/kotlin/`, and existing tests under `src/test/`, `src/gametest/`, `src/clientTest/`. No build tooling.

**Spec:** [docs/superpowers/specs/2026-05-09-use-case-catalog-design.md](../specs/2026-05-09-use-case-catalog-design.md)

---

## Working notes

**Audit-commit anchor.** When the audit pass (Task 9) begins, capture `git rev-parse HEAD` and stamp it as `last_audited_commit:` in every article's frontmatter. This lets sub-projects 2/3 detect drift via `git diff <sha> -- src/test src/gametest src/clientTest`.

**Test-reference notation (closed set):**
- JUnit-style: `ClassName.methodName`
- Kotest single-level: `ClassName."string spec name"`
- Kotest nested: `ClassName."outer" / "should ..."`
- Multiple: up to three comma-separated; `(+N more)` if over.

**Status values (closed set):** `covered`, `GAP-PARTIAL`, `GAP`. Cross-cutting cells use literal text `see UC-XXX-NN`.

**No placeholders.** Every UC entry written must have actor, trigger, preconditions, outcome filled. No "TBD", no "etc."

---

### Task 1: Scaffold `docs/use-cases/` and update CLAUDE.md

**Files:**
- Create: `docs/use-cases/INDEX.md`
- Modify: `CLAUDE.md` (add one row to the "Category index" table)

- [ ] **Step 1: Create `docs/use-cases/INDEX.md`**

```markdown
# Use Cases

End-to-end and per-subsystem use-case catalog with test-coverage audit. Use this folder to find: "what scenarios does this mod support, which are tested, where are the gaps."

Each article lists parent UCs (`UC-<PREFIX>-NN`) describing user-visible journeys, with sub-IDs (`.a`–`.f`) decomposing them into system interactions. A coverage matrix at the foot of each article maps every UC to its existing test (or marks it `GAP` / `GAP-PARTIAL`).

The prefix list is closed: `REC` (recording), `RUN` (running), `PER` (persistence), `NET` (networking), `MAN` (managed worlds), `CMD` (command surface), `GT` (gametest harness). Cross-cutting UCs reuse a parent prefix or, if no single prefix dominates, use `X2X`.

**Tags:** use-cases, scenarios, coverage, audit, test-matrix

## Articles

- [Recording](recording.md) — Author opens recorder block, captures redstone behavior into a spec. Tags: recorder, capture, ui, dsl-emit.
- [Running](running.md) — Player runs a saved spec via runner block; verification surfaces. Tags: runner, replay, verification, ui.
- [Persistence](persistence.md) — `.spec.kts` + `.nbt` save/load, directory scan, sidecar handling. Tags: storage, kts, sidecar.
- [Networking](networking.md) — C2S/S2C payloads, server-authority, origin-pos lookup. Tags: payloads, sync, authority.
- [Managed worlds](managed-worlds.md) — Datapack-driven void dim, grid placement, folder-tree, save-back. Tags: managed, dimensions, grid.
- [Command surface](command.md) — `/redstonespecs managed` dispatcher. Tags: command, dispatch.
- [Gametest harness](gametest-harness.md) — Test infrastructure use-cases: fixtures, sentinels, replay. Tags: gametest, harness, fixtures.
- [Cross-cutting](cross-cutting.md) — End-to-end journeys spanning ≥3 subsystems. Tags: e2e, integration, regression.
```

- [ ] **Step 2: Add row to `CLAUDE.md` "Category index" table**

Insert this row immediately after the `[build/]` row (alphabetical-by-folder order is not enforced; the existing table groups by concern):

```markdown
| [use-cases/](docs/use-cases/INDEX.md) | use-cases, scenarios, coverage, audit, test-matrix | User journeys decomposed into system interactions, with a per-journey test-coverage audit |
```

- [ ] **Step 3: Verify INDEX.md links and CLAUDE.md edit**

Run:
```bash
grep -F "[use-cases/]" CLAUDE.md
test -f docs/use-cases/INDEX.md && echo OK
```
Expected: both commands print a non-empty line / `OK`.

- [ ] **Step 4: Commit**

```bash
git add docs/use-cases/INDEX.md CLAUDE.md
git commit -m "docs(use-cases): scaffold catalog folder and INDEX"
```

---

### Task 2: Draft `recording.md`

**Files:**
- Create: `docs/use-cases/recording.md`

**Sources to read** (informs UC enumeration; do not modify):
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRecorderBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/SpecBlockEntity.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecorder.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecording.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/RecordingDslEmitter.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/event/SubTickPhaseEvents.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderScreen.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/item/SpecMarkerTool.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/item/UndoStack.kt`
- `docs/architecture/recording-pipeline.md` (existing context)

- [ ] **Step 1: Read sources and enumerate parent UCs**

Read each file above. As you read, note candidate parent UCs in scratch — the journey is "Author opens recorder, marks I/O, captures behavior, finalizes spec." Enumerate ~4–6 parent UCs covering: place/open recorder, mark inputs/outputs with marker tool, undo a marker action, start/stop capture, finalize into spec, recover from failure.

- [ ] **Step 2: Write the article skeleton**

Create `docs/use-cases/recording.md` with this frontmatter and outline. The body must be filled in (no placeholders) before commit:

```markdown
---
title: Recording use-cases
tags: [recorder, capture, ui, dsl-emit, use-cases]
summary: Author opens recorder block, marks inputs/outputs, captures redstone behavior, finalizes into a spec file.
last_audited_commit: PENDING
---

# Recording use-cases

The recording journey: an author places a recorder block, marks I/O positions, drives the world through a behavior they want to capture, and finalizes the result into a `.spec.kts` + `.nbt` pair.

## UC-REC-01 — <title>
...

## UC-REC-02 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

The coverage matrix is left empty until Task 9; the `last_audited_commit` value stays as `PENDING`.

- [ ] **Step 3: Author every parent UC and its sub-IDs**

For each parent UC, write a complete entry using the template:

```markdown
### UC-REC-NN — <one-line user-facing title>

**Actor:** Author
**Trigger:** <what initiates this journey>
**Preconditions:** <what must be true before>
**Outcome:** <observable result on success>

**System interactions:**
- UC-REC-NN.a — <interaction>
- UC-REC-NN.b — <interaction>
- ...

**Invariants:** <links to existing docs, if applicable>
**Edge cases referenced elsewhere:** <UC-IDs from other articles, if applicable>
```

Hard cap: six sub-IDs per parent. If a UC needs more, split into two parents.

Every actor / trigger / precondition / outcome line must be a real concrete sentence. No "TBD", no "etc."

- [ ] **Step 4: Verify article structure**

Run:
```bash
grep -c "^### UC-REC-" docs/use-cases/recording.md
grep -E "^### UC-REC-[0-9]+\.[a-z] " docs/use-cases/recording.md && echo "ERROR: sub-IDs must not be top-level headings"
grep "TBD\|TODO" docs/use-cases/recording.md && echo "ERROR: placeholder found"
```
Expected: parent count is 4–6; second and third commands produce no output (no errors).

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/recording.md
git commit -m "docs(use-cases): draft recording journey UCs"
```

---

### Task 3: Draft `running.md`

**Files:**
- Create: `docs/use-cases/running.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/block/RedstoneSpecRunnerBlock.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/runRedstoneSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/SpecSnapshot.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingView.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/StateRecordingStorage.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/runner/PlayerInteractionDispatch.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/SpecRun.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/RedstoneSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/dsl/ConditionEvaluator.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerScreen.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/state/ClientRunnerState.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/widget/TimelineSliderWidget.kt`
- `docs/runner/engine-driven-verification.md`
- `docs/runner/player-interaction-dispatch.md`

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~4–5 parent UCs. The journey covers: select a spec in runner UI, run a single replay, scrub the timeline of a completed run, observe a verification failure, abort mid-run / recover, button-input replay path.

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Running use-cases
tags: [runner, replay, verification, ui, use-cases]
summary: Player or author runs a saved spec via the runner block; verification surfaces in the UI.
last_audited_commit: PENDING
---

# Running use-cases

The running journey: a saved `.spec.kts` is loaded by the runner block, the spec lambda is replayed against the world, and assertions fire inline. Verification results surface in `RunnerScreen` and the timeline widget.

## UC-RUN-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template from Task 2 Step 3. Cap sub-IDs at six. Concrete content only.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-RUN-" docs/use-cases/running.md
grep "TBD\|TODO" docs/use-cases/running.md && echo "ERROR: placeholder found"
```
Expected: parent count is 4–5; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/running.md
git commit -m "docs(use-cases): draft running journey UCs"
```

---

### Task 4: Draft `persistence.md`

**Files:**
- Create: `docs/use-cases/persistence.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/KtsSpecLoader.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecScript.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecPersistence.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/SpecDirectoryScan.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/RecordingSidecar.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/persistence/StructurePersistence.kt`
- `docs/persistence/spec-on-disk-format.md`
- `docs/persistence/kts-script-host.md`
- `docs/persistence/spec-data-model-invariants.md`

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~5–7 parent UCs covering: write `.spec.kts` + `.nbt` atomically, load `.spec.kts` via Kotlin scripting host, scan a spec directory, refuse malformed/malicious script, round-trip emit→write→load→equals, structure (NBT) capture/restore, sidecar drift handling.

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Persistence use-cases
tags: [storage, kts, sidecar, scripting, use-cases]
summary: Save and load `.spec.kts` + `.nbt` pairs; scan spec directories; handle sidecar drift.
last_audited_commit: PENDING
---

# Persistence use-cases

The persistence layer turns in-memory specs into `.spec.kts` + `.nbt` file pairs and back. JSON appears nowhere on disk or wire for spec content (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)).

## UC-PER-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template. Concrete content only; cap at six sub-IDs.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-PER-" docs/use-cases/persistence.md
grep "TBD\|TODO" docs/use-cases/persistence.md && echo "ERROR: placeholder found"
```
Expected: parent count is 5–7; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/persistence.md
git commit -m "docs(use-cases): draft persistence journey UCs"
```

---

### Task 5: Draft `networking.md`

**Files:**
- Create: `docs/use-cases/networking.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/NetworkRegistry.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/Packets.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/network/ClientNetworkHandler.kt`
- `docs/persistence/network-payload-contract.md`
- (Do NOT include `network/managed/*` here; those belong in `managed-worlds.md`.)

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~4–6 parent UCs covering: client opens recorder/runner screen and requests state via C2S, server validates `originPos` lookup, server emits S2C confirmation/state, client handles disconnect mid-handshake, server rejects unauthorized C2S (server-authority).

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Networking use-cases
tags: [payloads, sync, authority, use-cases]
summary: C2S/S2C payloads, server-authority enforcement, origin-pos lookup, confirmation handshakes.
last_audited_commit: PENDING
---

# Networking use-cases

Spec content never crosses the wire as JSON. Payloads carry origin-relative coordinates and rely on the server's `originPos` lookup as the single source of authority (see [persistence/network-payload-contract.md](../persistence/network-payload-contract.md)).

## UC-NET-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template. Cap at six sub-IDs. Concrete content only.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-NET-" docs/use-cases/networking.md
grep "TBD\|TODO" docs/use-cases/networking.md && echo "ERROR: placeholder found"
```
Expected: parent count is 4–6; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/networking.md
git commit -m "docs(use-cases): draft networking journey UCs"
```

---

### Task 6: Draft `managed-worlds.md`

**Files:**
- Create: `docs/use-cases/managed-worlds.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRoot.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedRootsConfig.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedFolderTree.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimRegistry.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedDimLifecycle.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedWorld.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSession.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedServerContext.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCell.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCellSaver.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedNewSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedTeleport.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedSaveNaming.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/GridLayout.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/LoadedSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedNetworkRegistry.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/ManagedPackets.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedClientNetworking.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedIntegratedBoot.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedRootListScreen.kt`
- `src/client/kotlin/com/breadmoirai/redstonespecs/client/managed/ManagedScreen.kt`
- `docs/architecture/managed-redstone-worlds.md`

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~6–8 parent UCs. This is the largest journey. Cover: declare a managed root in config, create the void dim from datapack on world load, lay out the grid, create a new spec cell, save the cell back to its `.spec.kts` on edit, browse the folder tree, teleport to a cell, fall back to single-dim/region mode for first-start or mid-session folders, network-sync managed state to client, handle ungraceful unload.

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Managed worlds use-cases
tags: [managed, dimensions, grid, datapack, use-cases]
summary: Per-folder void-dim workspace via runtime datapack; deterministic grid; per-spec save-back.
last_audited_commit: PENDING
---

# Managed worlds use-cases

A managed root is a folder of `.spec.kts` files projected into a runtime-generated void dimension as a deterministic grid. Each spec gets a cell; edits in the cell save back to the spec file (see [architecture/managed-redstone-worlds.md](../architecture/managed-redstone-worlds.md)).

## UC-MAN-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template. Cap at six sub-IDs. Concrete content only.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-MAN-" docs/use-cases/managed-worlds.md
grep "TBD\|TODO" docs/use-cases/managed-worlds.md && echo "ERROR: placeholder found"
```
Expected: parent count is 6–8; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/managed-worlds.md
git commit -m "docs(use-cases): draft managed-worlds journey UCs"
```

---

### Task 7: Draft `command.md`

**Files:**
- Create: `docs/use-cases/command.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/managed/ManagedCommand.kt`
- `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/managed/ManagedCommandSpec.kt` (existing test surface; informs UCs)

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~3–4 parent UCs covering the `/redstonespecs managed` subcommands actually implemented. Read `ManagedCommand.kt` for the dispatch tree; the gametest spec lists the expected externally-visible behaviors.

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Command-surface use-cases
tags: [command, dispatch, use-cases]
summary: `/redstonespecs managed` subcommand dispatcher and its observable effects.
last_audited_commit: PENDING
---

# Command-surface use-cases

The mod exposes server-side subcommands under `/redstonespecs managed`. Each parent UC below is one subcommand path with its observable outcome.

## UC-CMD-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template. Cap at six sub-IDs. Concrete content only.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-CMD-" docs/use-cases/command.md
grep "TBD\|TODO" docs/use-cases/command.md && echo "ERROR: placeholder found"
```
Expected: parent count is 3–4; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/command.md
git commit -m "docs(use-cases): draft command-surface UCs"
```

---

### Task 8: Draft `gametest-harness.md`

**Files:**
- Create: `docs/use-cases/gametest-harness.md`

**Sources to read:**
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/RedstoneTestSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/RedstoneTestSpecContext.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/KotestLauncher.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/DiagnosticRecorderListener.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/launcher/ResultCollector.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RecordingHolder.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/runner/RunRedstoneSpec.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/server/Suspending.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/server/Structures.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/core/Lifecycle.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/testing/core/Ticks.kt`
- `src/main/kotlin/com/breadmoirai/redstonespecs/gametest/.../GametestSentinel.kt` (read via `find` if path differs)
- `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/SpecTestContext.kt`
- `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/ClientTestSentinel.kt`
- `docs/gametest/kotest-bridge.md`
- `docs/gametest/spec-test-context.md`
- `docs/gametest/unit-vs-gametest-split.md`

- [ ] **Step 1: Read sources and enumerate parent UCs**

Target ~3–4 parent UCs covering: register a new Kotest spec in `GametestSentinel`, drive a spec via `runRedstoneSpec` from a Kotest body, use `SpecTestContext` for client-gametest UI assertions, capture diagnostic recordings on failure.

- [ ] **Step 2: Write the article skeleton**

```markdown
---
title: Gametest-harness use-cases
tags: [gametest, harness, fixtures, kotest, use-cases]
summary: Test infrastructure scenarios: spec registration, runRedstoneSpec dispatch, client-gametest fixtures, diagnostic recording.
last_audited_commit: PENDING
---

# Gametest-harness use-cases

These UCs describe how author-written tests interact with the harness. They are the only UCs whose **actor** is the *test author* rather than a player.

## UC-GT-01 — <title>
...

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
```

- [ ] **Step 3: Author every parent UC and its sub-IDs**

Use the template. Cap at six sub-IDs. Concrete content only.

- [ ] **Step 4: Verify article structure**

```bash
grep -c "^### UC-GT-" docs/use-cases/gametest-harness.md
grep "TBD\|TODO" docs/use-cases/gametest-harness.md && echo "ERROR: placeholder found"
```
Expected: parent count is 3–4; no placeholder output.

- [ ] **Step 5: Commit**

```bash
git add docs/use-cases/gametest-harness.md
git commit -m "docs(use-cases): draft gametest-harness UCs"
```

---

### Task 9: Audit pass — populate every coverage matrix

**Files:**
- Modify: `docs/use-cases/recording.md`, `running.md`, `persistence.md`, `networking.md`, `managed-worlds.md`, `command.md`, `gametest-harness.md`

**Test sources to scan (read-only):**
- `src/test/kotlin/com/breadmoirai/redstonespecs/**` (all files; ~25 unit Kotest specs)
- `src/gametest/kotlin/com/breadmoirai/redstonespecs/test/**` (`GametestSentinel.kt`, `SmokeSpec.kt`, `managed/Managed*Spec.kt`, `ManagedTestSupport.kt`)
- `src/clientTest/kotlin/com/breadmoirai/redstonespecs/test/**` (`ClientTestSentinel.kt`, `RunRedstoneSpecSmokeTest.kt`, `SpecTestContext.kt`)

- [ ] **Step 1: Capture audit-commit anchor**

Run:
```bash
git rev-parse HEAD
```
Record the SHA. This is the value to stamp into every article's `last_audited_commit` field.

- [ ] **Step 2: For each UC sub-ID, identify the production code path**

For every parent UC and every sub-ID across all seven journey articles:
1. From the UC's text, identify the production class / function it describes.
2. `grep -rn "<ClassOrFunction>" src/test src/gametest src/clientTest` for references.
3. Open each matched test file and read the relevant block to confirm it actually exercises the UC's outcome under the UC's preconditions — not merely instantiates the class.
4. Assign one of `covered` / `GAP-PARTIAL` / `GAP`.

`GAP-PARTIAL` rules (apply strictly):
- Test calls into the path but does not assert the UC's defining outcome.
- Test covers golden path only while the UC describes a cross-subsystem-invariant edge.
Otherwise: `GAP` (no test) or `covered` (assertion confirmed).

- [ ] **Step 3: Fill the coverage matrix in each article**

For each article, replace the empty matrix with rows for every parent UC and every sub-ID, in declaration order:

```markdown
## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-REC-01 | <one-line description> | `ClassName.methodName` | covered |
| UC-REC-01.a | <sub-interaction> | — | **GAP** |
| UC-REC-01.b | <sub-interaction> | `ClassName."string spec name"` | **GAP-PARTIAL** |
```

Test-reference rules:
- JUnit-style: `ClassName.methodName`
- Kotest single-level: `ClassName."string spec name"`
- Kotest nested: `ClassName."outer" / "should ..."`
- Multiple covering tests: list up to three, comma-separated; over three, list the most representative and add `(+N more)`.
- `GAP` rows: literal em-dash `—` in the Test column.

- [ ] **Step 4: Stamp `last_audited_commit` in each article's frontmatter**

In all seven journey article frontmatters, replace `last_audited_commit: PENDING` with the SHA from Step 1. Use sed for safety:

```bash
SHA=$(git rev-parse HEAD)
for f in docs/use-cases/recording.md docs/use-cases/running.md docs/use-cases/persistence.md docs/use-cases/networking.md docs/use-cases/managed-worlds.md docs/use-cases/command.md docs/use-cases/gametest-harness.md; do
  sed -i "s/^last_audited_commit: PENDING$/last_audited_commit: $SHA/" "$f"
done
```

- [ ] **Step 5: Verify the audit pass**

Run:
```bash
grep -c "^last_audited_commit: PENDING" docs/use-cases/*.md
grep -h "^last_audited_commit:" docs/use-cases/*.md | sort -u | wc -l
grep -E "^\| UC-(REC|RUN|PER|NET|MAN|CMD|GT)-" docs/use-cases/*.md | wc -l
```
Expected: first command prints `0` for every file (no PENDING left); second prints `1` (all articles share one SHA — `cross-cutting.md` doesn't exist yet so isn't counted); third command's count is non-zero and matches the UC count.

- [ ] **Step 6: Commit**

```bash
git add docs/use-cases/recording.md docs/use-cases/running.md docs/use-cases/persistence.md docs/use-cases/networking.md docs/use-cases/managed-worlds.md docs/use-cases/command.md docs/use-cases/gametest-harness.md
git commit -m "docs(use-cases): audit pass — populate coverage matrices"
```

---

### Task 10: Write `cross-cutting.md`

**Files:**
- Create: `docs/use-cases/cross-cutting.md`

- [ ] **Step 1: Enumerate end-to-end UCs**

Target ~4–5 cross-cutting UCs that span ≥3 subsystems. Candidate journeys:
- Author records a behavior in singleplayer, saves, restarts the world, loads, runs, observes pass.
- Author records on a dedicated server, client receives confirmation, server saves to disk, second client loads via runner.
- Author authors a `.spec.kts` by hand, places it in a managed root, dim regenerates and projects it into the grid, edits cell, save-back overwrites the file.
- Spec authored on commit A, replayed on commit B with refactored mixin — invariant docs (DiodeBlock.FACING etc.) gate behavior.
- Test author registers a new Kotest spec in `GametestSentinel`, runs `:26.1:test`, harness drives `runRedstoneSpec`, diagnostic recording surfaces on failure.

Cross-cutting UCs do **not** introduce new test gaps — their coverage rows reference parent UC IDs from other articles.

- [ ] **Step 2: Write the article**

```markdown
---
title: Cross-cutting use-cases
tags: [e2e, integration, regression, use-cases]
summary: End-to-end journeys that span ≥3 subsystems; reference parent UCs from other articles.
last_audited_commit: <SHA from Task 9 Step 1>
---

# Cross-cutting use-cases

These UCs describe full end-to-end flows touching record, persist, network, run, and verify in one motion. They introduce no independent test gaps; coverage points back at the parent UCs in the per-journey articles.

## UC-X2X-01 — <title>

**Actor:** <Author / Player / Test author>
**Trigger:** <what initiates>
**Preconditions:** <state at start>
**Outcome:** <observable result>

**References:**
- UC-REC-NN, UC-PER-NN, UC-RUN-NN, ...

(no "System interactions" block — the parent UCs already decompose the work)

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-X2X-01 | <description> | see UC-REC-NN, UC-RUN-NN | see refs |
```

Every parent UC referenced in the **References:** line must exist in another article. Status column for cross-cutting rows uses literal text `see refs`.

- [ ] **Step 3: Verify cross-cutting structure**

Run:
```bash
grep -c "^### UC-X2X-" docs/use-cases/cross-cutting.md
grep "TBD\|TODO" docs/use-cases/cross-cutting.md && echo "ERROR: placeholder found"
grep -E "see UC-(REC|RUN|PER|NET|MAN|CMD|GT)-" docs/use-cases/cross-cutting.md | wc -l
```
Expected: parent count is 4–5; no placeholder output; reference count is non-zero.

- [ ] **Step 4: Commit**

```bash
git add docs/use-cases/cross-cutting.md
git commit -m "docs(use-cases): cross-cutting end-to-end UCs"
```

---

### Task 11: Validation pass

**Files:** _read-only inspection across `docs/use-cases/*.md`_

- [ ] **Step 1: Verify every UC ID is unique across all articles**

Run:
```bash
grep -hE "^### UC-[A-Z0-9]+-[0-9]+" docs/use-cases/*.md \
  | sed -E 's/^### (UC-[A-Z0-9]+-[0-9]+).*/\1/' \
  | sort | uniq -d
```
Expected: empty output (no duplicates). If any line appears, rename the duplicate.

- [ ] **Step 2: Verify every test reference resolves to a real file**

For every non-empty Test cell across the seven journey articles, extract `ClassName` and confirm a file matches:

```bash
grep -hoE '`[A-Z][A-Za-z0-9_]+(\.[a-zA-Z_][A-Za-z0-9_]*|\."[^"]+")[^`]*`' docs/use-cases/*.md \
  | sed -E 's/^`([A-Z][A-Za-z0-9_]+).*/\1/' \
  | sort -u \
  | while read cls; do
      hits=$(find src/test src/gametest src/clientTest -name "${cls}.kt" 2>/dev/null | wc -l)
      if [ "$hits" -eq 0 ]; then echo "MISSING CLASS: $cls"; fi
    done
```
Expected: no `MISSING CLASS:` lines. Any miss means a typo in the audit; fix the matrix entry.

- [ ] **Step 3: Verify every cross-cutting reference points at an extant UC**

```bash
referenced=$(grep -hoE 'UC-(REC|RUN|PER|NET|MAN|CMD|GT)-[0-9]+(\.[a-z])?' docs/use-cases/cross-cutting.md | sort -u)
defined=$(grep -hE "^### UC-(REC|RUN|PER|NET|MAN|CMD|GT)-" docs/use-cases/recording.md docs/use-cases/running.md docs/use-cases/persistence.md docs/use-cases/networking.md docs/use-cases/managed-worlds.md docs/use-cases/command.md docs/use-cases/gametest-harness.md \
  | sed -E 's/^### (UC-[A-Z]+-[0-9]+).*/\1/' | sort -u)
for r in $referenced; do
  base=$(echo "$r" | sed -E 's/\.[a-z]$//')
  if ! grep -q "^${base}$" <<< "$defined"; then echo "DANGLING REF: $r"; fi
done
```
Expected: no `DANGLING REF:` lines.

- [ ] **Step 4: Verify `last_audited_commit` is stamped on every journey article**

```bash
grep -L "^last_audited_commit: [0-9a-f]\{7,\}" docs/use-cases/recording.md docs/use-cases/running.md docs/use-cases/persistence.md docs/use-cases/networking.md docs/use-cases/managed-worlds.md docs/use-cases/command.md docs/use-cases/gametest-harness.md docs/use-cases/cross-cutting.md
```
Expected: empty output. Any file listed has missing or `PENDING` stamp; fix it.

- [ ] **Step 5: Verify INDEX.md links resolve**

```bash
grep -oE '\(([a-z-]+\.md)\)' docs/use-cases/INDEX.md | tr -d '()' | while read f; do
  if [ ! -f "docs/use-cases/$f" ]; then echo "BROKEN LINK: $f"; fi
done
```
Expected: no `BROKEN LINK:` output.

- [ ] **Step 6: Final placeholder sweep**

```bash
grep -nE "TBD|TODO|FIXME|PENDING" docs/use-cases/*.md
```
Expected: empty output. Any hit must be resolved before commit.

- [ ] **Step 7: Commit (only if any fix-ups were needed in steps 1–6)**

If validation surfaced fixes:
```bash
git add docs/use-cases/
git commit -m "docs(use-cases): validation-pass fixes"
```

If validation found nothing to fix, skip the commit; the catalog is final as of Task 10's commit.

---

### Task 12: Final inventory + handoff

**Files:** _read-only summary; no edits_

- [ ] **Step 1: Inventory and surface coverage stats**

```bash
echo "=== Articles ==="
ls docs/use-cases/
echo
echo "=== UC counts per article ==="
for f in docs/use-cases/*.md; do
  if [ "$f" != "docs/use-cases/INDEX.md" ]; then
    n=$(grep -c "^### UC-" "$f")
    echo "$f: $n parent UCs"
  fi
done
echo
echo "=== Coverage status totals ==="
echo "covered:     $(grep -c '| covered |' docs/use-cases/*.md | awk -F: '{s+=$2} END {print s}')"
echo "GAP-PARTIAL: $(grep -c '| \*\*GAP-PARTIAL\*\* |' docs/use-cases/*.md | awk -F: '{s+=$2} END {print s}')"
echo "GAP:         $(grep -c '| \*\*GAP\*\* |' docs/use-cases/*.md | awk -F: '{s+=$2} END {print s}')"
```

This produces the input for sub-projects 2 and 3 — the GAP and GAP-PARTIAL totals tell us how many test stubs the next plan must include.

- [ ] **Step 2: Surface to user**

Report to the user:
- The list of journey articles created.
- Total parent UCs and total sub-IDs.
- The three coverage totals (covered / GAP-PARTIAL / GAP).
- The `last_audited_commit` SHA for downstream sub-projects.

This output is the natural launching point for brainstorming sub-project 2 (golden-path test gap-filling).

---

## Self-review record

Spec coverage check (against `docs/superpowers/specs/2026-05-09-use-case-catalog-design.md`):
- Catalog folder structure → Task 1
- INDEX.md and CLAUDE.md row → Task 1
- Per-journey articles (recording / running / persistence / networking / managed-worlds / command / gametest-harness) → Tasks 2–8
- Per-UC entry format with parent + `.letter` sub-IDs → Tasks 2–8 Step 3
- Six-sub-ID cap → Tasks 2–8 Step 3
- Coverage matrix per article → Task 9
- Three-status closed set (`covered` / `GAP-PARTIAL` / `GAP`) → Task 9 Step 2
- Test-reference notation → Task 9 Step 3
- `last_audited_commit` stamping → Task 9 Step 4
- Cross-cutting article + `UC-X2X-NN` prefix → Task 10
- Validation: ID uniqueness, ref resolution, stamps, links, placeholder sweep → Task 11
- Inventory / coverage totals for downstream sub-projects → Task 12

No deferred placeholders. Every code-bearing step shows full content. Naming consistent (`UC-<PREFIX>-NN`, `last_audited_commit`, `covered` / `GAP-PARTIAL` / `GAP`) across all tasks.
