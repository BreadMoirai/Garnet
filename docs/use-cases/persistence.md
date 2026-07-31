---
title: Persistence use-cases
tags: [storage, kts, sidecar, scripting, use-cases]
summary: Save and load `.spec.kts` + `.nbt` pairs; scan spec directories; handle sidecar drift.
last_audited_commit: 04907e06339cd4a545cef18246e30f515326c44d
---

# Persistence use-cases

The persistence layer turns in-memory specs into `.spec.kts` + `.nbt` file pairs and back. JSON appears nowhere on disk or wire for spec content (see [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md)).

---

### UC-PER-01 — Write `.spec.kts` atomically after recording finalization

**Actor:** System (recording finalize path)
**Trigger:** `RecordingDslEmitter` finishes emitting DSL source after a capture session ends.
**Preconditions:** A valid `GarnetSpec` has been constructed in memory; `saveDir` is a writable path under `<world>/garnet/`.
**Outcome:** A `<id>.spec.kts` file is written to disk containing the full DSL representation of the spec; the file can be immediately reloaded by `KtsSpecLoader`.

**System interactions:**
- UC-PER-01.a — `SpecPersistence.writeSpecKts(saveDir, id, source)` calls `saveDir.createDirectories()` then `file.writeText(source)`, overwriting any prior version.
- UC-PER-01.b — `StructurePersistence.save(saveDir, id, level, originPos, bounds)` captures the live block region via `StructureTemplate.fillFromWorld` and writes `<id>.nbt` with `NbtIo.writeCompressed`.
- UC-PER-01.c — `RecordingSidecar.save(saveDir, specId, recording)` serialises the authorship-time `StateRecording` to `<id>.recording.nbt` for the editor timeline; not consulted on the execution path.
- UC-PER-01.d — Both files resolve to the same `saveDir` directory; the pair is identified by the shared stem `id`.

**Invariants:** [spec-on-disk-format — companion files](../persistence/spec-on-disk-format.md); [spec-data-model-invariants — id is the filename stem](../persistence/spec-data-model-invariants.md)

---

### UC-PER-02 — Load a `.spec.kts` file via the Kotlin scripting host

**Actor:** System (spec load path: server startup or on-demand load)
**Trigger:** `SpecPersistence.load(saveDir, id)` is called, or `KtsSpecLoader.loadFileAsGarnetSpec(path)` directly.
**Preconditions:** `<id>.spec.kts` exists in `saveDir`; the file ends with a `garnetSpec(...) { ... }` expression.
**Outcome:** A `GarnetSpec` instance is returned whose type identity matches the host's `GarnetSpec` class; the spec is ready for execution by `runGarnetSpec`.

**System interactions:**
- UC-PER-02.a — `KtsSpecLoader.evalOrThrow` feeds the source to `BasicJvmScriptingHost.eval` with `SpecScriptCompilationConfig` (DSL pre-imports, classloader anchored to `GarnetSpec::class`).
- UC-PER-02.b — On `ResultWithDiagnostics.Failure`, all severity+message diagnostics are joined and thrown as an `IllegalStateException`; the caller in `SpecPersistence.load` catches and logs a warning, returning `null`.
- UC-PER-02.c — On success, `ResultValue.Value.value` is cast to `GarnetSpec`; a non-`GarnetSpec` result type triggers an error naming the actual type received.
- UC-PER-02.d — `SpecPersistence.load` wraps the entire path in `runCatching` so a broken script never propagates an exception to the caller.

**Invariants:** [kts-script-host — file contract](../persistence/kts-script-host.md); [kts-script-host — classloader pinning](../persistence/kts-script-host.md)

---

### UC-PER-03 — Scan a spec directory for available specs

**Actor:** System (no live caller today — the runner-screen picker that used to call this was
deleted with the recorder/runner blocks; `SpecDirectoryScan` is exercised only by its own unit
test now)
**Trigger:** `SpecDirectoryScan.list(specsDir)` is called directly.
**Preconditions:** `specsDir` may or may not exist; the caller handles both cases.
**Outcome:** A sorted list of `.spec.kts` filenames (relative names, not full paths) is returned; an empty list is returned if the directory does not exist or contains no matching files.

