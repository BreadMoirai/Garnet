---
title: Redstone project use-cases
tags: [redstone-project, dimensions, grid, datapack, use-cases]
summary: Per-folder void-dim workspace via runtime datapack; deterministic grid; per-spec save-back; standalone .nbt structure place/save/create.
last_audited_commit: 4c849243e4ce68ca5147bd2a16ce057664baa8fd
---

# Redstone project use-cases

A project root is a folder of `.spec.kts` files projected into a runtime-generated void dimension as a deterministic grid. Each spec gets a cell; edits in the cell save back to the spec file (see [architecture/redstone-project.md](../architecture/redstone-project.md)).

**UI status:** `ProjectScreen` and `ProjectRootListScreen` were deleted in the Compose-dock hard-cut
(see [ui/dock-framework.md](../ui/dock-framework.md)). The only reachable client entry point today is
`TitleScreenMixin`'s "Redstone Projects…" button, which calls `ProjectIntegratedBoot.bootWorkspace()`
directly — a single shared `redstonespecs-workspace` save with no root pinned. The multi-root
registration store (`ProjectRootsConfig`) and the per-root boot entry (`ProjectIntegratedBoot.boot(rootPath)`)
were **deleted as dead code** once the title button was retargeted to `bootWorkspace()` — nothing
referenced them outside their own unit tests. UC-MAN-01 and UC-MAN-02.a below are retained only as
historical notes on that removed machinery. UC-MAN-06
("New Spec"), UC-MAN-07 ("Save Now"), and UC-MAN-08 ("Unload") describe buttons that lived on the
deleted `ProjectScreen`; their server handlers are unchanged and reachable by sending the C2S payload
directly, but the Compose `ProjectExplorerPanel` (the only live LEFT-dock panel today) does not yet
expose New Spec/Save Now/Unload actions — only browse/load/refresh.

---

### UC-MAN-01 — Declare and persist a project root *(removed)*

Registering a filesystem folder as a persistent project-spec root is no longer a capability: the
store that backed it (`ProjectRootsConfig`, a JSON list under `<MC-config-dir>/redstonespecs/`) and
its unit test were deleted as dead code — see the UI-status note above. The title button boots a
single shared workspace via `bootWorkspace()`; there is no per-root registration or persisted root
list. The only piece of this area that survives is the path-safety guard the live network handlers
still rely on:

- **UC-MAN-01.e** `ProjectRoot` enforces that every path is absolute and rejects path-traversal attempts via `resolveSubpath`: a relative or escaping subpath returns `null` (symlink-defeat via `toRealPath` before `startsWith` check). This guard is exercised regardless of caller (still used by `resolveSubpath` calls from the live `ProjectNetworkRegistry` handlers).

---

### UC-MAN-02 — Boot the project singleplayer world

The title button boots a single shared workspace via `ProjectIntegratedBoot.bootWorkspace()`, which
opens or creates the fixed `redstonespecs-workspace` save with no root pinned, using the private
`openOrCreateWorld` helper. The per-root boot entry that used to derive a `project-<tail>-<hash>` save
name was removed; the `openOrCreateWorld` helper and the (now dormant) `pendingRoot`/`ProjectServerContext`
pinning machinery remain.

- **UC-MAN-02.a** *(removed)* The per-root `boot(rootPath)` entry — which derived a save name via `ProjectSaveNaming.saveName` (`project-<sanitized-tail>-<8-hex-sha1>`, so two roots with the same final component got distinct save names) and set `pendingRoot` to pin a `ProjectServerContext` — was deleted as dead code. `ProjectSaveNaming` itself survives as a pure function with a unit test, but is no longer wired to any boot path.
- **UC-MAN-02.b** `ProjectIntegratedBoot.openOrCreateWorld` calls `Minecraft.levelSource.levelExists(saveName)`. If the save exists, `WorldOpenFlows.openWorld` reopens it; otherwise `WorldOpenFlows.createFreshLevel` creates a creative, peaceful, allow-commands, flat-void world (overworld replaced with `FlatLevelGeneratorPresets.THE_VOID` via `FlatLevelSource`).
- **UC-MAN-02.c** *(dormant)* `ProjectIntegratedBoot`'s own `SERVER_STARTING` listener (registered once by `ensureListenersRegistered`, which `bootWorkspace` still calls) reads the `pendingRoot` `AtomicReference` and, if set, calls `ProjectServerContext.set(server, ProjectServerContext(root))`. No caller sets `pendingRoot` anymore, so this listener is a no-op. The live root-pinning path is `Redstonespecs`' own `SERVER_STARTING` listener, which pins a `ProjectServerContext` from the `SharedSettings.projectRootPath` config when set.
- **UC-MAN-02.d** A `SERVER_STARTED` listener in the mod entrypoint reads `ProjectServerContext.get(server)` and, if present, calls `ProjectDimLifecycle.placeAll(server, root)` to lay out the full tree; any prior `ProjectWorld` for the same root is reused, or a new one is created and stored via `ProjectWorld.set`.
- **UC-MAN-02.e** Re-opening the same root from a future session reopens the same persistent save; scratch blocks placed outside spec bounds between the previous close and this open are still present, because only cell AABB contents are re-placed from disk.

