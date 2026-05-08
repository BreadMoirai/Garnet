---
title: RedstoneSpec ↔ Kotest bridge
tags: [design, runner, kotest, dsl, persistence, lifecycle]
summary: Make authoring and running an in-game RedstoneSpec the same shape as writing and running a Kotest unit test, by shipping kotest-engine + kotlin-scripting in main and replacing the SpecEntry data model with executable .spec.kts files.
---

# RedstoneSpec ↔ Kotest bridge — Design

## Goal

When a user defines a `RedstoneSpec` in-game, the development process and the end result should be indistinguishable from writing and running a Kotlin unit test:

- Authoring produces readable, hand-editable Kotlin source.
- Execution runs through the real Kotest engine and produces real `TestResult`s.
- Failures land at exact assertion granularity with line numbers, not as a single "verification failed" rollup.
- Dev-side tests (`src/clientTest/`, `src/gametest/`) and shipped end-user specs share one base class, one DSL, and one runner.

## Scope

In scope:

- Promote Kotest's runtime dependency from `testImplementation` to `main` (engine + assertions).
- Add `kotlin-scripting-jvm-host` + `kotlin-scripting-compiler-embeddable` to `main` so `.spec.kts` files can be compiled and executed at runtime.
- Replace `RecordingFinalizer.deriveEntries` (flat `SpecEntry` rows) with `RecordingToKts` (Kotlin source generation).
- Replace `OutputVerifier` (post-run diff) with assertion helpers called inline from test bodies.
- Migrate `<id>.nbt` (flat-entries persistence) to `<id>.spec.kts` (executable) plus `<id>.recording.nbt` (reference snapshot only).
- Single base class `RedstoneTestSpec` used by both shipped and dev-side tests.

Out of scope:

- Multi-case-per-spec authoring (one spec = one test case is locked).
- Hand-rolled DSL parsers; we evaluate `.kts` directly via the scripting host.
- Migration of existing on-disk specs (greenfield format change; users re-record).

## Constraints driving the design

These are decided; the design downstream of them is not negotiable without revisiting them.

1. **Audience: both end-users and developers, with a shared core.** No two-tier system where end-user specs and dev tests diverge.
2. **Authoring + execution + ship Kotest at runtime.** The DSL itself is test-shaped; Kotest is the execution engine, not just the reporter.
3. **One spec = one test case.** Multiple scenarios = multiple spec files.
4. **Recording auto-derives → explicit DSL.** Recording is a code generator; the `.spec.kts` it emits is the source of truth thereafter.
5. **`.spec.kts` evaluated at runtime.** Real Kotlin script, hand-editable in any text editor.
6. **Authorship recording retained as reference.** `<id>.recording.nbt` sidecar; not on the execution path.
7. **Diagnostic recording always-on during replay.** Every test run captures a full state trace, attached to the `TestResult`.

## The two recordings

The current pipeline conflates two distinct recording activities. The new design splits them.

| Activity | When | Output | Role |
|---|---|---|---|
| **R1 — Authorship recording** | User plays in-game during initial spec creation | `StateRecording` → finalized to `.spec.kts` source | One-shot code generator. After finalize, the recording is persisted as `<id>.recording.nbt` for visualization/regeneration but is **not** consulted at execution time. |
| **R2′ — Diagnostic recording** | Every spec replay | `StateRecording` attached to `TestResult.metaData` | Always-on observability. Powers the in-game timeline scrubber for any run, pass or fail. Does not participate in verification. |

The current `OutputVerifier`-driven Stage 4 disappears: assertions in the test body verify directly. Diagnostic recording observes alongside but never gates pass/fail.

## Pipeline before / after

Before:

```
record → finalize → run → verify
StateRecorder    SpecEntry rows    SpecRunner    OutputVerifier
                 (data spec)       re-records    diffs recordings
```

After:

```
authorship:  record → finalize-to-kts          (one-shot, produces source)
             StateRecorder    RecordingToKts   → <id>.spec.kts + <id>.recording.nbt

execution:   load .kts → kotest engine → test body drives runner → assertions fire
             KtsSpecLoader    RedstoneTestEngine    RunnerDsl       Kotest TestResult

diagnostics: DiagnosticRecorder runs alongside execution; attaches recording to TestResult
```

## Module shape