**System interactions:**
- UC-PER-03.a — `SpecDirectoryScan.list` guards with `Files.isDirectory(specsDir)`; returns `emptyList()` if the guard fails, so callers need no null check.
- UC-PER-03.b — `Files.list(specsDir)` streams all entries; the stream is closed in a `use` block; only entries whose filename ends with `.spec.kts` are kept.
- UC-PER-03.c — Results are sorted lexicographically before being returned, giving the dropdown a stable alphabetical order independent of filesystem traversal order.

**Invariants:** [spec-on-disk-format — spec directory layout](../persistence/spec-on-disk-format.md)

---

### UC-PER-04 — Refuse malformed or evaluation-failing script

**Actor:** System (load path)
**Trigger:** `KtsSpecLoader.loadFileAsGarnetSpec` or `KtsSpecLoader.loadSpec` is called on a file that has a compilation error, a runtime exception during evaluation, or a last-expression that is not a `GarnetSpec`.
**Preconditions:** `<id>.spec.kts` exists but is syntactically wrong, missing the `garnetSpec(...)` tail expression, or throws at eval time.
**Outcome:** The load call throws (or returns `null` via `SpecPersistence.load`); no partial `GarnetSpec` is surfaced; a diagnostic message names the file and cause.

**System interactions:**
- UC-PER-04.a — Compilation failure: `ResultWithDiagnostics.Failure` → `evalOrThrow` joins all `reports` (severity + message) into one `IllegalStateException("Failed to load $name:\n$msg")`.
- UC-PER-04.b — Eval-time exception: `ResultValue.Error` → `throw rv.error` surfaces the original throwable.
- UC-PER-04.c — Script did not evaluate (`ResultValue.NotEvaluated`) → `error("$name: script was not evaluated")`.
- UC-PER-04.d — Last expression is not a `GarnetSpec` value (e.g., `Unit`) → error names the actual type received, directing the author to ensure the file ends with `garnetSpec(...) { ... }`.
- UC-PER-04.e — `SpecPersistence.load` wraps in `runCatching`, logs a `WARN` line with the id and message, and returns `null`; the runner-screen picker skips null entries.

**Invariants:** [kts-script-host — file contract and threat model](../persistence/kts-script-host.md)

---

### UC-PER-05 — Round-trip: emit → write → load → equals

**Actor:** System (end-to-end spec identity verification)
**Trigger:** A recording session completes; the finalize path emits DSL source, writes it, then immediately loads it back to confirm integrity.
**Preconditions:** `RecordingDslEmitter` has produced a valid source string; `SpecPersistence.writeSpecKts` has written it to disk.
**Outcome:** `KtsSpecLoader.loadFileAsGarnetSpec` returns a `GarnetSpec` whose `id`, `bounds`, `lifespan`, `structure`, and declared callbacks structurally match the original in-memory spec.

**System interactions:**
- UC-PER-05.a — `RecordingDslEmitter` converts the in-memory `GarnetSpec` to a DSL source string using KotlinPoet; the string is passed directly to `SpecPersistence.writeSpecKts`.
- UC-PER-05.b — `SpecPersistence.writeSpecKts` resolves `saveDir / "$id.spec.kts"` and writes the text; no intermediate format conversion occurs.
- UC-PER-05.c — `KtsSpecLoader.loadFileAsGarnetSpec(path)` re-evaluates the file via the scripting host and returns the live `GarnetSpec` instance.
- UC-PER-05.d — The loaded spec's `id`, `bounds`, and `lifespan` are validated by the DSL `init {}` invariants; a mismatch between emitted and loaded values indicates an emitter bug.

**Invariants:** [spec-data-model-invariants — bounds and lifespan constraints](../persistence/spec-data-model-invariants.md); [spec-on-disk-format — no JSON on disk](../persistence/spec-on-disk-format.md)

---

### UC-PER-06 / UC-PER-07 — Structure sidecar capture, restore, and drift *(moved)*

