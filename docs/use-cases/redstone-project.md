---
title: Redstone project use-cases
tags: [redstone-project, dimensions, grid, datapack, use-cases]
summary: Per-folder void-dim workspace via runtime datapack; deterministic grid; per-spec save-back.
last_audited_commit: 4c849243e4ce68ca5147bd2a16ce057664baa8fd
---

# Redstone project use-cases

A project root is a folder of `.spec.kts` files projected into a runtime-generated void dimension as a deterministic grid. Each spec gets a cell; edits in the cell save back to the spec file (see [architecture/redstone-project.md](../architecture/redstone-project.md)).

---

### UC-MAN-01 — Declare and persist a project root

User registers a filesystem folder as a project-spec root; that registration survives MC restarts.

- **UC-MAN-01.a** The user opens `ProjectRootListScreen` (injected as a "Project Specs…" button on `TitleScreen` via `TitleScreenMixin`) and types an absolute path into the `EditBox`.
- **UC-MAN-01.b** Clicking "Add" appends the path to the in-memory list and immediately calls `ProjectRootsConfig.save(configPath, roots)`, writing a JSON array to `<MC-config-dir>/redstonespecs/project-roots.json`.
- **UC-MAN-01.c** On next client launch `ProjectRootsConfig.load(configPath)` re-reads the file; the root appears in the list without re-entry.
- **UC-MAN-01.d** Clicking "X" beside a root removes it from the list and re-saves the config; the entry disappears immediately on `rebuildWidgets`.
- **UC-MAN-01.e** `ProjectRoot` enforces that every path is absolute and rejects path-traversal attempts via `resolveSubpath`: a relative or escaping subpath returns `null` (symlink-defeat via `toRealPath` before `startsWith` check).

---

### UC-MAN-02 — Boot the project singleplayer world

Opening a project root from `ProjectRootListScreen` creates or reopens the dedicated singleplayer save for that root, then seeds it with the spec layout.

- **UC-MAN-02.a** Clicking "Open" in `ProjectRootListScreen` calls `ProjectIntegratedBoot.boot(rootPath)`. `ProjectSaveNaming.saveName` derives `project-<sanitized-tail>-<8-hex-sha1>` from the root's absolute path, so two roots with the same final component get different save names.
- **UC-MAN-02.b** `ProjectIntegratedBoot.openOrCreateWorld` calls `Minecraft.levelSource.levelExists(saveName)`. If the save exists, `WorldOpenFlows.openWorld` reopens it; otherwise `WorldOpenFlows.createFreshLevel` creates a creative, peaceful, allow-commands, flat-void world (overworld replaced with `FlatLevelGeneratorPresets.THE_VOID` via `FlatLevelSource`).
- **UC-MAN-02.c** A `SERVER_STARTING` listener (registered once by `ensureListenersRegistered`) picks up the `pendingRoot` `AtomicReference` and calls `ProjectServerContext.set(server, ProjectServerContext(root))`, pinning the active root for the server lifetime.
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

- **UC-MAN-05.a** Opening `ProjectScreen` (via `/redstonespecs project` or the world-list flow) immediately sends `ListProjectTreeC2S`. `ProjectNetworkRegistry.handleListTree` calls `ProjectFolderTree.scan(root)` on the server and replies with `ProjectTreeSnapshotS2C` carrying the leaf list (subpath + spec count), intermediate folders, and the player's current `activeSubpath`.
- **UC-MAN-05.b** `ProjectClientNetworking` receives `ProjectTreeSnapshotS2C`. If `ProjectScreen` is open it calls `onTreeSnapshot` and `rebuildWidgets`; if no project screen is open it constructs a new `ProjectScreen(payload)` and sets it as the current screen.
- **UC-MAN-05.c** Clicking a leaf row sends `LoadProjectFolderC2S(subpath)`. `ProjectNetworkRegistry.handleLoadFolder` validates the subpath via `root.resolveSubpath` (path-traversal guard), calls `ProjectTeleport.toFolder`, and sends `ProjectFolderLoadedS2C` with the spec-id list and any errors.
- **UC-MAN-05.d** `ProjectTeleport.toFolder` looks up `ProjectDimRegistry.regionOriginOf(subpath)`, teleports the player to `(region.x+0.5, yBase+2, region.z+0.5)` in `projectLevel()`, and calls `ProjectSession.setActive(player.uuid, subpath)` so subsequent server actions (save, new-spec) scope to the right folder.
- **UC-MAN-05.e** If the subpath's region has not been assigned (folder not yet placed), `toFolder` returns `false` and the server replies with `ProjectErrorS2C`; `ProjectScreen.onError` updates the status label.

---

### UC-MAN-06 — Create a new spec cell in the active folder

A player names a new spec from the in-game UI; the server writes a stub `.spec.kts`, re-places the folder, and the new cell appears in the world.

