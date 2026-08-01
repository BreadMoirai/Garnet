---
title: Structure lifecycle use-cases
tags: [structure, nbt, autosave, save, history, use-cases]
summary: Saving/loading `.nbt` structures, standalone-`.nbt` debounced auto-save with local history, across both the spec-cell sidecar and the standalone-Explorer path.
---

# Structure lifecycle use-cases

Everything about turning a live in-world block region into an on-disk `.nbt` structure and back:
capture, place, dirty-state tracking, explicit save, and revert. Two code paths write and read
`.nbt` files, and they are deliberately different:

| Path | Origin / bounds | Written by | Read by | Home UCs |
|---|---|---|---|---|
| **Spec-cell sidecar** | fixed origin + fixed `bounds` (`fillFromWorld`/`placeInWorld` 1:1) | `StructurePersistence.save` | `StructurePersistence.load` | UC-PER-06, UC-PER-07 |
| **Standalone Explorer file** | auto-fit tight box within a region lane, re-centered on place | `StructurePersistence.saveAutoFitToFile` | `StructurePersistence.placeStructureCentered` | UC-MAN-10 |

**Dirty-state** is a first-class concept only on the standalone path, and there is no dirty-buffer
sidecar: an in-progress edit is tracked in-memory by `StructureAutoSave` (fed by the setBlock-mixin
watcher), and `StructureCommit` writes the real `<name>.nbt` directly once the edit debounce
elapses, recording a `LocalHistoryStore` revision immediately before the rewrite. `BEFORE_SAVE`,
`ServerTickEvents.END_SERVER_TICK`, and `SERVER_STOPPED` all drive `StructureCommit`; "Save
Structure" (`SaveStructureC2S`) is a force-commit through the identical path. There is no "Discard"
— since a placed structure auto-saves continuously, there is nothing to revert to except through
`LocalHistoryStore`. See
[architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files),
`docs/persistence/local-history.md`, and
`docs/superpowers/specs/2026-07-31-structure-autosave-local-history-design.md` for the design
history.

