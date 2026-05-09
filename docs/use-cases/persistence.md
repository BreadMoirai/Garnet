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
**Preconditions:** A valid `RedstoneSpec` has been constructed in memory; `saveDir` is a writable path under `<world>/redstonespecs/`.
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
**Trigger:** `SpecPersistence.load(saveDir, id)` is called, or `KtsSpecLoader.loadFileAsRedstoneSpec(path)` directly.
**Preconditions:** `<id>.spec.kts` exists in `saveDir`; the file ends with a `redstoneSpec(...) { ... }` expression.
**Outcome:** A `RedstoneSpec` instance is returned whose type identity matches the host's `RedstoneSpec` class; the spec is ready for execution by `runRedstoneSpec`.

**System interactions:**
- UC-PER-02.a — `KtsSpecLoader.evalOrThrow` feeds the source to `BasicJvmScriptingHost.eval` with `SpecScriptCompilationConfig` (DSL pre-imports, classloader anchored to `RedstoneSpec::class`).
- UC-PER-02.b — On `ResultWithDiagnostics.Failure`, all severity+message diagnostics are joined and thrown as an `IllegalStateException`; the caller in `SpecPersistence.load` catches and logs a warning, returning `null`.
- UC-PER-02.c — On success, `ResultValue.Value.value` is cast to `RedstoneSpec`; a non-`RedstoneSpec` result type triggers an error naming the actual type received.
- UC-PER-02.d — `SpecPersistence.load` wraps the entire path in `runCatching` so a broken script never propagates an exception to the caller.

**Invariants:** [kts-script-host — file contract](../persistence/kts-script-host.md); [kts-script-host — classloader pinning](../persistence/kts-script-host.md)

---

### UC-PER-03 — Scan a spec directory for available specs

**Actor:** System (runner-screen picker, on screen open)
**Trigger:** The runner UI needs to populate its spec dropdown; `SpecDirectoryScan.list(specsDir)` is called.
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
**Trigger:** `KtsSpecLoader.loadFileAsRedstoneSpec` or `KtsSpecLoader.loadSpec` is called on a file that has a compilation error, a runtime exception during evaluation, or a last-expression that is not a `RedstoneSpec`.
**Preconditions:** `<id>.spec.kts` exists but is syntactically wrong, missing the `redstoneSpec(...)` tail expression, or throws at eval time.
**Outcome:** The load call throws (or returns `null` via `SpecPersistence.load`); no partial `RedstoneSpec` is surfaced; a diagnostic message names the file and cause.

**System interactions:**
- UC-PER-04.a — Compilation failure: `ResultWithDiagnostics.Failure` → `evalOrThrow` joins all `reports` (severity + message) into one `IllegalStateException("Failed to load $name:\n$msg")`.
- UC-PER-04.b — Eval-time exception: `ResultValue.Error` → `throw rv.error` surfaces the original throwable.
- UC-PER-04.c — Script did not evaluate (`ResultValue.NotEvaluated`) → `error("$name: script was not evaluated")`.
- UC-PER-04.d — Last expression is not a `RedstoneSpec` value (e.g., `Unit`) → error names the actual type received, directing the author to ensure the file ends with `redstoneSpec(...) { ... }`.
- UC-PER-04.e — `SpecPersistence.load` wraps in `runCatching`, logs a `WARN` line with the id and message, and returns `null`; the runner-screen picker skips null entries.

**Invariants:** [kts-script-host — file contract and threat model](../persistence/kts-script-host.md)

---

### UC-PER-05 — Round-trip: emit → write → load → equals

**Actor:** System (end-to-end spec identity verification)
**Trigger:** A recording session completes; the finalize path emits DSL source, writes it, then immediately loads it back to confirm integrity.
**Preconditions:** `RecordingDslEmitter` has produced a valid source string; `SpecPersistence.writeSpecKts` has written it to disk.
**Outcome:** `KtsSpecLoader.loadFileAsRedstoneSpec` returns a `RedstoneSpec` whose `id`, `bounds`, `lifespan`, `structure`, and declared callbacks structurally match the original in-memory spec.

**System interactions:**
- UC-PER-05.a — `RecordingDslEmitter` converts the in-memory `RedstoneSpec` to a DSL source string using KotlinPoet; the string is passed directly to `SpecPersistence.writeSpecKts`.
- UC-PER-05.b — `SpecPersistence.writeSpecKts` resolves `saveDir / "$id.spec.kts"` and writes the text; no intermediate format conversion occurs.
- UC-PER-05.c — `KtsSpecLoader.loadFileAsRedstoneSpec(path)` re-evaluates the file via the scripting host and returns the live `RedstoneSpec` instance.
- UC-PER-05.d — The loaded spec's `id`, `bounds`, and `lifespan` are validated by the DSL `init {}` invariants; a mismatch between emitted and loaded values indicates an emitter bug.