New package: `net.breadmoirai.redstonespecs.testing` in the `main` source set.

| Component | Responsibility | Notes |
|---|---|---|
| `RedstoneTestSpec` | Base class extending Kotest `FunSpec`, wraps lifecycle in `withContext(McDispatchers.Server)` via `CoroutineDispatcherFactory`. | Used by both shipped `.spec.kts` and dev tests in `src/clientTest/`, `src/gametest/`, `src/test/`. Replaces the dev-only `ServerTestSpec`. |
| `RedstoneTestEngine` | Programmatic Kotest launcher. Single entry point for in-game Run buttons, `RedstoneSpecRunnerBlock`, and dev-side gradle tasks. | Wraps Kotest's `KotestEngineLauncher`. Configured programmatically (no `ServiceLoader`) to sidestep Fabric classloader issues. |
| `KtsSpecLoader` | Compiles `<id>.spec.kts` via Kotlin scripting host into a `Spec` instance. Caches by file content hash. | Sole runtime consumer of `kotlin-scripting-jvm-host`. |
| `RecordingToKts` | Pure function: `(baseSpec, StateRecording) → KtsSource`. | Replaces `RecordingFinalizer.deriveEntries`. Output is human-readable Kotlin. |
| `RunnerDsl` | Helper surface available inside test bodies: `spawnStructure`, `press`, `awaitTicks`, `signalAt`, `expectStable`, etc. | Delegates to existing runner internals (`tryApplyAsPlayerInteraction`, `StateRecorder`, structure-grid allocator). No logic duplication. |
| `DiagnosticRecorder` | `TestListener` that runs `StateRecorder` for every test, attaches result to `TestResult.metaData`. | Always-on. Surfaces in the in-game timeline scrubber and the HTML report. |

Removed:

- `OutputVerifier` (entire class).
- The dev-only `ServerTestSpec` class (collapsed into `RedstoneTestSpec`).

### Out of scope (deferred to a future plan)

The original design listed these as candidates for retirement, but the implementation
landed in 2026-05-07's six plans (A–F) deliberately scoped to the engine bridge only.
The data-model retirement is left for a future "Plan G" follow-up:

- `SpecEntry` and the per-entry data shape — still consumed by `RedstoneSpec.entries`,
  `data/dsl/EntryDsl.kt`, `client/screen/SpecEditorScreen.kt`, HUD/bounds renderers.
  The in-game editor still authors and edits per-entry rows.
- `SpecJsonCodec` — still used for BE NBT serialization and the in-flight per-entry
  C2S edit payloads.
- `SaveSpecEntryC2SPayload` and `RemoveSpecEntryC2SPayload` — still the granular-edit
  network layer.
- `RecordingFinalizer.deriveEntries` — still called from `SpecBlockEntity` after a
  recording completes; produces `SpecEntry` rows that round-trip through `KtsSpecEmitter`.

These pieces remain functional. A follow-up plan can retire them once the editor UI
is reworked to operate directly on `.spec.kts` text or generated test bodies.

## Authored DSL — example

What `RecordingToKts` emits, and what users read/edit:

```kotlin
class ComparatorLatchSpec : RedstoneTestSpec({
    test("comparator latches after 4 ticks") {
        val s = spawnStructure(id("redstonespecs", "comparator_basic"))
        press(s.absolute(BlockPos(2, 2, 1)))
        awaitTicks(4)
        s.signalAt(BlockPos(4, 2, 1)) shouldBe 15
        s.expectStable(BlockPos(4, 2, 1))
    }
})
```

This is the same shape as a hand-written dev test in `src/clientTest/`. The only difference: shipped specs come from a `.spec.kts` evaluated at runtime; dev specs are compiled into the jar.

## Persistence

| File | Format | Role |
|---|---|---|
| `<id>.spec.kts` | Kotlin source | Source of truth. Executed by Kotest engine. Hand-editable. |
| `<id>.recording.nbt` | NBT | Authorship snapshot. Used by editor for visualization and `.kts` regeneration. **Never** consulted at execution time. |

Network: the `SaveSpecEntryC2SPayload` (per-entry edits) is removed. New `SaveSpecScriptC2SPayload` ships the full `.kts` text plus the recording NBT in one payload. Granular per-entry edits no longer apply — the spec is source code, so the smallest edit unit is "recompile the script."