- **UC-MAN-06.a** The player types a name into the `EditBox` in `ProjectScreen` (validated client-side for non-blank) and clicks "New Spec", sending `NewProjectSpecC2S(name)`.
- **UC-MAN-06.b** `ProjectNetworkRegistry.handleNewSpec` reads `ProjectSession.get(player.uuid)?.activeSubpath`; if no folder is active it replies with `ProjectErrorS2C("no folder selected")`.
- **UC-MAN-06.c** `ProjectNewSpec.create(folder, name)` enforces that `name` matches `[a-zA-Z0-9_-]+`, that the target file does not already exist, then writes a minimal stub via `RecordingDslEmitter.emitStub(name)`. Throws on any violation so the caller can catch and report.
- **UC-MAN-06.d** After creating the stub, `handleNewSpec` calls `ProjectDimLifecycle.placeFolder(server, root, activeSubpath)` to re-scan and re-place the entire folder. The new file appears as an empty cell with a `REDSTONE_SPEC_RECORDER_BLOCK` anchor.
- **UC-MAN-06.e** `ProjectFolderLoadedS2C` is sent back with the updated spec-id list and any parse/layout errors from the re-place.

---

### UC-MAN-07 — Save edited cell blocks back to disk

When the player has modified blocks inside a spec's cell AABB, the server detects the diff and overwrites the source `.spec.kts` structure file.

- **UC-MAN-07.a** The player clicks "Save Now" in `ProjectScreen`, sending `SaveNowC2S`. `ProjectNetworkRegistry.handleSaveNow` calls `ProjectDimLifecycle.saveAll(server)`, which iterates every subpath in `ProjectWorld.perFolder` and calls `saveFolder`.
- **UC-MAN-07.b** `saveFolder` resolves each spec's absolute cell origin via `ProjectWorld.absoluteCellOrigin` (adds `regionOrigin.x/z` to the region-relative `cell.origin.x/z`; Y from `cell.origin.y` is already absolute). It then passes `level`, `loaded`, and `absoluteCellOrigin` to `ProjectCellSaver.captureAndSaveIfDirty`.
- **UC-MAN-07.c** `ProjectCellSaver` captures the live cell volume with `StructureTemplate.fillFromWorld`, serializes both live and baseline snapshots to `CompoundTag`, and returns `CellSaveResult(saved=false)` if the NBT is equal (no-op). Only a structural change in block data triggers the rewrite.
- **UC-MAN-07.d** On a dirty diff, `NbtIo.writeCompressed(liveNbt, structureFile)` overwrites `<structureId>.nbt`. The `.spec.kts` source re-emission is deferred (noted in `ProjectCellSaver`); `RecordingDslEmitter` will handle it in a later phase.
- **UC-MAN-07.e** After a successful save, `saveFolder` captures a fresh `StructureTemplate` and stores it as the new `loadedSnapshot` in `ProjectWorld.perFolder`, so subsequent saves have an accurate baseline.
- **UC-MAN-07.f** `ProjectSaveReportS2C` is sent back to the player with per-spec `"specId|saved=true/false[|err=…]"` strings; `ProjectScreen.onSaveReport` updates the status label with the count of saved specs.

---

### UC-MAN-08 — Unload active folder and handle ungraceful session end

A player explicitly unloads their active folder focus, or the session is cleared when the player disconnects or the server stops.