---

### UC-MAN-03 — Scan the folder tree and assign regions

On `placeAll`, the mod walks the project root's directory tree, classifies folders as leaves or intermediates, and assigns each leaf a non-overlapping region in the overworld.

- **UC-MAN-03.a** `ProjectFolderTree.scan(root)` walks the tree with `Files.walk`, collecting all directories that contain at least one `.spec.kts` file as `ProjectLeaf` entries and all directories that contain sub-directories as `intermediates`. Both collections are sorted by subpath for deterministic ordering.
- **UC-MAN-03.b** `ProjectDimLifecycle.placeAll` iterates `tree.leaves.sortedBy { it.subpath }` and calls `placeFolderInto` for each leaf. Sorting guarantees that region-index assignment via `ProjectDimRegistry.getOrAssignRegion` is stable across server restarts (even though the registry is in-memory ephemeral).
- **UC-MAN-03.c** `ProjectDimRegistry.getOrAssignRegion(subpath)` increments an `AtomicInteger` counter and computes `BlockPos(idx * regionWidth, projectGridYBase, 0)`. `regionWidth` is derived from `SharedSettings` (`cellSize.x * rowMax + cellGap * (rowMax + 1) + REGION_PAD=64`) so regions never collide regardless of how large a folder grid grows.
- **UC-MAN-03.d** `projectLevel()` returns `server.overworld()` directly — no custom dimension type or datapack is registered; all project grids coexist in the integrated server's overworld.
- **UC-MAN-03.e** `ProjectDimRegistry` is a `WeakHashMap`-keyed singleton per `MinecraftServer`; `dispose(server)` is called on server stop to release the reference.

---

### UC-MAN-04 — Lay out and place spec cells in the grid

Each leaf folder's specs are sorted, assigned to a row-major grid slot, and physically placed as structure NBT + anchor blocks in the overworld at the folder's region origin.

- **UC-MAN-04.a** `GridLayout.compute` accepts sorted `LayoutInput` entries (sorted case-insensitively by filename, then by `spec.id`) and places each into slot `(slotIndex % rowMax, slotIndex / rowMax)`. Origin is `BlockPos(sx*(cellSize.x+gap), yBase, sz*(cellSize.z+gap))` — all offsets are region-relative.
- **UC-MAN-04.b** Any spec whose `bounds` exceeds `cellSize` on any axis is excluded with a `LayoutError`; it does not consume a slot and its error is reported in `LoadFolderReport.errors`.
- **UC-MAN-04.c** `placeCell` reads `<spec.structure ?: spec.id>.nbt` beside the spec file. If the file exists it loads the `StructureTemplate` via `NbtIo.readCompressed` and calls `tpl.placeInWorld`. If it does not exist the cell is placed empty (new spec path).
- **UC-MAN-04.d** An anchor block (`REDSTONE_SPEC_RUNNER_BLOCK` for existing structure, `REDSTONE_SPEC_RECORDER_BLOCK` for new) is placed at `absOrigin.offset(spec.bounds.x, 0, 0)` and its `SpecBlockEntity.projectSourcePath` is set to the spec file path. This binding is not persisted to NBT and is reset on every `placeFolder`.
- **UC-MAN-04.e** After placement, `StructureTemplate.fillFromWorld` captures the cell volume as `loadedSnapshot` and stores it in `LoadedSpec`. This snapshot is the dirty-diff baseline used by `ProjectCellSaver`.
- **UC-MAN-04.f** `world.perFolder[subpath]` is replaced atomically (via `ConcurrentHashMap`) with the newly placed specs so that stale entries from a prior placement of the same subpath are not visible.

---

### UC-MAN-05 — Browse the folder tree in-game and teleport to a folder

A player selects a leaf folder from the in-game UI, which teleports them to that folder's region and marks it as their active focus.

