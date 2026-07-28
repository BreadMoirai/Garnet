---
title: Use-case catalog and test-coverage audit (sub-project 1 of 3)
date: 2026-05-09
status: design
sub_project_of: comprehensive use-cases + tests for garnet
---

# Use-case catalog and test-coverage audit

## Purpose

Produce a single canonical, browsable catalog of every meaningful user journey
and system interaction in the garnet mod, audited against the existing
test suites and annotated with coverage status. The catalog is the foundation
for the next two sub-projects (gap-filling tests for golden paths, then for
edge cases) — both will reference UC IDs declared here.

This sub-project produces **documentation only**. No production code is
modified. No tests are written. No gradle is invoked.

## Decomposition context

This is sub-project 1 of 3. The other two are out of scope for this spec:

- **Sub-project 2** — Fill gametest gaps for golden-path UCs marked `GAP` /
  `GAP-PARTIAL` in this catalog.
- **Sub-project 3** — Fill gametest gaps for cross-subsystem-invariant edge
  cases.

Each follow-up sub-project will be brainstormed and planned separately and
will reference UC IDs from this catalog as fixed inputs.

## Deliverables

1. New top-level docs category at `docs/use-cases/` containing:
   - `INDEX.md` — index of journey articles
   - `recording.md` — `UC-REC-*`
   - `running.md` — `UC-RUN-*`
   - `persistence.md` — `UC-PER-*`
   - `networking.md` — `UC-NET-*`
   - `managed-worlds.md` — `UC-MAN-*`
   - `command.md` — `UC-CMD-*`
   - `gametest-harness.md` — `UC-GT-*`
   - `cross-cutting.md` — end-to-end UCs that reference parent IDs from the
     other articles
2. One new row in `CLAUDE.md`'s **Category index** table pointing at the new
   folder.
3. Each journey article frontmatter line: `last_audited_commit: <sha>` so
   downstream sub-projects can detect catalog drift.

No other files are created or modified.

## Catalog structure

### Coverage scope

Per the brainstorming decisions, the catalog covers:

- **Golden paths** — every supported user journey end-to-end.
- **User-observable failures + recovery** — anything a user could file a
  bug for (load failure, save during shutdown, mid-run abort, network
  disconnect during sync).
- **Cross-subsystem invariants** — boundary conditions that tests guard
  against even if users wouldn't notice immediately
  (origin-pos lookup miss, FACING direction handling, server-authority
  bypasses, payload-handshake misses).

Explicitly **out of scope** for this catalog (deferred or never):

- Concurrency / lifecycle race UCs (save-during-record, dim-unload-during-run,
  world-close-during-emit).
- Pure-internal invariant violations with no observable surface.

### ID scheme

Two-level: parent UC ID `UC-<PREFIX>-NN`, system-interaction child IDs
appended as `.a`, `.b`, … Sub-IDs are capped at six per parent; if a UC needs
more, split the parent.

Prefix list (closed set):

| Prefix | Domain |
|---|---|
| `UC-REC` | Recording: capture / emit pipeline |
| `UC-RUN` | Running: replay / verification |
| `UC-PER` | Persistence: `.spec.kts` + `.nbt` save/load, scan, sidecar |
| `UC-NET` | Networking: payloads, server-authority, handshakes |
| `UC-MAN` | Managed worlds: void dim, grid, folder-tree, save-back |
| `UC-CMD` | `/garnet managed` command surface |
| `UC-GT` | Gametest harness: fixtures, sentinels, replay infrastructure |

Cross-cutting UCs reuse a parent prefix where dominant; if no single prefix
dominates they live in `cross-cutting.md` and use `UC-X2X-NN` (the only
extension to the prefix list).

### Per-UC entry format

Each parent UC entry follows this template:

```markdown
### UC-<PREFIX>-NN — <one-line user-facing title>

**Actor:** Author / Player / Server-op / Test runner
**Trigger:** What initiates the journey
**Preconditions:** What must be true before
**Outcome:** Observable result on success

**System interactions:**
- UC-<PREFIX>-NN.a — <interaction>
- UC-<PREFIX>-NN.b — <interaction>
- …

**Invariants:** links to existing docs
**Edge cases referenced elsewhere:** UC-<other>-MM, …
```

Cross-cutting UCs omit "System interactions" (they reference parent UC IDs
from other articles) and add an explicit **References:** line.

### Per-article coverage table

At the bottom of every journey article:

```markdown
## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-REC-03 | Author finalizes recording | `RecordingSidecarTest.roundTrip` | covered |
| UC-REC-03.a | Finalize C2S payload | — | **GAP** |
| UC-REC-03.b | originPos validation | `ManagedNetworkRegistrySpec."rejects unknown origin"` | covered |
| UC-REC-03.c | DSL emit | `RecordingDslEmitterTest.emitsInputsAndOutputs` | covered |
| UC-REC-03.e | Atomic write + sidecar | `RecordingSidecarTest.atomicWrite` | **GAP-PARTIAL** |
```

Status values (closed set):

- `covered` — at least one existing test asserts the UC's outcome under its
  preconditions.
- `GAP-PARTIAL` — a test touches the path but skips the assertion that
  defines the UC, or covers only the golden path while the cross-subsystem
  edge is unguarded.