**Invariants:** [spec-data-model-invariants — bounds and lifespan constraints](../persistence/spec-data-model-invariants.md); [spec-on-disk-format — no JSON on disk](../persistence/spec-on-disk-format.md)

---

### UC-PER-06 — Capture and restore a structure sidecar (`.nbt`)

**Actor:** System (editor save / runner setup)
**Trigger:** The editor saves a spec and the associated circuit region must be persisted; or the runner is about to execute a spec and needs to restore the initial block state.
**Preconditions:** For save: a `ServerLevel`, origin `BlockPos`, and bounding `Vec3i` are available. For restore: `<id>.nbt` exists in `saveDir`.
**Outcome:** Save — `<id>.nbt` is written as a compressed NBT structure file usable by MC's `StructureTemplate` API. Restore — the block region is filled back to its saved state at the given origin before the spec runs.

**System interactions:**
- UC-PER-06.a — `StructurePersistence.save` builds a `StructureTemplate` via `fillFromWorld(level, originPos, bounds, false, emptyList())`, serialises it to a `CompoundTag`, and writes with `NbtIo.writeCompressed`; `IOException` is caught and logged at ERROR without re-throw.
- UC-PER-06.b — `StructurePersistence.load` reads `<id>.nbt` with `NbtIo.readCompressed` (unlimited heap accounter), reconstructs the template via `StructureTemplate.load(blockGetter, nbt)`, then places blocks with `placeInWorld(..., StructurePlaceSettings(), level.random, 2)`.
- UC-PER-06.c — `StructurePersistence.hasChanges` compares the saved NBT bytes against a freshly captured live region; returns `true` (treat as changed) on `IOException` to avoid silent data loss.
- UC-PER-06.d — `StructurePersistence.clearBounds` sets every block in the region to `AIR` before a structure is placed, preventing block merging artifacts.

**Invariants:** [spec-on-disk-format — companion files](../persistence/spec-on-disk-format.md)

---

### UC-PER-07 — Handle sidecar drift (script present, `.nbt` missing or stale)

**Actor:** System (runner pre-flight check)
**Trigger:** `SpecPersistence.load` succeeds (`.spec.kts` present and parses), but `StructurePersistence.load` finds no matching `.nbt`, or `StructurePersistence.hasChanges` reports the live region has diverged from the saved NBT.
**Preconditions:** `<id>.spec.kts` exists and loads cleanly; `<id>.nbt` is absent, unreadable, or byte-differs from the live block region.
**Outcome:** The system surfaces the drift to the operator; execution is either blocked or proceeds with a warning, depending on caller policy. The runner never silently runs a spec against a stale circuit.

**System interactions:**
- UC-PER-07.a — Missing `.nbt`: `StructurePersistence.load` logs `WARN("[StructurePersistence#load] structure file '{}' not found", file)` and returns without placing blocks; the region is whatever the world currently contains.
- UC-PER-07.b — Unreadable `.nbt`: `IOException` in `StructurePersistence.load` is caught and logged at ERROR; the return path is the same as the missing-file case.
- UC-PER-07.c — `StructurePersistence.hasChanges` returns `true` when the `.nbt` is absent, on read error, or when the serialised live region's `CompoundTag` does not equal the saved tag byte-for-byte.
- UC-PER-07.d — `RecordingSidecar.load` returns `null` when `<id>.recording.nbt` is absent; callers that only need the `StateRecording` for visualisation must handle `null` gracefully; the execution path is unaffected.