- **UC-MAN-05.a** `/redstonespecs project` immediately sends `ListProjectTreeC2S`. `ProjectNetworkRegistry.handleListTree` (via private `sendTree`) calls `scanFolder(root.path)` on the server and replies with `ProjectTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` carrying the full recursive folder tree (every file/folder, not just spec leaves) and the player's current `activeSubpath`.
- **UC-MAN-05.b** `ProjectClientNetworking` receives `ProjectTreeSnapshotS2C` and feeds it into `ProjectTreeState.onSnapshot(payload)`. The Compose Project Explorer (`ProjectExplorerPanel` in the LEFT dock) reads `ProjectTreeState.snapshot` and recursively renders `snapshot.root.children`, recomposing on change. This is the **only** client-side reaction to the snapshot — the legacy `ProjectScreen`, which used to auto-rebuild on snapshot, was deleted in the Compose-dock hard-cut. See [ui/dock-framework.md](../ui/dock-framework.md) for the recursive render pattern and expand/select state.
- **UC-MAN-05.c** Clicking a "spec-folder" row (a folder directly containing a `*.spec.kts` file) sends `LoadProjectFolderC2S(path)`. `ProjectNetworkRegistry.handleLoadFolder` validates the subpath via `root.resolveSubpath` (path-traversal guard), calls `ProjectTeleport.toFolder`, and sends `ProjectFolderLoadedS2C` with the spec-id list and any errors. Clicking a non-spec folder or its expand triangle just toggles expand client-side (no packet); clicking a file row selects/highlights it client-side (no packet).
- **UC-MAN-05.d** `ProjectTeleport.toFolder` looks up `ProjectDimRegistry.regionOriginOf(subpath)`, teleports the player to `(region.x+0.5, yBase+2, region.z+0.5)` in `projectLevel()`, and calls `ProjectSession.setActive(player.uuid, subpath)` so subsequent server actions (save, new-spec) scope to the right folder.
- **UC-MAN-05.e** If the subpath's region has not been assigned (folder not yet placed), `toFolder` returns `false` and the server replies with `ProjectErrorS2C`. `ProjectTreeState.onError(payload)` sets `status = "error: ${payload.reason}"`, which the Explorer panel renders as its status line — the same mechanism that used to update `ProjectScreen`'s status label.

---

### UC-MAN-06 — Create a new spec cell in the active folder

A player names a new spec from the in-game UI; the server writes a stub `.spec.kts`, re-places the folder, and the new cell appears in the world. **No live UI currently sends `NewProjectSpecC2S`** — the deleted `ProjectScreen` had the "New Spec" `EditBox`/button; `ProjectExplorerPanel` (the current LEFT-dock panel) does not yet have an equivalent control. The server handler and packet are otherwise unchanged and reachable by sending the payload directly (as the coverage tests below do).

- **UC-MAN-06.a** *(historical UI path)* The player types a name into the `EditBox` in the deleted `ProjectScreen` (validated client-side for non-blank) and clicks "New Spec", sending `NewProjectSpecC2S(name)`.
- **UC-MAN-06.b** `ProjectNetworkRegistry.handleNewSpec` reads `ProjectSession.get(player.uuid)?.activeSubpath`; if no folder is active it replies with `ProjectErrorS2C("no folder selected")`.
- **UC-MAN-06.c** `ProjectNewSpec.create(folder, name)` enforces that `name` matches `[a-zA-Z0-9_-]+`, that the target file does not already exist, then writes a minimal stub via `RecordingDslEmitter.emitStub(name)`. Throws on any violation so the caller can catch and report.
- **UC-MAN-06.d** After creating the stub, `handleNewSpec` calls `ProjectDimLifecycle.placeFolder(server, root, activeSubpath)` to re-scan and re-place the entire folder. The new file appears as an empty cell with a `REDSTONE_SPEC_RECORDER_BLOCK` anchor.
- **UC-MAN-06.e** `ProjectFolderLoadedS2C` is sent back with the updated spec-id list and any parse/layout errors from the re-place.

---

### UC-MAN-07 — Save edited cell blocks back to disk

When the player has modified blocks inside a spec's cell AABB, the server detects the diff and overwrites the source `.spec.kts` structure file. **No live UI currently sends `SaveNowC2S`** — the deleted `ProjectScreen` had the "Save Now" button; `ProjectExplorerPanel` does not yet have an equivalent control. The server handler is otherwise unchanged and reachable by sending the payload directly.

- **UC-MAN-07.a** *(historical UI path)* The player clicks "Save Now" in the deleted `ProjectScreen`, sending `SaveNowC2S`. `ProjectNetworkRegistry.handleSaveNow` calls `ProjectDimLifecycle.saveAll(server)`, which iterates every subpath in `ProjectWorld.perFolder` and calls `saveFolder`.
- **UC-MAN-07.b** `saveFolder` resolves each spec's absolute cell origin via `ProjectWorld.absoluteCellOrigin` (adds `regionOrigin.x/z` to the region-relative `cell.origin.x/z`; Y from `cell.origin.y` is already absolute). It then passes `level`, `loaded`, and `absoluteCellOrigin` to `ProjectCellSaver.captureAndSaveIfDirty`.
- **UC-MAN-07.c** `ProjectCellSaver` captures the live cell volume with `StructureTemplate.fillFromWorld`, serializes both live and baseline snapshots to `CompoundTag`, and returns `CellSaveResult(saved=false)` if the NBT is equal (no-op). Only a structural change in block data triggers the rewrite.
- **UC-MAN-07.d** On a dirty diff, `NbtIo.writeCompressed(liveNbt, structureFile)` overwrites `<structureId>.nbt`. The `.spec.kts` source re-emission is deferred (noted in `ProjectCellSaver`); `RecordingDslEmitter` will handle it in a later phase.
- **UC-MAN-07.e** After a successful save, `saveFolder` captures a fresh `StructureTemplate` and stores it as the new `loadedSnapshot` in `ProjectWorld.perFolder`, so subsequent saves have an accurate baseline.
- **UC-MAN-07.f** `ProjectSaveReportS2C` is sent back to the player with per-spec `"specId|saved=true/false[|err=…]"` strings; `ProjectTreeState.onSaveReport` sets `status = "saved N spec(s)"`, which the Explorer panel renders as its status line (the same mechanism `ProjectScreen.onSaveReport` used before deletion).