- **UC-MAN-08.a** The player clicks "Unload" in `ProjectScreen`, sending `UnloadProjectFolderC2S`. `ProjectNetworkRegistry.handleUnload` calls `ProjectSession.clear(player.uuid)` and replies with `ProjectSaveReportS2C(emptyList())`.
- **UC-MAN-08.b** After unload, the player's `activeSubpath` is `null`; a subsequent `handleNewSpec` (which requires a folder focus) receives `ProjectErrorS2C("no folder selected")`. `handleSaveNow` is deliberately session-independent — it calls `ProjectDimLifecycle.saveAll(server)` over every loaded folder in `ProjectWorld.perFolder`, so post-unload it still returns a `ProjectSaveReportS2C` (empty when nothing is loaded), not an error.
- **UC-MAN-08.c** If a player disconnects without clicking Unload (ungraceful exit), `ProjectSession.clear(player.uuid)` must be called from the server-side player disconnect event. The `ProjectSession` map is a `ConcurrentHashMap` keyed by `UUID`; the player's slot is released so it does not linger after reconnect.
- **UC-MAN-08.d** On server stop, a `SERVER_STOPPED` listener (registered in `Redstonespecs.onInitialize`) calls `ProjectDimLifecycle.releaseServerState(server)`, which invokes `ProjectDimRegistry.dispose(server)`, `ProjectWorld.clear(server)`, and `ProjectServerContext.clear(server)` — removing each `WeakHashMap` entry for the server and releasing all server-scoped state promptly rather than waiting for GC.
- **UC-MAN-08.e** Spec cell blocks persist in the singleplayer save across sessions, but on next `placeAll` the cell is **rebuilt from disk, not preserved**: when the `.nbt` exists, `placeCell` calls `StructureTemplate.placeInWorld` over the cell AABB (`ProjectDimLifecycle.placeCell`), overwriting whatever was there, then `fillFromWorld` snapshots that freshly-placed structure as the new `loadedSnapshot` baseline. So **only changes previously saved to `.nbt` persist; un-saved in-world edits inside the cell are discarded on the next placement.** Two exceptions: a brand-new spec with no `.nbt` yet is placed empty (nothing to overwrite), and blocks *outside* the cell AABB are never re-placed and survive (see UC-MAN-02.e). "Save Now" (UC-MAN-07) is the mechanism for persisting edits; there is no auto-save on disconnect.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-MAN-01 | Declare and persist a project root | `ProjectRootsConfigTest."save then load roundtrips a list of paths"` | **GAP-PARTIAL** |
| UC-MAN-01.a | User types path into `EditBox` in `ProjectRootListScreen` | `ProjectEntryFlowSpec."UC-MAN-01.a: TitleScreen RedstoneIconButton opens ProjectRootListScreen"`, `ProjectEntryFlowSpec."UC-MAN-01.b: Add button persists path to config and renders 'Open: <path>' row"` | covered |
| UC-MAN-01.b | "Add" appends path and calls `ProjectRootsConfig.save` writing JSON | `ProjectRootsConfigTest."save then load roundtrips a list of paths"`, `ProjectRootsConfigTest."save creates parent directories if needed"`, `ProjectEntryFlowSpec."UC-MAN-01.b: Add button persists path to config and renders 'Open: <path>' row"` | covered |
| UC-MAN-01.c | On next launch `ProjectRootsConfig.load` re-reads file | `ProjectRootsConfigTest."save then load roundtrips a list of paths"`, `ProjectRootsConfigTest."load returns empty when file missing"` | covered |
| UC-MAN-01.d | "X" removes entry and re-saves; entry disappears on `rebuildWidgets` | — | **GAP** |
| UC-MAN-01.e | `ProjectRoot.resolveSubpath` rejects relative and escaping subpaths | `ProjectRootTest."resolveSubpath rejects parent traversal"`, `ProjectRootTest."resolveSubpath rejects absolute subpath"`, `ProjectRootTest."resolveSubpath rejects symlink that escapes root"` | covered |
| UC-MAN-02 | Boot the project singleplayer world | — | **GAP** |
| UC-MAN-02.a | `ProjectIntegratedBoot.boot` derives save name via `ProjectSaveNaming.saveName` | `ProjectSaveNamingTest."preserves alphanumeric tail and appends 8-hex hash"`, `ProjectSaveNamingTest."same tail at different absolute paths produce different hashes"` | **GAP-PARTIAL** |
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
| UC-MAN-05 | Browse folder tree in-game and teleport to a folder | `ProjectNetworkRegistrySpec."handleListTree sends snapshot matching ProjectFolderTree.scan"` | **GAP-PARTIAL** |
| UC-MAN-05.a | `ListProjectTreeC2S` triggers scan and `ProjectTreeSnapshotS2C` reply | `ProjectNetworkRegistrySpec."handleListTree sends snapshot matching ProjectFolderTree.scan"` | covered |
| UC-MAN-05.b | `ProjectClientNetworking` receives snapshot and opens or updates `ProjectScreen` | `ProjectEntryFlowSpec."/redstonespecs managed opens ProjectScreen client-side with the tree leaves"`, `ProjectEntryFlowSpec."UC-MAN-05.b: ProjectScreen shows \"Loading…\" placeholder before snapshot, clears after"` | covered |
| UC-MAN-05.c | `LoadProjectFolderC2S` triggers path-traversal guard, teleport, and `ProjectFolderLoadedS2C` reply | `ProjectNetworkRegistrySpec."handleLoadFolder rejects path traversal with ProjectErrorS2C"`, `ProjectNetworkRegistrySpec."handleLoadFolder happy path sends ProjectFolderLoadedS2C and sets session"` | covered |
| UC-MAN-05.d | `ProjectTeleport.toFolder` teleports player and calls `ProjectSession.setActive` | `ProjectTeleportSpec."toFolder teleports player to region and sets active subpath"` | covered |
| UC-MAN-05.e | Unknown subpath: `toFolder` returns `false`, server replies with `ProjectErrorS2C` | `ProjectTeleportSpec."toFolder returns false for unknown subpath and does not change session"` | covered |
| UC-MAN-06 | Create a new spec cell in the active folder | `ProjectNetworkRegistrySpec."handleNewSpec with active session creates file and sends ProjectFolderLoadedS2C"` | covered |
| UC-MAN-06.a | Player sends `NewProjectSpecC2S(name)` after typing non-blank name | `ProjectEntryFlowSpec."UC-MAN-06.a (text survives snapshot): typed spec name in EditBox survives an incoming ProjectTreeSnapshotS2C"`, `ProjectEntryFlowSpec."UC-MAN-06.a (creates file): clicking \"New Spec\" after typing creates the .spec.kts on disk"` | covered |
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