- `GAP` — no test exercises the path.

Test-reference notation:

- JUnit-style: `ClassName.methodName`
- Kotest `StringSpec` / single-level: `ClassName."string spec name"`
- Kotest nested: `ClassName."outer context" / "should ..."`
- Multiple covering tests: list up to three, comma-separated. More than
  three: list the most representative and append `(+N more)`.

Cross-cutting UC coverage reads `see UC-XXX-NN` rather than naming a test —
cross-cutting UCs introduce no independent test gaps.

## Estimated scope

Approximate counts to scope the implementation plan; exact numbers will
emerge during the read pass.

| Journey article | Parent UCs | Sub-IDs |
|---|---|---|
| recording.md | 4–6 | ~20 |
| running.md | 4–5 | ~18 |
| persistence.md | 5–7 | ~20 |
| networking.md | 4–6 | ~16 |
| managed-worlds.md | 6–8 | ~25 |
| command.md | 3–4 | ~10 |
| gametest-harness.md | 3–4 | ~10 |
| cross-cutting.md | 4–5 | (refs only) |

Approximate total: **~33–45 parent UCs, ~120 sub-IDs** across all journeys.

## Audit methodology

For each candidate UC sub-ID:

1. Identify the production code path that implements it (file + class /
   function).
2. `grep` test sources (`src/test/`, `src/gametest/`, `src/clientTest/`) for
   references to that class, mixin target, payload type, or
   `garnetSpec("…")` DSL id.
3. Read each matched test in source to confirm it actually exercises the UC's
   behavior, not just touches the same class.
4. Assign one of `covered` / `GAP-PARTIAL` / `GAP` per the definitions above.

The audit reads test source code to map intent. It does **not** run any
tests — verifying that existing tests pass is left to CI / the
`clientClasses classes gametestClasses clientTestClasses testClasses`
verification path documented in `docs/build/local-verification-commands.md`.

## Source-file map per journey

The implementation plan's read pass will visit, at minimum:

| Article | Production sources to read |
|---|---|
| recording.md | `block/GarnetRecorderBlock*`, `runner/StateRecorder.kt`, `runner/RecordingDslEmitter.kt`, `event/SubTickPhaseEvents.kt`, `client/screen/recorder*`, `network/*Recording*` |
| running.md | `block/GarnetRunnerBlock*`, `runner/runGarnetSpec.kt`, `runner/StateRecording*View*`, `dsl/SpecRun*`, `client/screen/runner*` |
| persistence.md | `persistence/KtsSpecLoader*`, `persistence/Spec*`, `persistence/Recording*Sidecar*` |
| networking.md | `network/*` (excluding `network/managed/`), payload classes |
| managed-worlds.md | `managed/*`, `network/managed/*`, `client/managed/*` |
| command.md | `/garnet managed` dispatch wiring (likely `managed/*Command*`) |
| gametest-harness.md | `testing/*`, `clientTest/.../SpecTestContext.kt`, `gametest/.../GametestSentinel.kt`, existing `Managed*Spec.kt` patterns |
| cross-cutting.md | _(no new source reading; references parent UCs)_ |

## Authoring sequence

1. **Per-subsystem read pass** — read sources, enumerate UCs per article. Do
   not yet write coverage tables.
2. **Audit pass** — `grep` test sources, populate coverage tables across all
   journey articles in one batch (so test references stay consistent).
3. **Cross-cutting pass** — once per-subsystem UCs are stable, write
   `cross-cutting.md` referencing parent UC IDs from the other articles.
4. **Validation pass** — verify (a) every UC ID is unique across all
   articles, (b) every test reference resolves to a real `file:method`,
   (c) every cross-cutting reference points at a UC that actually exists,
   (d) `last_audited_commit` is stamped on each article.

## Risks and mitigations

- **Test-name churn between catalog and follow-up sub-projects.** Test
  classes/methods may be renamed before sub-projects 2/3 run.
  *Mitigation:* `last_audited_commit: <sha>` in each article's frontmatter
  lets the next sub-project diff and detect drift.
- **UC granularity drift.** Tempting to over-decompose into `.a`–`.k`.
  *Mitigation:* hard cap of six sub-IDs per parent; split parents that need
  more.
- **Cross-cutting double-counting.** Cross-cutting UCs could appear to
  inflate the gap count.
  *Mitigation:* cross-cutting coverage cells read `see UC-XXX-NN` and never
  count as independent gaps.

## Self-review checklist

Run after writing the spec, before user review:

- [ ] No "TBD" / placeholder UC entries
- [ ] Prefix list `REC|RUN|PER|NET|MAN|CMD|GT` (+ `X2X` only in
  cross-cutting) is exhaustive and consistent
- [ ] Per-journey UC count estimates are framed as approximate
- [ ] Every "the audit will produce X" claim names the source files / grep
  patterns it depends on
- [ ] The doc explicitly states the audit reads tests but does not run them

## What this spec does NOT do

- Does not write any new test
- Does not modify any production code
- Does not run gradle
- Does not extend coverage to concurrency / lifecycle-race UCs
- Does not pre-decide which `GAP` UCs sub-project 2 will tackle first