---

### UC-MAN-08 — Unload active folder and handle ungraceful session end

A player explicitly unloads their active folder focus, or the session is cleared when the player disconnects or the server stops. **No live UI currently sends `UnloadProjectFolderC2S`** — the deleted `ProjectScreen` had the "Unload" button; `ProjectExplorerPanel` does not yet have an equivalent control. The server handler is otherwise unchanged.

- **UC-MAN-08.a** *(historical UI path)* The player clicks "Unload" in the deleted `ProjectScreen`, sending `UnloadProjectFolderC2S`. `ProjectNetworkRegistry.handleUnload` calls `ProjectSession.clear(player.uuid)` and replies with `ProjectSaveReportS2C(emptyList())`.
- **UC-MAN-08.b** After unload, the player's `activeSubpath` is `null`; a subsequent `handleNewSpec` (which requires a folder focus) receives `ProjectErrorS2C("no folder selected")`. `handleSaveNow` is deliberately session-independent — it calls `ProjectDimLifecycle.saveAll(server)` over every loaded folder in `ProjectWorld.perFolder`, so post-unload it still returns a `ProjectSaveReportS2C` (empty when nothing is loaded), not an error.
- **UC-MAN-08.c** If a player disconnects without clicking Unload (ungraceful exit), `ProjectSession.clear(player.uuid)` must be called from the server-side player disconnect event. The `ProjectSession` map is a `ConcurrentHashMap` keyed by `UUID`; the player's slot is released so it does not linger after reconnect.
- **UC-MAN-08.d** On server stop, a `SERVER_STOPPED` listener (registered in `Redstonespecs.onInitialize`) calls `ProjectDimLifecycle.releaseServerState(server)`, which invokes `ProjectDimRegistry.dispose(server)`, `ProjectWorld.clear(server)`, and `ProjectServerContext.clear(server)` — removing each `WeakHashMap` entry for the server and releasing all server-scoped state promptly rather than waiting for GC.
- **UC-MAN-08.e** Spec cell blocks persist in the singleplayer save across sessions, but on next `placeAll` the cell is **rebuilt from disk, not preserved**: when the `.nbt` exists, `placeCell` calls `StructureTemplate.placeInWorld` over the cell AABB (`ProjectDimLifecycle.placeCell`), overwriting whatever was there, then `fillFromWorld` snapshots that freshly-placed structure as the new `loadedSnapshot` baseline. So **only changes previously saved to `.nbt` persist; un-saved in-world edits inside the cell are discarded on the next placement.** Two exceptions: a brand-new spec with no `.nbt` yet is placed empty (nothing to overwrite), and blocks *outside* the cell AABB are never re-placed and survive (see UC-MAN-02.e). "Save Now" (UC-MAN-07) is the mechanism for persisting edits; there is no auto-save on disconnect.

---

### UC-MAN-09 — Re-root the Explorer from a native folder picker

A player opens the Explorer header's option button, chooses **Open Folder**, and picks a folder
in the OS dialog; the workspace root switches to it. **Attach Folder** is present but disabled
(multi-root is Plan B).

- **UC-MAN-09.a** Clicking the option button toggles `RootPickerController.menuOpen`, rendering
  the hand-rolled `RootMenu` overlay. **Open Folder** calls `RootPickerController.openFolder`,
  which runs the injectable `FolderPicker` (default `TinyfdFolderPicker` →
  `TinyFileDialogs.tinyfd_selectFolderDialog`) on a worker thread.
- **UC-MAN-09.b** On a non-null pick, the controller normalizes the path to absolute (matching
  the server's canonical form) and persists it client-side (`ModConfig.projectRootPath` →
  `redstonespecs.json`, also mirrored to `SharedSettings.projectRootPath`) and sends
  `SetProjectRootC2S(path)` on the client thread via `Minecraft.execute`. A cancel (null) sends
  nothing. **Persistence is client-side only:** in singleplayer/LAN the integrated server shares
  the JVM so the choice is restored via `ModConfig.load()`; a dedicated-server root swap is not
  durable across restart.