The spec-cell structure-sidecar journeys — `StructurePersistence.save`/`load`/`hasChanges`,
`clearBounds`, and the missing/unreadable/stale-`.nbt` drift handling — now live with the rest of
the structure-I/O journeys in
[structure-lifecycle.md](structure-lifecycle.md#spec-cell-structure-sidecar-uc-per-06--uc-per-07).
The UC IDs (`UC-PER-06.a`–`.d`, `UC-PER-07.a`–`.d`) are unchanged; only the article that hosts them
moved. `UC-PER-01.b` (the `StructurePersistence.save` call on the finalize path) still lives above,
in UC-PER-01.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-PER-01 | Write `.spec.kts` + `.nbt` + recording sidecar after finalization | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | **GAP-PARTIAL** |
| UC-PER-01.a | `SpecPersistence.writeSpecKts` creates dirs and writes file | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-01.b | `StructurePersistence.save` captures region and writes `.nbt` | `StructureSidecarPersistenceSpec."UC-PER-06: save captures the region and load restores it byte-for-byte at the origin"` (same call; see [structure-lifecycle.md](structure-lifecycle.md)) | covered |
| UC-PER-01.c | `RecordingSidecar.save` writes `.recording.nbt` for editor timeline | `RecordingSidecarTest."save then load yields an equivalent recording"`, `SpecPersistenceTest."writeSpecKts with sidecar recording roundtrips"` | covered |
| UC-PER-01.d | Both files share the same `saveDir` and stem `id` | `SpecPersistenceTest."writeSpecKts with sidecar recording roundtrips"` | covered |
| UC-PER-02 | Load `.spec.kts` via scripting host | `KtsSpecLoaderTest."loadGarnetSpec returns a spec.GarnetSpec from new-style source"` | covered |
| UC-PER-02.a | `KtsSpecLoader.evalOrThrow` feeds source to `BasicJvmScriptingHost.eval` | `KtsSpecLoaderTest."loadGarnetSpec returns a spec.GarnetSpec from new-style source"` | covered |
| UC-PER-02.b | `ResultWithDiagnostics.Failure` throws `IllegalStateException`; `SpecPersistence.load` returns `null` | `KtsSpecLoaderTest."loadGarnetSpec surfaces compilation errors"` | **GAP-PARTIAL** |
| UC-PER-02.c | Success casts `ResultValue.Value.value` to `GarnetSpec` | `KtsSpecLoaderTest."loadGarnetSpec returns a spec.GarnetSpec from new-style source"` | covered |
| UC-PER-02.d | `SpecPersistence.load` wraps path in `runCatching` so broken script returns `null` | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | **GAP-PARTIAL** |
| UC-PER-03 | Scan spec directory for available specs | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-03.a | `SpecDirectoryScan.list` returns `emptyList()` when directory not found | `SpecDirectoryScanTest."returns empty list when directory does not exist"` | covered |
| UC-PER-03.b | Only `.spec.kts` entries kept; stream closed in `use` block | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-03.c | Results sorted lexicographically | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-04 | Refuse malformed or evaluation-failing script | `KtsSpecLoaderTest."loadGarnetSpec surfaces compilation errors"` | **GAP-PARTIAL** |
| UC-PER-04.a | Compilation failure → `IllegalStateException` with joined diagnostics | `KtsSpecLoaderTest."loadGarnetSpec surfaces compilation errors"` | covered |
| UC-PER-04.b | Eval-time exception: `ResultValue.Error` → re-throw original throwable | — | **GAP** |
| UC-PER-04.c | `ResultValue.NotEvaluated` → `error(...)` | — | **GAP** |
| UC-PER-04.d | Last expression not a `GarnetSpec` → error names actual type | — | **GAP** |
| UC-PER-04.e | `SpecPersistence.load` wraps in `runCatching`, logs WARN, returns `null` | — | **GAP** |
| UC-PER-05 | Round-trip: emit → write → load → equals | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadGarnetSpec"`, `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-05.a | `RecordingDslEmitter` converts in-memory spec to DSL source string | `RecordingDslEmitterTest."emits garnetSpec header with correct metadata"` | **GAP-PARTIAL** |
| UC-PER-05.b | `SpecPersistence.writeSpecKts` resolves path and writes text | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-05.c | `KtsSpecLoader.loadFileAsGarnetSpec` re-evaluates file and returns `GarnetSpec` | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadGarnetSpec"` | covered |
| UC-PER-05.d | Loaded spec's `id`, `bounds`, `lifespan` match emitted values | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadGarnetSpec"` | covered |
| UC-PER-06 | Capture and restore structure sidecar (`.nbt`) *(moved)* | see [structure-lifecycle.md — coverage matrix](structure-lifecycle.md#coverage-matrix) | moved |
| UC-PER-07 | Handle sidecar drift (`.nbt` missing / stale) *(moved)* | see [structure-lifecycle.md — coverage matrix](structure-lifecycle.md#coverage-matrix) | moved |
