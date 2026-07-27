---
title: Structure lifecycle use-cases
tags: [structure, nbt, dirty-state, save, revert, sidecar, use-cases]
summary: Saving/loading `.nbt` structures, `.nbt.unsaved` dirty tracking, and save-vs-discard, across both the spec-cell sidecar and the standalone-Explorer path.
last_audited_commit: 554fa4a50d6b42d1c6ed989bcbf13141f286c020
---

# Structure lifecycle use-cases

Everything about turning a live in-world block region into an on-disk `.nbt` structure and back:
capture, place, dirty-state tracking, explicit save, and revert. Two code paths write and read
`.nbt` files, and they are deliberately different:

| Path | Origin / bounds | Written by | Read by | Home UCs |
|---|---|---|---|---|
| **Spec-cell sidecar** | fixed origin + fixed `bounds` (`fillFromWorld`/`placeInWorld` 1:1) | `StructurePersistence.save` | `StructurePersistence.load` | UC-PER-06, UC-PER-07 |
| **Standalone Explorer file** | auto-fit tight box within a region lane, re-centered on place | `StructurePersistence.saveAutoFitToFile` | `StructurePersistence.placeStructureCentered` | UC-MAN-10 |

**Dirty-state** is a first-class concept only on the standalone path: an in-progress edit is buffered
to a `<name>.nbt.unsaved` sidecar next to the committed `<name>.nbt`. "Save Structure" commits and
deletes the sidecar; "Discard" deletes the sidecar and re-places from the committed file; a world-save
(`BEFORE_SAVE`) flushes the live region to (or deletes) the sidecar. The committed `.nbt` is never
touched except by an explicit save. See
[architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files)
and [persistence/spec-on-disk-format.md](../persistence/spec-on-disk-format.md).