- **UC-MAN-09.c** `ProjectNetworkRegistry.handleSetRoot` rejects a non-directory / invalid path
  with `ProjectErrorS2C`; otherwise it sets `SharedSettings.projectRootPath`, pins a new
  `ProjectServerContext`, re-runs `ProjectDimLifecycle.placeAll`, and re-sends the single-root
  `ProjectTreeSnapshotS2C`. The Explorer re-renders rooted at the new folder.
- **UC-MAN-09.d** *(Plan-A rough edges, deferred to Plan B)* The previous root's already-placed
  cells remain in the workspace overworld after a swap and `ProjectDimRegistry` keeps
  accumulating region assignments; **Attach Folder** (a second root) is not implemented.

---

### UC-MAN-10 — Place, save, and create standalone structure files

A `.nbt` file in the Explorer is a first-class citizen independent of any spec: clicking it places
the structure, "Save Structure" auto-fits and rewrites it, and "+ Structure" creates a new empty
one. See [architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files).

- **UC-MAN-10.a** Clicking a `.nbt` `FileNode` in `ProjectExplorerPanel` sends `PlaceStructureC2S(path)`. `ProjectNetworkRegistry.handlePlaceStructure` resolves the subpath, checks for a `.nbt.unsaved` sidecar (`StructurePersistence.unsavedSidecarOf`) and loads it in preference to the committed `.nbt` when present (reporting `hasUnsaved = true`), then delegates to the shared `placeStructureFrom` helper: assigns/reuses a region via `ProjectDimRegistry.getOrAssignStructureRegion` (a disjoint +X lane at `z = STRUCTURE_LANE_Z`), clears only the previously-placed footprint (`placedBoxOf` → `StructurePersistence.clearBounds`), calls `StructurePersistence.placeStructureCentered`, and records the new `PlacedBox` via `setPlacedBox`.
- **UC-MAN-10.b** `StructureRegionMath.centeredStart`/`anchorY` center the structure in the region and floor it at `SharedSettings.projectGridYBase` (64), or vertically center it when the structure's height is at or above `TALL_THRESHOLD` (256).
- **UC-MAN-10.c** The `StructureActions()` "Save Structure" button (enabled when `selectedPath` ends with `.nbt`) sends `SaveStructureC2S(path)`. `handleSaveStructure` refuses (with `ProjectErrorS2C`) unless the structure was placed this session (`placedBoxOf(subpath) != null`), then scans the assigned region for non-air, computes the tight box via `StructureRegionMath.autoFit`, calls `StructurePersistence.saveAutoFitToFile` to rewrite `<name>.nbt` (an all-air region returns `null` and no file is written), and deletes the `.nbt.unsaved` sidecar so the structure reports clean.
- **UC-MAN-10.d** The `StructureActions()` "+ Structure" name field sends `NewStructureC2S(name)`. `handleNewStructure` calls `ProjectNewStructure.create(folder, name)`, which writes an empty `<name>.nbt` into the active folder, then re-sends the project tree.
- **UC-MAN-10.e** All handlers reply with `StructureResultS2C(subpath, sizeX, sizeY, sizeZ, hasUnsaved, message)`; `ProjectClientNetworking` feeds it to `ProjectTreeState.onStructureResult`, which sets `status = message` — the same status line used by folder load/save results. `hasUnsaved` is `true` only when place loaded from the `.nbt.unsaved` sidecar; save and discard both report `false`.
- **UC-MAN-10.f** `DiscardStructureC2S(subpath)` → `handleDiscardStructure` deletes the `.nbt.unsaved` sidecar (if any) and re-places from the committed `.nbt` via `placeStructureFrom`, reporting `hasUnsaved = false`. On `ServerLifecycleEvents.BEFORE_SAVE`, `ProjectNetworkRegistry.flushDirtyStructures` iterates `ProjectDimRegistry.placedStructureSubpaths()` and calls `StructurePersistence.flushUnsavedSidecar` for each placed structure's region, writing (or deleting, if the region now matches the committed file) its `.nbt.unsaved` — this is the only auto-persist point for in-progress structure edits; there is no autosave on disconnect.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-MAN-01 | Declare and persist a project root *(removed — `ProjectRootsConfig` and its test deleted as dead code)* | — | n/a |
| UC-MAN-01.e | `ProjectRoot.resolveSubpath` rejects relative and escaping subpaths | `ProjectRootTest."resolveSubpath rejects parent traversal"`, `ProjectRootTest."resolveSubpath rejects absolute subpath"`, `ProjectRootTest."resolveSubpath rejects symlink that escapes root"` | covered |
| UC-MAN-02 | Boot the project singleplayer world | — | **GAP** |
| UC-MAN-02.a | *(removed)* per-root `boot(rootPath)` derived save name via `ProjectSaveNaming.saveName`; `ProjectSaveNaming` survives as a pure function with its own test | `ProjectSaveNamingTest."preserves alphanumeric tail and appends 8-hex hash"`, `ProjectSaveNamingTest."same tail at different absolute paths produce different hashes"` | n/a (derivation covered; no live caller) |
| UC-MAN-02.b | `openOrCreateWorld` reopens or creates fresh void world | — | **GAP** |
| UC-MAN-02.c | `SERVER_STARTING` listener picks up `pendingRoot` and calls `ProjectServerContext.set` | — | **GAP** |
| UC-MAN-02.d | `SERVER_STARTED` listener calls `ProjectDimLifecycle.placeAll` if context present | `ProjectDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-02.e | Re-opening same root reuses persistent save; scratch blocks outside bounds preserved | — | **GAP** |
| UC-MAN-03 | Scan the folder tree and assign regions | `ProjectFolderTreeTest."leaf folder with one .spec.kts is reported as a leaf"` | **GAP-PARTIAL** |
| UC-MAN-03.a | `ProjectFolderTree.scan` walks tree, collects leaves and intermediates, sorted | `ProjectFolderTreeTest."leaf folder with one .spec.kts is reported as a leaf"`, `ProjectFolderTreeTest."intermediate folder containing only a subfolder is in intermediates; deeper leaf in leaves"` | covered |
| UC-MAN-03.b | `placeAll` iterates `leaves.sortedBy { it.subpath }` for deterministic order | `ProjectDimSpec."placeAll handles nested leaf folders with distinct region origins"` | **GAP-PARTIAL** |
| UC-MAN-03.c | `ProjectDimRegistry.getOrAssignRegion` computes non-colliding region origins | `ProjectDimRegistryTest."getOrAssignRegion is idempotent for the same subpath"`, `ProjectDimRegistryTest."distinct subpaths get distinct origins"`, `ProjectDimRegistryTest."region width matches cellSize.x * rowMax + gap*(rowMax+1) + REGION_PAD"` | covered |
| UC-MAN-03.d | `projectLevel()` returns `server.overworld()` | `ProjectDimSpec."load places cells in the managed dim and registers a session"` | covered |
| UC-MAN-03.e | `ProjectDimRegistry` keyed by `MinecraftServer`; `dispose` called on server stop | `ProjectDimRegistryTest."regionOriginOf returns null for unassigned subpath"`, `ProjectLifecycleReleaseTest."UC-MAN-08.d: releaseServerState disposes registry, world, and context"` | **GAP-PARTIAL** |
| UC-MAN-04 | Lay out and place spec cells in the grid | `ProjectDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-04.a | `GridLayout.compute` places specs row-major at derived origins | `GridLayoutTest."single spec lands at (0, yBase, 0)"`, `GridLayoutTest."row wraps at rowMax"`, `GridLayoutTest."sort is filename-case-insensitive"` | covered |
| UC-MAN-04.b | Oversized spec excluded with `LayoutError` and does not consume a slot | `GridLayoutTest."oversized spec excluded with error"` | covered |
| UC-MAN-04.c | `placeCell` reads `.nbt` beside spec file and calls `tpl.placeInWorld` | `ProjectDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-04.d | Anchor block placed; `SpecBlockEntity.projectSourcePath` set and not persisted to NBT | `ProjectDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-04.e | `StructureTemplate.fillFromWorld` captures cell volume as `loadedSnapshot` | `ProjectCellSaverSpec."no mutation -> not saved"` | **GAP-PARTIAL** |
| UC-MAN-04.f | `world.perFolder[subpath]` replaced atomically via `ConcurrentHashMap` | `ProjectDimSpec."re-place after adding a new spec keeps region origin and includes new spec"` | **GAP-PARTIAL** |
| UC-MAN-05 | Browse folder tree in-game and teleport to a folder | `ProjectNetworkRegistrySpec."handleListTree sends a recursive snapshot matching scanFolder"` | **GAP-PARTIAL** |
| UC-MAN-05.a | `ListProjectTreeC2S` triggers scan and `ProjectTreeSnapshotS2C` reply | `ProjectNetworkRegistrySpec."handleListTree sends a recursive snapshot matching scanFolder"` | covered |
| UC-MAN-05.b | `ProjectClientNetworking` receives snapshot and feeds `ProjectTreeState`; the Compose Explorer renders it | `ProjectExplorerSpec."Explorer renders a project tree snapshot"` | covered |
| UC-MAN-05.c | `LoadProjectFolderC2S` triggers path-traversal guard, teleport, and `ProjectFolderLoadedS2C` reply | `ProjectNetworkRegistrySpec."handleLoadFolder rejects path traversal with ProjectErrorS2C"`, `ProjectNetworkRegistrySpec."handleLoadFolder happy path sends ProjectFolderLoadedS2C and sets session"` | covered |
| UC-MAN-05.d | `ProjectTeleport.toFolder` teleports player and calls `ProjectSession.setActive` | `ProjectTeleportSpec."toFolder teleports player to region and sets active subpath"` | covered |
| UC-MAN-05.e | Unknown subpath: `toFolder` returns `false`, server replies with `ProjectErrorS2C` | `ProjectTeleportSpec."toFolder returns false for unknown subpath and does not change session"` | covered |
| UC-MAN-06 | Create a new spec cell in the active folder *(orphaned: no live UI caller)* | `ProjectNetworkRegistrySpec."handleNewSpec with active session creates file and sends ProjectFolderLoadedS2C"` | **GAP-PARTIAL** |
| UC-MAN-06.a | *(historical UI path)* Player sends `NewProjectSpecC2S(name)` after typing non-blank name in the deleted `ProjectScreen` | — (`ProjectEntryFlowSpec`, the client test that covered this, was deleted with the screen it tested; the server-side handler is covered under UC-MAN-06) | **GAP** |
| UC-MAN-06.b | No active folder → `ProjectErrorS2C("no folder selected")` | `ProjectNetworkRegistrySpec."handleNewSpec without active session returns 'no folder selected'"` | covered |
| UC-MAN-06.c | `ProjectNewSpec.create` validates name regex, non-duplicate, writes stub | `ProjectNewSpecTest."create writes <name>.spec.kts with stub content"`, `ProjectNewSpecTest."illegal characters in name throw"`, `ProjectNewSpecTest."file already exists throws"` | covered |
| UC-MAN-06.d | `handleNewSpec` calls `placeFolder` to re-scan and re-place entire folder | `ProjectNetworkRegistrySpec."handleNewSpec with active session creates file and sends ProjectFolderLoadedS2C"`, `ProjectDimSpec."ProjectNewSpec.create writes a stub spec.kts to the leaf folder"` | covered |
| UC-MAN-06.e | `ProjectFolderLoadedS2C` sent back with updated spec-id list and errors | `ProjectNetworkRegistrySpec."handleNewSpec with active session creates file and sends ProjectFolderLoadedS2C"` | covered |
| UC-MAN-07 | Save edited cell blocks back to disk | `ProjectCellSaverSpec."mutation inside cell -> saved and .nbt written"` | covered |
| UC-MAN-07.a | "Save Now" sends `SaveNowC2S`; `handleSaveNow` calls `saveAll` | `ProjectNetworkRegistrySpec."handleSaveNow returns formatted ProjectSaveReportS2C"` | covered |
| UC-MAN-07.b | `saveFolder` resolves absolute cell origin and passes to `captureAndSaveIfDirty` | `ProjectDimSpec."saveNow writes only specs whose cell volume changed"` | covered |
| UC-MAN-07.c | `ProjectCellSaver` returns `saved=false` when NBT is equal | `ProjectCellSaverSpec."no mutation -> not saved"`, `ProjectCellSaverSpec."mutation outside cell bounds -> not saved"` | covered |
| UC-MAN-07.d | Dirty diff: `NbtIo.writeCompressed` overwrites `.nbt`; `.spec.kts` re-emission deferred | `ProjectCellSaverSpec."mutation inside cell -> saved and .nbt written"` | **GAP-PARTIAL** |
| UC-MAN-07.e | After save, fresh snapshot stored as new `loadedSnapshot` baseline | `ProjectCellSaverSpec."snapshot refresh: second saveFolder after a save returns saved=false"` | covered |
| UC-MAN-07.f | `ProjectSaveReportS2C` sent with per-spec result strings | `ProjectNetworkRegistrySpec."handleSaveNow returns formatted ProjectSaveReportS2C"` | covered |
| UC-MAN-08 | Unload active folder and handle ungraceful session end | `ProjectNetworkRegistrySpec."handleUnload clears session and sends empty save report"` | **GAP-PARTIAL** |
| UC-MAN-08.a | "Unload" sends `UnloadProjectFolderC2S`; `handleUnload` clears session and sends empty report | `ProjectNetworkRegistrySpec."handleUnload clears session and sends empty save report"` | covered |
| UC-MAN-08.b | Post-unload `handleNewSpec` returns `ProjectErrorS2C("no folder selected")`; `handleSaveNow` is session-independent (saves all loaded folders) | `ProjectNetworkRegistrySpec."handleNewSpec without active session returns 'no folder selected'"` | covered |
| UC-MAN-08.c | Ungraceful disconnect: `ProjectSession.clear` called from disconnect event | `ProjectNetworkRegistrySpec."ungraceful disconnect clears the player's managed session"` | covered |
| UC-MAN-08.d | Server stop: `dispose` and `clear` calls release all server-scoped state | `ProjectLifecycleReleaseTest."UC-MAN-08.d: releaseServerState disposes registry, world, and context"` | covered |
| UC-MAN-08.e | On next `placeAll`, cell is rebuilt from on-disk `.nbt` (un-saved in-world edits overwritten); only saved changes persist | — | **GAP** |
| UC-MAN-09 | Re-root the Explorer from a native folder picker | `RootPickerSpec`, `ProjectNetworkRegistrySpec` | **GAP-PARTIAL** |
| UC-MAN-09.a | Option button toggles the menu; Open Folder runs the `FolderPicker` on a worker thread | `RootPickerSpec."openFolder sends SetProjectRootC2S and persists the picked path"`, `ProjectExplorerSpec."Explorer header renders the root option button and opens the dropdown"` | covered |
| UC-MAN-09.b | Non-null pick persists + sends `SetProjectRootC2S`; cancel sends nothing | `RootPickerSpec."openFolder sends SetProjectRootC2S and persists the picked path"`, `RootPickerSpec."openFolder sends nothing when the picker is cancelled"` | covered |
| UC-MAN-09.c | `handleSetRoot` validates dir, swaps root, re-places, re-snapshots; non-dir → `ProjectErrorS2C` | `ProjectNetworkRegistrySpec."handleSetRoot switches root, persists it, and sends a snapshot of the new folder"`, `ProjectNetworkRegistrySpec."handleSetRoot rejects a non-directory path with ProjectErrorS2C"` | covered |
| UC-MAN-09.d | *(Plan B)* old grid persists; region assignments accumulate; Attach not implemented | — | n/a |
| UC-MAN-10 | Place, save, and create standalone structure files | `ProjectStructureNetworkSpec` | covered |
| UC-MAN-10.a | `PlaceStructureC2S` → `handlePlaceStructure` prefers the `.nbt.unsaved` sidecar when present, assigns/reuses a structure region, cheap-re-clears the prior footprint, places via the shared `placeStructureFrom` helper, and records the new `PlacedBox` | `ProjectStructureNetworkSpec."place then save round-trips a standalone structure via handlers"`, `ProjectStructureNetworkSpec."place rejects a non-.nbt subpath"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"`, `ProjectDimRegistryTest."getOrAssignStructureRegion is idempotent and distinct per subpath"`, `ProjectDimRegistryTest."structure regions sit in a lane disjoint from spec-folder regions"`, `ProjectDimRegistryTest."placed-box round-trips per subpath"` | covered |
| UC-MAN-10.b | `centeredStart`/`anchorY` center in-region and floor/vertically-center by height | `StructureRegionMathTest."centeredStart centers a box in a region (floor-divides odd slack)"`, `StructureRegionMathTest."anchorY floors short structures at yBase"`, `StructureRegionMathTest."anchorY vertically centers structures at or above the tall threshold"` | covered |
| UC-MAN-10.c | `SaveStructureC2S` → `handleSaveStructure` refuses unless placed this session, auto-fits the non-air region, rewrites the `.nbt`, and deletes the `.nbt.unsaved` sidecar; empty region writes no file | `StructureRegionPersistenceSpec."auto-fit save captures the tight non-air box; place re-centers it"`, `StructureRegionPersistenceSpec."auto-fit save of an empty region writes a file and returns null"`, `StructureRegionMathTest."autoFit tightly boxes scattered non-air cells"`, `StructureRegionMathTest."autoFit returns null when the volume has no non-air"`, `ProjectStructureNetworkSpec."save without placing this session is refused and does not touch the file"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"` | covered |
| UC-MAN-10.d | `NewStructureC2S` → `handleNewStructure` writes an empty `.nbt` and re-sends the tree | `ProjectStructureNetworkSpec."new structure creates the file and re-sends the tree"` | covered |
| UC-MAN-10.e | `StructureResultS2C` codec and status-line wiring | `StructurePacketsTest."StructureResultS2C codec round-trips"`, `StructurePacketsTest."PlaceStructureC2S codec round-trips"`, `StructurePacketsTest."SaveStructureC2S codec round-trips"`, `StructurePacketsTest."NewStructureC2S codec round-trips"` | covered |
| UC-MAN-10.f | `DiscardStructureC2S` → `handleDiscardStructure` deletes the sidecar and re-places from the committed `.nbt`; `BEFORE_SAVE` → `flushDirtyStructures` writes/deletes each placed structure's sidecar | `StructurePacketsTest."DiscardStructureC2S codec round-trips"`, `ProjectStructureNetworkSpec."dirty sidecar lifecycle: flush writes/deletes, place loads unsaved, save+discard clear"` | covered |

**UI-caller gap (not a test gap):** UC-MAN-01/02/06/07/08's `.a` rows above are marked historical
because their only client trigger (`ProjectScreen`/`ProjectRootListScreen`) was deleted in the
Compose-dock hard-cut and `ProjectExplorerPanel` does not yet expose New Spec / Save Now / Unload /
multi-root controls. The server handlers remain fully covered by their gametest specs; what's missing
is a UI to drive them, tracked as future dock-panel work, not a coverage regression to backfill with
more tests.