## In-game run lifecycle

1. User clicks Run on a `RedstoneSpecRunnerBlock`.
2. Server resolves spec id → `<id>.spec.kts` text.
3. `KtsSpecLoader.compile(text)` → `Spec` class (cached by content hash).
4. `RedstoneTestEngine.run(spec)` launches Kotest on a worker thread; `CoroutineDispatcherFactory` ensures test-body code runs on the server thread.
5. Test body executes; `RunnerDsl` calls drive structure spawn, input dispatch, tick advancement, sampling. `DiagnosticRecorder` captures alongside.
6. Engine produces a `TestResult`; runner block UI shows pass/fail + assertion line; in-game timeline scrubber reads the attached diagnostic recording.
7. HTML/JUnit reports written to disk under `build/reports/redstonespecs/runtime/` for parity with dev tests.

## Convergence with dev-side tests

After this change:

- All test source sets (`src/test/`, `src/gametest/`, `src/clientTest/`) extend `RedstoneTestSpec` instead of the now-removed `ServerTestSpec`.
- Dev tests `spawnStructure` from `src/<sourceSet>/resources/data/...` exactly as today.
- Shipped specs and dev specs run through the same engine, the same DSL, and the same reporters.
- The only divergence is the source of the spec class: dev = compiled into jar; runtime = compiled from `.spec.kts` by `KtsSpecLoader`.

The `docs/gametest/kotest-bridge.md` article continues to apply to dev tests verbatim — `awaitTicks`, `onServer`, `spawnStructure` semantics are unchanged. Update its base-class reference from `ServerTestSpec` to `RedstoneTestSpec` and note that the same primitives are now also available to shipped specs.

## Error handling and reporting

- Compilation errors in `.spec.kts` surface as a synthetic failed `TestResult` with the compiler diagnostic. The runner block UI shows the line/column.
- Test-body assertion failures are ordinary Kotest failures; Kotest's existing reporting machinery handles them.
- Runtime exceptions (e.g. structure not found, input dispatch failed) propagate as test failures, not crashes — the engine catches and reports.
- The `DiagnosticRecorder` artifact is always attached, so any failure has a full per-tick state trace available in the report.

## Testing strategy

- The bridge itself (`KtsSpecLoader`, `RecordingToKts`, `RedstoneTestEngine`) gets dev-side Kotest specs in `src/test/` (no MC needed) and `src/clientTest/` (end-to-end with a real world).
- A round-trip test: record a structure → finalize to `.kts` → recompile → execute → assert pass. Confirms `RecordingToKts` output is always executable and self-consistent.
- A diagnostic-recording test: induce a failure, assert the `TestResult.metaData` carries a recording matching the replay.

## Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Kotlin scripting host on Fabric is uncharted; classloader and JiJ behavior unknown. | Could block the whole approach. | **Spike first.** If intractable, fall back to a declarative one-line test body (Approach C from brainstorming) — same end-user UX, no scripting host. |
| Kotest's `ServiceLoader`-based discovery has bitten Fabric mods before. | Engine fails to find listeners/extensions. | Configure the engine programmatically via `KotestEngineLauncher`'s explicit API; never rely on SL at runtime. |
| Jar size: kotest-engine + assertions + scripting host ≈ 25–40 MB. | Larger mod download. | JiJ via Fabric Loom; consider relocation if conflicts arise; document the cost in `docs/build/`. |
| Always-on diagnostic recording cost on every replay. | Memory/CPU per run. | Reuse the existing `StateRecorder` ring-buffer pattern; cap retained ticks by spec bounds size; the existing recorder is already production-tuned for authorship. |
| Greenfield format change abandons existing on-disk specs. | Users lose old specs. | Documented as a one-time break; current specs are gametest stubs per `docs/gametest/INDEX.md`, so real-world impact is minimal. |

## Open questions

- Where on disk do shipped `.spec.kts` files live for an end-user? (World save folder vs. mod config dir vs. data pack — picked at plan time.)
- HTML report destination for runtime runs — same `build/reports/redstonespecs/runtime/` for dev parity, or a world-save-relative path?
- Does the in-game editor allow free-form `.kts` editing, or only structured edits with regenerate-from-recording? (Locked-in answer affects editor UI scope.)

These resolve during plan writing; none invalidate the architecture.