The **grid-cell save-back** (UC-MAN-07) is a third, related mechanism — it diffs a spec cell's live
volume against a captured baseline and rewrites the spec's `.nbt` — but it lives with the
grid-projection journeys in [redstone-project.md](redstone-project.md#uc-man-07--save-edited-cell-blocks-back-to-disk).

---

## Standalone `.nbt` structures (UC-MAN-10)

*(Moved here from [redstone-project.md](redstone-project.md); the IDs are unchanged.)*

A `.nbt` file in the Explorer is a first-class citizen independent of any spec: clicking it places
the structure, editing it auto-saves through a debounced `StructureCommit`, "Save Structure" forces
an immediate commit, and "+ Structure" creates a new empty one. **Save/New have no client UI
trigger** — the `ExplorerToolbar`'s single row is just a kebab overflow menu (Open Folder),
Refresh, and Collapse All (see [architecture/redstone-project.md](../architecture/redstone-project.md)).
There is no "Discard" any more: a placed structure auto-saves continuously, so there is nothing to
discard back to (recovery goes through `LocalHistoryStore`, see `docs/persistence/local-history.md`).
The place/save packets and handlers below are still fully covered by `EditorStructureNetworkSpec`;
`handleNewStructure` and `handleCreateFolder` resolve their target folder from the payload's
`parentSubpath` instead of the session's active folder (see UC-MAN-10.d).

### UC-MAN-10 — Place, save, and create standalone structure files

- **UC-MAN-10.a** Clicking a `.nbt` `FileNode` in `ProjectExplorerPanel` sends `PlaceStructureC2S(path)`. `EditorNetworking.handlePlaceStructure` resolves the subpath, seeds a `LocalHistoryStore` baseline revision (reason `REASON_PLACED`) the first time a structure is placed so a rollback target exists even before the first edit, then delegates to the shared `placeStructureFrom` helper: assigns/reuses a region via `EditorDimRegistry.getOrAssignStructureRegion` (a disjoint +X lane at `z = STRUCTURE_LANE_Z`), clears only the previously-placed footprint (`placedBoxOf` → `StructurePersistence.clearBounds`), calls `StructurePersistence.placeStructureCentered`, and records the new `PlacedBox` via `setPlacedBox`. A non-`.nbt` subpath and a corrupt/unreadable `.nbt` each reply with `EditorErrorS2C` rather than throwing.
- **UC-MAN-10.b** `StructureRegionMath.centeredStart`/`anchorY` center the structure in the region and floor it at `SharedSettings.projectGridYBase` (64), or vertically center it when the structure's height is at or above `TALL_THRESHOLD` (256).
- **UC-MAN-10.c** `SaveStructureC2S(path)` is a force-commit: `handleSaveStructure` refuses (with `EditorErrorS2C`) unless the structure was placed this session (`placedBoxOf(subpath) != null`), then calls `StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_MANUAL)` — the identical engine that drives debounced auto-save. A commit that finds no change (the region already matches the committed `.nbt`) replies with a `StructureResultS2C` "no changes to save" message instead of broadcasting; a real write records a `LocalHistoryStore` revision before rewriting the `.nbt` and broadcasts `StructureAutoSavedS2C` to every player.
- **UC-MAN-10.d** `NewStructureC2S(parentSubpath, name)` — `handleNewStructure` resolves `parentSubpath` via `EditorRoot.resolveSubpath` (`""` = the project root; the session's active folder is not read), re-validates the final name through `EditorNames.resolveFinalName`/`EditorNames.validate` against the destination folder's real contents, then calls `EditorNewStructure.create(folder, name)` — which writes an empty `<name>.nbt` into that folder — and re-sends the project tree. A name collision or invalid name replies with `EditorErrorS2C` instead of writing. `CreateFolderC2S(parentSubpath, name)` follows the identical resolve/validate path through `handleCreateFolder`, creating a plain directory instead of a `.nbt` file.
- **UC-MAN-10.e** `handlePlaceStructure`/`handleNewStructure` reply with `StructureResultS2C(subpath, sizeX, sizeY, sizeZ, message)` — there is no `hasUnsaved` field any more; `EditorClientNetworking` feeds it to `ProjectTreeState.onStructureResult`, which sets `status = message`, the same status line used by folder load/save results. Every commit — debounced auto-save or a forced `SaveStructureC2S` — instead replies/broadcasts `StructureAutoSavedS2C(subpath, sizeX, sizeY, sizeZ, blockCount, savedAtMillis)`.
- **UC-MAN-10.f** `StructureCommit` is the sole auto-persist path: `ServerTickEvents.END_SERVER_TICK` calls `StructureCommit.tick`, which commits any placed structure whose `StructureAutoSave` dirty state (fed by the setBlock-mixin watcher) has debounced or hit the max-dirty cap; `ServerLifecycleEvents.BEFORE_SAVE` and `SERVER_STOPPED` both call `StructureCommit.commitAll` as a backstop regardless of timing. A commit scans only `union(placedBox, dirtyBox)` via `StructurePersistence.captureAutoFitIn` — never the whole region — writes the `.nbt` directly, records a `LocalHistoryStore` revision immediately before the rewrite (so the pre-edit content survives even if the `.nbt` write itself then fails), and clears the dirty state only once the `.nbt` is confirmed correct. An edit the setBlock-mixin watcher never sees (a 4-arg `setBlock` or a direct `LevelChunk.setBlockState` write) is invisible to this path and will not be captured by anything until a later edit inside the structure's placed footprint brings it back into `union(placedBox, dirtyBox)` — there is no longer a watcher-independent backstop for that gap (the old `.nbt.unsaved`-sidecar flush that covered it was removed with the sidecar model).
- **UC-MAN-10.g** *(removed)* Was `DiscardStructureC2S(selectedPath)` + `ExplorerTreeState.selectedHasUnsaved()`/dirty-dot logic. There is no `DiscardStructureC2S` any more, `FileNode` carries no dirty flag, and `ExplorerTreeState.selectedHasUnsaved()` was deleted along with it — a placed structure auto-saves continuously, so there is no "unsaved" state to select or discard.
- **UC-MAN-10.h** `RenamePathC2S(subpath, newName)` (Explorer's right-click "Rename") → `handleRename`: resolves `subpath` via `EditorRoot.resolveSubpath` and refuses `subpath == ""` (the client disables the menu item for the root, but the server does not trust that), re-validates `newName` via `EditorNames.validate` against the parent's real sibling names (excluding the node's own current name, so re-committing an unchanged name is a no-op rather than a self-collision). If the renamed node is itself a currently-placed structure, `handleRename` first commits any pending auto-save edits under the OLD subpath via `StructureCommit.commit(server, subpath, LocalHistoryStore.REASON_AUTOSAVE)` — this must happen BEFORE the file move, since the dirty state is keyed by subpath and moving first would strand it under a name `StructureCommit` will never resolve again. The `.nbt` file move happens next, inside a `try`, immediately followed by `LocalHistoryStore.moveHistory(source, target)` to carry the structure's revision history across (history is keyed by the file's own absolute path, so a rename that didn't move it would silently lose every prior revision). Every registry mutation below only runs in the success path afterward, so a failed move (lock, permission, full disk) is a true no-op — file, registry, and world are all left exactly as they were, and any commit that already landed before the failed move simply stays committed under the old subpath. If the renamed node is itself a currently-placed structure (`EditorDimRegistry.placedBoxOf(subpath) != null`), it clears that footprint (`StructurePersistence.clearBounds`) and calls `EditorDimRegistry.unplaceStructure(subpath)` — dropping both the `placedBoxes` and `structureBySubpath` entries — then re-places it under the new subpath via `placeStructureFrom`. The structure lands in a freshly-assigned region (`nextStructureIndex` is monotonic and never recycled) rather than reusing the old one — this is intended, matching how every other region assignment in the registry behaves. Separately, `EditorDimRegistry.rekeyForRename(subpath, newSubpath)` rewrites every OTHER registry entry (across `bySubpath`, `structureBySubpath`, `placedBoxes`) whose key is `subpath` or nested under it (`"$subpath/"` prefix) onto the new subpath — this is what keeps a structure placed *inside* a renamed folder reachable: without it, `StructureCommit` would silently skip it forever and a click on the new path would re-place a second copy in a fresh region. Rekeying is pure bookkeeping — it never touches the world, since only the file's path changed, not its placed position. If `EditorSession.activeSubpath` equals the renamed subpath, or is nested under it, the session is repointed onto the new subpath (`repointSession`, boundary-safe via a `"$oldSubpath/"` prefix match — renaming `redstone` repoints `redstone/clocks` but not an unrelated sibling like `redstoneworks/clocks`). On success the tree is re-sent; any failure (name collision, filesystem error) replies with `EditorErrorS2C` and leaves the source untouched.

---

## Spec-cell structure sidecar (UC-PER-06 / UC-PER-07)

*(Moved here from [persistence.md](persistence.md); the IDs are unchanged.)* This is the exact-origin,
fixed-`bounds` path used by the recording-finalize save and the runner's pre-execution restore. It
does **not** auto-fit or re-center — it captures and replaces a region 1:1 at a caller-supplied
origin.

### UC-PER-06 — Capture and restore a structure sidecar (`.nbt`)

**Actor:** System (no live product caller today — the recorder-finalize save and the
runner-pre-execution restore this describes both went with the deleted recorder/runner blocks;
`StructurePersistence.save`/`load` are exercised only by `StructureSidecarPersistenceSpec` now)
**Trigger:** Directly calling `StructurePersistence.save`/`load`. Historically: the editor saving
a spec and the associated circuit region needing persistence; or the runner about to execute a
spec and needing to restore the initial block state.
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
| UC-MAN-10 | Place, save, and create standalone structure files | `EditorStructureNetworkSpec` | covered |
| UC-MAN-10.a | `PlaceStructureC2S` → `handlePlaceStructure` seeds a `LocalHistoryStore` baseline revision, assigns/reuses a structure region, cheap-re-clears the prior footprint, places via `placeStructureFrom`, records the new `PlacedBox`; non-`.nbt` and corrupt `.nbt` reply with an error | `EditorStructureNetworkSpec."place then save round-trips a standalone structure via handlers"`, `EditorStructureNetworkSpec."place rejects a non-.nbt subpath"`, `EditorStructureNetworkSpec."placing a corrupt .nbt replies with an error instead of throwing"`, `EditorStructureNetworkSpec."editing a placed structure and force-saving commits straight to the .nbt"`, `EditorDimRegistryTest."getOrAssignStructureRegion is idempotent and distinct per subpath"`, `EditorDimRegistryTest."structure regions sit in a lane disjoint from spec-folder regions"`, `EditorDimRegistryTest."placed-box round-trips per subpath"` | covered |
| UC-MAN-10.b | `centeredStart`/`anchorY` center in-region and floor/vertically-center by height | `StructureRegionMathTest."centeredStart centers a box in a region (floor-divides odd slack)"`, `StructureRegionMathTest."anchorY floors short structures at yBase"`, `StructureRegionMathTest."anchorY vertically centers structures at or above the tall threshold"` | covered |
| UC-MAN-10.c | `SaveStructureC2S` → `handleSaveStructure` refuses unless placed this session, force-commits through `StructureCommit.commit` (reason `REASON_MANUAL`), which auto-fits the dirty region and rewrites the `.nbt`; no change writes nothing | `StructureRegionPersistenceSpec."auto-fit save captures the tight non-air box; place re-centers it"`, `StructureRegionPersistenceSpec."auto-fit save of an empty region writes a file and returns null"`, `StructureRegionMathTest."autoFit tightly boxes scattered non-air cells"`, `StructureRegionMathTest."autoFit returns null when the volume has no non-air"`, `EditorStructureNetworkSpec."save without placing this session is refused and does not touch the file"`, `EditorStructureNetworkSpec."editing a placed structure and force-saving commits straight to the .nbt"` | covered |
| UC-MAN-10.d | `NewStructureC2S`/`CreateFolderC2S` → `handleNewStructure`/`handleCreateFolder` resolve `parentSubpath` via `EditorRoot.resolveSubpath`, re-validate the name via `EditorNames`, write the `.nbt`/directory, and re-send the tree | `EditorStructureNetworkSpec."new structure creates the file and re-sends the tree"`, `EditorFileOpsNetworkSpec."handleCreateFolder creates a folder at the project root"`, `EditorFileOpsNetworkSpec."handleCreateFolder creates a nested folder"`, `EditorFileOpsNetworkSpec."handleCreateFolder rejects a parent that escapes the root"`, `EditorFileOpsNetworkSpec."handleCreateFolder rejects a name containing a separator"`, `EditorFileOpsNetworkSpec."handleNewStructure creates in the named folder, not the session's active folder"`, `EditorFileOpsNetworkSpec."handleNewStructure creates at the project root for an empty parent"` | covered |
| UC-MAN-10.e | `StructureResultS2C`/`StructureAutoSavedS2C` codecs and status-line wiring | `EditorStructurePacketsTest."StructureResultS2C codec round-trips"`, `EditorStructurePacketsTest."PlaceStructureC2S codec round-trips"`, `EditorStructurePacketsTest."SaveStructureC2S codec round-trips"`, `EditorStructurePacketsTest."NewStructureC2S codec round-trips"`, `StructureExplorerSpec."onStructureResult surfaces the message as Explorer status"`, `StructureExplorerSpec."an auto-save result lands in the Explorer status line"` | covered |
| UC-MAN-10.f | `StructureCommit.tick`/`commitAll` (the sole auto-persist path) commit any structure whose `StructureAutoSave` dirty state has debounced or hit the max-dirty cap, scanning only `union(placedBox, dirtyBox)`; an edit the setBlock-mixin watcher never saw is invisible to this path — there is no watcher-independent backstop any more | `StructureAutoSaveSpec."an edit marks the structure dirty and the dirty box grows to enclose every edit"`, `StructureAutoSaveSpec."a commit writes the .nbt, records a revision, and clears the dirty state"`, `StructureAutoSaveSpec."tick commits a due structure and skips one that is not due"`, `StructureAutoSaveSpec."a commit's capture is bounded to union(placedBox, dirtyBox), not the whole region"` | covered |
| UC-MAN-10.g | *(removed)* `DiscardStructureC2S(selectedPath)` + `selectedHasUnsaved()`/dirty-dot logic — deleted along with the sidecar model; there is nothing to discard once a structure auto-saves continuously | n/a | n/a |
| UC-MAN-10.h | `RenamePathC2S` → `handleRename`: resolves/validates, refuses renaming the root, commits pending edits under the OLD subpath BEFORE the move (so the dirty state is never stranded), moves the `.nbt` and carries its `LocalHistoryStore` revisions across via `moveHistory`, unloads and re-places a currently-placed structure under the new subpath (landing in a fresh region), rekeys every OTHER registry entry nested under a renamed folder via `EditorDimRegistry.rekeyForRename`, and repoints `EditorSession.activeSubpath` boundary-safely for a renamed ancestor folder | `EditorFileOpsNetworkSpec."handleRename renames a folder"`, `EditorFileOpsNetworkSpec."handleRename rejects a new name that already exists"`, `EditorFileOpsNetworkSpec."handleRename rejects a new name containing a separator"`, `EditorFileOpsNetworkSpec."renaming a placed structure unloads it and reloads it under the new name"`, `EditorFileOpsNetworkSpec."renaming a placed AND dirty structure commits first, so no edits are lost"`, `EditorFileOpsNetworkSpec."renaming a folder rekeys every structure placed beneath it onto the new subpath"`, `EditorFileOpsNetworkSpec."renaming an ancestor folder repoints the active session"` | covered |
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