The **grid-cell save-back** (UC-MAN-07) is a third, related mechanism — it diffs a spec cell's live
volume against a captured baseline and rewrites the spec's `.nbt` — but it lives with the
grid-projection journeys in [redstone-project.md](redstone-project.md#uc-man-07--save-edited-cell-blocks-back-to-disk).

---

## Standalone `.nbt` structures (UC-MAN-10)

*(Moved here from [redstone-project.md](redstone-project.md); the IDs are unchanged.)*

A `.nbt` file in the Explorer is a first-class citizen independent of any spec: clicking it places
the structure, "Save Structure" auto-fits and rewrites it, and "+ Structure" creates a new empty
one.

### UC-MAN-10 — Place, save, and create standalone structure files

- **UC-MAN-10.a** Clicking a `.nbt` `FileNode` in `ProjectExplorerPanel` sends `PlaceStructureC2S(path)`. `ProjectNetworkRegistry.handlePlaceStructure` resolves the subpath, checks for a `.nbt.unsaved` sidecar (`StructurePersistence.unsavedSidecarOf`) and loads it in preference to the committed `.nbt` when present (reporting `hasUnsaved = true`), then delegates to the shared `placeStructureFrom` helper: assigns/reuses a region via `ProjectDimRegistry.getOrAssignStructureRegion` (a disjoint +X lane at `z = STRUCTURE_LANE_Z`), clears only the previously-placed footprint (`placedBoxOf` → `StructurePersistence.clearBounds`), calls `StructurePersistence.placeStructureCentered`, and records the new `PlacedBox` via `setPlacedBox`. A non-`.nbt` subpath and a corrupt/unreadable `.nbt` each reply with `ProjectErrorS2C` rather than throwing.
- **UC-MAN-10.b** `StructureRegionMath.centeredStart`/`anchorY` center the structure in the region and floor it at `SharedSettings.projectGridYBase` (64), or vertically center it when the structure's height is at or above `TALL_THRESHOLD` (256).
- **UC-MAN-10.c** The `StructureActions()` "Save Structure" button (enabled when `selectedPath` ends with `.nbt`) sends `SaveStructureC2S(path)`. `handleSaveStructure` refuses (with `ProjectErrorS2C`) unless the structure was placed this session (`placedBoxOf(subpath) != null`), then scans the assigned region for non-air, computes the tight box via `StructureRegionMath.autoFit`, calls `StructurePersistence.saveAutoFitToFile` to rewrite `<name>.nbt` (an all-air region returns `null` and still writes an empty structure file), and deletes the `.nbt.unsaved` sidecar so the structure reports clean.
- **UC-MAN-10.d** The `StructureActions()` "+ Structure" name field sends `NewStructureC2S(name)`. `handleNewStructure` calls `ProjectNewStructure.create(folder, name)`, which writes an empty `<name>.nbt` into the active folder, then re-sends the project tree.
- **UC-MAN-10.e** All handlers reply with `StructureResultS2C(subpath, sizeX, sizeY, sizeZ, hasUnsaved, message)`; `ProjectClientNetworking` feeds it to `ProjectTreeState.onStructureResult`, which sets `status = message` — the same status line used by folder load/save results. `hasUnsaved` is `true` only when place loaded from the `.nbt.unsaved` sidecar; save and discard both report `false`.
- **UC-MAN-10.f** `DiscardStructureC2S(subpath)` → `handleDiscardStructure` deletes the `.nbt.unsaved` sidecar (if any) and re-places from the committed `.nbt` via `placeStructureFrom`, reporting `hasUnsaved = false`. On `ServerLifecycleEvents.BEFORE_SAVE`, `ProjectNetworkRegistry.flushDirtyStructures` iterates `ProjectDimRegistry.placedStructureSubpaths()` and calls `StructurePersistence.flushUnsavedSidecar` for each placed structure's region, writing (or deleting, if the region now matches the committed file) its `.nbt.unsaved` — this is the only auto-persist point for in-progress structure edits; there is no autosave on disconnect. Dirtiness is decided by `structuresDiffer`, a palette-order-insensitive NBT comparison (a reordered palette with remapped indices is *not* a change).
- **UC-MAN-10.g** The `StructureActions()` "Discard" button sends `DiscardStructureC2S(selectedPath)` when `selectedPath` ends with `.nbt`; it renders dimmed (`TEXT_DISABLED`) unless `ProjectTreeState.selectedHasUnsaved()` is true. `selectedHasUnsaved()` resolves `selectedPath` against `snapshot.root` via `FolderNode.resolve` and returns true only when it lands on a `FileNode` with `hasUnsaved == true`. The tree also prefixes a `● ` dirty dot on a structure `FileNode`'s label when `node.hasUnsaved` is true.

---

## Spec-cell structure sidecar (UC-PER-06 / UC-PER-07)

*(Moved here from [persistence.md](persistence.md); the IDs are unchanged.)* This is the exact-origin,
fixed-`bounds` path used by the recording-finalize save and the runner's pre-execution restore. It
does **not** auto-fit or re-center — it captures and replaces a region 1:1 at a caller-supplied
origin.

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

### UC-PER-07 — Handle sidecar drift (script present, `.nbt` missing or stale)

**Actor:** System (runner pre-flight check)
**Trigger:** `SpecPersistence.load` succeeds (`.spec.kts` present and parses), but `StructurePersistence.load` finds no matching `.nbt`, or `StructurePersistence.hasChanges` reports the live region has diverged from the saved NBT.
**Preconditions:** `<id>.spec.kts` exists and loads cleanly; `<id>.nbt` is absent, unreadable, or byte-differs from the live block region.
**Outcome:** The system surfaces the drift to the operator; execution is either blocked or proceeds with a warning, depending on caller policy. The runner never silently runs a spec against a stale circuit.

**System interactions:**
- UC-PER-07.a — Missing `.nbt`: `StructurePersistence.load` logs `WARN("[StructurePersistence#load] structure file '{}' not found", file)` and returns without placing blocks; the region is whatever the world currently contains.
- UC-PER-07.b — Unreadable `.nbt`: `IOException` in `StructurePersistence.load` is caught and logged at ERROR; the return path is the same as the missing-file case.
- UC-PER-07.c — `StructurePersistence.hasChanges` returns `true` when the `.nbt` is absent, on read error, or when the serialised live region's `CompoundTag` does not equal the saved tag byte-for-byte.
- UC-PER-07.d — `RecordingSidecar.load` returns `null` when `<id>.recording.nbt` is absent; callers that only need the `StateRecording` for visualisation must handle `null` gracefully; the execution path is unaffected. *(This is the recording-sidecar sibling of the same drift journey; the `.recording.nbt` is authorship metadata, not a structure.)*

**Invariants:** [spec-on-disk-format — companion files](../persistence/spec-on-disk-format.md); [kts-script-host — threat model](../persistence/kts-script-host.md)

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-MAN-10 | Place, save, and create standalone structure files | `ProjectStructureNetworkSpec` | covered |
| UC-MAN-10.a | `PlaceStructureC2S` → `handlePlaceStructure` prefers the `.nbt.unsaved` sidecar, assigns/reuses a structure region, cheap-re-clears the prior footprint, places via `placeStructureFrom`, records the new `PlacedBox`; non-`.nbt` and corrupt `.nbt` reply with an error | `ProjectStructureNetworkSpec."place then save round-trips a standalone structure via handlers"`, `ProjectStructureNetworkSpec."place rejects a non-.nbt subpath"`, `ProjectStructureNetworkSpec."placing a corrupt .nbt replies with an error instead of throwing"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"`, `ProjectDimRegistryTest."getOrAssignStructureRegion is idempotent and distinct per subpath"`, `ProjectDimRegistryTest."structure regions sit in a lane disjoint from spec-folder regions"`, `ProjectDimRegistryTest."placed-box round-trips per subpath"` | covered |
| UC-MAN-10.b | `centeredStart`/`anchorY` center in-region and floor/vertically-center by height | `StructureRegionMathTest."centeredStart centers a box in a region (floor-divides odd slack)"`, `StructureRegionMathTest."anchorY floors short structures at yBase"`, `StructureRegionMathTest."anchorY vertically centers structures at or above the tall threshold"` | covered |
| UC-MAN-10.c | `SaveStructureC2S` → `handleSaveStructure` refuses unless placed this session, auto-fits the non-air region, rewrites the `.nbt`, deletes the `.nbt.unsaved` sidecar; empty region still writes a file | `StructureRegionPersistenceSpec."auto-fit save captures the tight non-air box; place re-centers it"`, `StructureRegionPersistenceSpec."auto-fit save of an empty region writes a file and returns null"`, `StructureRegionMathTest."autoFit tightly boxes scattered non-air cells"`, `StructureRegionMathTest."autoFit returns null when the volume has no non-air"`, `ProjectStructureNetworkSpec."save without placing this session is refused and does not touch the file"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"` | covered |
| UC-MAN-10.d | `NewStructureC2S` → `handleNewStructure` writes an empty `.nbt` and re-sends the tree | `ProjectStructureNetworkSpec."new structure creates the file and re-sends the tree"` | covered |
| UC-MAN-10.e | `StructureResultS2C` codec and status-line wiring | `StructurePacketsTest."StructureResultS2C codec round-trips"`, `StructurePacketsTest."PlaceStructureC2S codec round-trips"`, `StructurePacketsTest."SaveStructureC2S codec round-trips"`, `StructurePacketsTest."NewStructureC2S codec round-trips"`, `StructureExplorerSpec."onStructureResult surfaces the message as Explorer status"` | covered |
| UC-MAN-10.f | `DiscardStructureC2S` → `handleDiscardStructure` deletes the sidecar and re-places from the committed `.nbt`; `BEFORE_SAVE` → `flushDirtyStructures` writes/deletes each placed structure's sidecar; dirtiness via palette-insensitive `structuresDiffer` | `StructurePacketsTest."DiscardStructureC2S codec round-trips"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"`, `StructureDiffTest."identical structures are not different"`, `StructureDiffTest."palette reordering with remapped indices is not different"`, `StructureDiffTest."a changed block state is different"`, `StructureDiffTest."a changed size is different"`, `StructureDiffTest."unsavedSidecarOf appends .unsaved adjacent to the file"` | covered |
| UC-MAN-10.g | Client "Discard" control sends `DiscardStructureC2S(selectedPath)` and dims unless `ProjectTreeState.selectedHasUnsaved()`; dirty dot prefixes a `.nbt` `FileNode` label when `hasUnsaved` | `StructureExplorerSpec."selectedHasUnsaved reflects the dirty flag on the selected .nbt node"` | covered |
| UC-PER-06 | Capture and restore structure sidecar (`.nbt`) | `StructureSidecarPersistenceSpec` | covered |
| UC-PER-06.a | `StructurePersistence.save` builds template, serialises, writes NBT | `StructureSidecarPersistenceSpec."UC-PER-06: save captures the region and load restores it byte-for-byte at the origin"` | covered |
| UC-PER-06.b | `StructurePersistence.load` reads NBT and places blocks with `placeInWorld` | `StructureSidecarPersistenceSpec."UC-PER-06: save captures the region and load restores it byte-for-byte at the origin"` | covered |
| UC-PER-06.c | `hasChanges` false when live == saved, true on byte diff and on absent file | `StructureSidecarPersistenceSpec."UC-PER-06.c: hasChanges is false right after save and true after a block edit"`, `StructureSidecarPersistenceSpec."UC-PER-07.c: hasChanges treats an absent .nbt as changed"` | covered (read-error branch untested) |
| UC-PER-06.d | `clearBounds` sets region to AIR before placement | `StructureSidecarPersistenceSpec."UC-PER-06: save captures the region and load restores it byte-for-byte at the origin"` (asserts region is AIR after clear) | covered |
| UC-PER-07 | Handle sidecar drift (script present, `.nbt` missing or stale) | `StructureSidecarPersistenceSpec` | covered |
| UC-PER-07.a | Missing `.nbt`: `load` logs WARN and returns without placing | `StructureSidecarPersistenceSpec."UC-PER-07.a: load of a missing .nbt is a no-op that leaves the region untouched"` | covered |
| UC-PER-07.b | Unreadable `.nbt`: `IOException` caught, no throw | `StructureSidecarPersistenceSpec."UC-PER-07.b: load of a corrupt .nbt is caught and does not throw"` | covered |
| UC-PER-07.c | `hasChanges` returns `true` on absent file, read error, or byte mismatch | `StructureSidecarPersistenceSpec."UC-PER-07.c: hasChanges treats an absent .nbt as changed"`, `StructureSidecarPersistenceSpec."UC-PER-06.c: ..."` | covered (read-error branch untested) |
| UC-PER-07.d | `RecordingSidecar.load` returns `null` when sidecar absent | `RecordingSidecarTest."load returns null when sidecar absent"`, `SpecPersistenceTest."no sidecar without explicit save"` | covered |

**Residual gap:** the `IOException`→`true` branch of `StructurePersistence.hasChanges` (a `.nbt` that
exists but fails to read) is the only untested edge on the spec-cell path — the absent-file and
byte-diff branches are both covered.