**Invariants:** [spec-on-disk-format — companion files](../persistence/spec-on-disk-format.md); [kts-script-host — threat model](../persistence/kts-script-host.md)

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-PER-01 | Write `.spec.kts` + `.nbt` + recording sidecar after finalization | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | **GAP-PARTIAL** |
| UC-PER-01.a | `SpecPersistence.writeSpecKts` creates dirs and writes file | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-01.b | `StructurePersistence.save` captures region and writes `.nbt` | — | **GAP** |
| UC-PER-01.c | `RecordingSidecar.save` writes `.recording.nbt` for editor timeline | `RecordingSidecarTest."save then load yields an equivalent recording"`, `SpecPersistenceTest."writeSpecKts with sidecar recording roundtrips"` | covered |
| UC-PER-01.d | Both files share the same `saveDir` and stem `id` | `SpecPersistenceTest."writeSpecKts with sidecar recording roundtrips"` | covered |
| UC-PER-02 | Load `.spec.kts` via scripting host | `KtsSpecLoaderTest."loadRedstoneSpec returns a dsl.RedstoneSpec from new-style source"` | covered |
| UC-PER-02.a | `KtsSpecLoader.evalOrThrow` feeds source to `BasicJvmScriptingHost.eval` | `KtsSpecLoaderTest."loadRedstoneSpec returns a dsl.RedstoneSpec from new-style source"` | covered |
| UC-PER-02.b | `ResultWithDiagnostics.Failure` throws `IllegalStateException`; `SpecPersistence.load` returns `null` | `KtsSpecLoaderTest."loadRedstoneSpec surfaces compilation errors"` | **GAP-PARTIAL** |
| UC-PER-02.c | Success casts `ResultValue.Value.value` to `RedstoneSpec` | `KtsSpecLoaderTest."loadRedstoneSpec returns a dsl.RedstoneSpec from new-style source"` | covered |
| UC-PER-02.d | `SpecPersistence.load` wraps path in `runCatching` so broken script returns `null` | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | **GAP-PARTIAL** |
| UC-PER-03 | Scan spec directory for available specs | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-03.a | `SpecDirectoryScan.list` returns `emptyList()` when directory not found | `SpecDirectoryScanTest."returns empty list when directory does not exist"` | covered |
| UC-PER-03.b | Only `.spec.kts` entries kept; stream closed in `use` block | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-03.c | Results sorted lexicographically | `SpecDirectoryScanTest."lists only .spec.kts files, sorted"` | covered |
| UC-PER-04 | Refuse malformed or evaluation-failing script | `KtsSpecLoaderTest."loadRedstoneSpec surfaces compilation errors"` | **GAP-PARTIAL** |
| UC-PER-04.a | Compilation failure → `IllegalStateException` with joined diagnostics | `KtsSpecLoaderTest."loadRedstoneSpec surfaces compilation errors"` | covered |
| UC-PER-04.b | Eval-time exception: `ResultValue.Error` → re-throw original throwable | — | **GAP** |
| UC-PER-04.c | `ResultValue.NotEvaluated` → `error(...)` | — | **GAP** |
| UC-PER-04.d | Last expression not a `RedstoneSpec` → error names actual type | — | **GAP** |
| UC-PER-04.e | `SpecPersistence.load` wraps in `runCatching`, logs WARN, returns `null` | — | **GAP** |
| UC-PER-05 | Round-trip: emit → write → load → equals | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadRedstoneSpec"`, `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-05.a | `RecordingDslEmitter` converts in-memory spec to DSL source string | `RecordingDslEmitterTest."emits redstoneSpec header with correct metadata"` | **GAP-PARTIAL** |
| UC-PER-05.b | `SpecPersistence.writeSpecKts` resolves path and writes text | `SpecPersistenceTest."writeSpecKts then load round-trips a new-dsl spec"` | covered |
| UC-PER-05.c | `KtsSpecLoader.loadFileAsRedstoneSpec` re-evaluates file and returns `RedstoneSpec` | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadRedstoneSpec"` | covered |
| UC-PER-05.d | Loaded spec's `id`, `bounds`, `lifespan` match emitted values | `KtsSpecLoaderRoundtripTest."new-dsl source roundtrips id, bounds, lifespan via loadRedstoneSpec"` | covered |
| UC-PER-06 | Capture and restore structure sidecar (`.nbt`) | — | **GAP** |
| UC-PER-06.a | `StructurePersistence.save` builds template, serialises, writes NBT | — | **GAP** |
| UC-PER-06.b | `StructurePersistence.load` reads NBT and places blocks with `placeInWorld` | — | **GAP** |
| UC-PER-06.c | `StructurePersistence.hasChanges` returns `true` on absent file, read error, or byte diff | — | **GAP** |
| UC-PER-06.d | `StructurePersistence.clearBounds` sets region to AIR before placement | — | **GAP** |
| UC-PER-07 | Handle sidecar drift (script present, `.nbt` missing or stale) | — | **GAP** |
| UC-PER-07.a | Missing `.nbt`: `StructurePersistence.load` logs WARN and returns without placing | — | **GAP** |
| UC-PER-07.b | Unreadable `.nbt`: `IOException` caught and logged at ERROR | — | **GAP** |
| UC-PER-07.c | `hasChanges` returns `true` on absent file, read error, or byte mismatch | — | **GAP** |
| UC-PER-07.d | `RecordingSidecar.load` returns `null` when sidecar absent | `RecordingSidecarTest."load returns null when sidecar absent"`, `SpecPersistenceTest."no sidecar without explicit save"` | covered |
