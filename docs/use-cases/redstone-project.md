---
title: Redstone project use-cases
tags: [redstone-project, dimensions, grid, datapack, use-cases]
summary: Per-folder void-dim workspace via runtime datapack; deterministic grid; per-spec save-back. (Standalone .nbt structures + dirty-state moved to structure-lifecycle.md.)
last_audited_commit: 4c849243e4ce68ca5147bd2a16ce057664baa8fd
---

# Redstone project use-cases

A project root is a folder of `.spec.kts` files projected into a runtime-generated void dimension as a deterministic grid. Each spec gets a cell; edits in the cell save back to the spec file (see [architecture/redstone-project.md](../architecture/redstone-project.md)).

**UI status:** `ProjectScreen` and `ProjectRootListScreen` were deleted in the Compose-dock hard-cut
(see [ui/dock-framework.md](../ui/dock-framework.md)). The only reachable client entry point today is
`TitleScreenMixin`'s "Redstone Projects…" button, which calls `EditorIntegratedBoot.bootWorkspace()`
directly — a single shared `garnet-workspace` save with no root pinned. The multi-root
registration store (`ProjectRootsConfig`) and the per-root boot entry (`EditorIntegratedBoot.boot(rootPath)`)
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
store that backed it (`ProjectRootsConfig`, a JSON list under `<MC-config-dir>/garnet/`) and
its unit test were deleted as dead code — see the UI-status note above. The title button boots a
single shared workspace via `bootWorkspace()`; there is no per-root registration or persisted root
list. The only piece of this area that survives is the path-safety guard the live network handlers
still rely on:

- **UC-MAN-01.e** `EditorRoot` enforces that every path is absolute and rejects path-traversal attempts via `resolveSubpath`: a relative or escaping subpath returns `null` (symlink-defeat via `toRealPath` before `startsWith` check). This guard is exercised regardless of caller (still used by `resolveSubpath` calls from the live network handlers).

---

### UC-MAN-02 — Boot the project singleplayer world

The title button boots a single shared workspace via `EditorIntegratedBoot.bootWorkspace()`, which
opens or creates the fixed `garnet-workspace` save with no root pinned, using the private
`openOrCreateWorld` helper. The per-root boot entry that used to derive a `project-<tail>-<hash>` save
name was removed; the `openOrCreateWorld` helper and the (now dormant) `pendingRoot`/`EditorServerContext`
pinning machinery remain.

- **UC-MAN-02.a** *(removed)* The per-root `boot(rootPath)` entry — which derived a save name via `EditorSaveNaming.saveName` (`project-<sanitized-tail>-<8-hex-sha1>`, so two roots with the same final component got distinct save names) and set `pendingRoot` to pin a `EditorServerContext` — was deleted as dead code. `EditorSaveNaming` itself survives as a pure function with a unit test, but is no longer wired to any boot path.
- **UC-MAN-02.b** `EditorIntegratedBoot.openOrCreateWorld` calls `Minecraft.levelSource.levelExists(saveName)`. If the save exists, `WorldOpenFlows.openWorld` reopens it; otherwise `WorldOpenFlows.createFreshLevel` creates a creative, peaceful, allow-commands, flat-void world (overworld replaced with `FlatLevelGeneratorPresets.THE_VOID` via `FlatLevelSource`).
- **UC-MAN-02.c** *(dormant)* `EditorIntegratedBoot`'s own `SERVER_STARTING` listener (registered once by `ensureListenersRegistered`, which `bootWorkspace` still calls) reads the `pendingRoot` `AtomicReference` and, if set, calls `EditorServerContext.set(server, EditorServerContext(root))`. No caller sets `pendingRoot` anymore, so this listener is a no-op. The live root-pinning path is `garnet`' own `SERVER_STARTING` listener, which pins a `EditorServerContext` from the `SharedSettings.projectRootPath` config when set.
- **UC-MAN-02.d** A `SERVER_STARTED` listener in the mod entrypoint reads `EditorServerContext.get(server)` and, if present, calls `EditorDimLifecycle.placeAll(server, root)` to lay out the full tree; any prior `EditorWorld` for the same root is reused, or a new one is created and stored via `EditorWorld.set`.
- **UC-MAN-02.e** Re-opening the same root from a future session reopens the same persistent save; scratch blocks placed outside spec bounds between the previous close and this open are still present, because only cell AABB contents are re-placed from disk.

---

### UC-MAN-03 — Scan the folder tree and assign regions

On `placeAll`, the mod walks the project root's directory tree, classifies folders as leaves or intermediates, and assigns each leaf a non-overlapping region in the overworld.

- **UC-MAN-03.a** `EditorFolderTree.scan(root)` walks the tree with `Files.walk`, collecting all directories that contain at least one `.spec.kts` file as `ProjectLeaf` entries and all directories that contain sub-directories as `intermediates`. Both collections are sorted by subpath for deterministic ordering.
- **UC-MAN-03.b** `EditorDimLifecycle.placeAll` iterates `tree.leaves.sortedBy { it.subpath }` and calls `placeFolderInto` for each leaf. Sorting guarantees that region-index assignment via `EditorDimRegistry.getOrAssignRegion` is stable across server restarts (even though the registry is in-memory ephemeral).
- **UC-MAN-03.c** `EditorDimRegistry.getOrAssignRegion(subpath)` increments an `AtomicInteger` counter and computes `BlockPos(idx * regionWidth, projectGridYBase, 0)`. `regionWidth` is derived from `SharedSettings` (`cellSize.x * rowMax + cellGap * (rowMax + 1) + REGION_PAD=64`) so regions never collide regardless of how large a folder grid grows.
- **UC-MAN-03.d** `projectLevel()` returns `server.overworld()` directly — no custom dimension type or datapack is registered; all project grids coexist in the integrated server's overworld.
- **UC-MAN-03.e** `EditorDimRegistry` is a `WeakHashMap`-keyed singleton per `MinecraftServer`; `dispose(server)` is called on server stop to release the reference.

---

### UC-MAN-04 — Lay out and place spec cells in the grid

Each leaf folder's specs are sorted, assigned to a row-major grid slot, and physically placed as structure NBT + anchor blocks in the overworld at the folder's region origin.

- **UC-MAN-04.a** `GridLayout.compute` accepts sorted `LayoutInput` entries (sorted case-insensitively by filename, then by `spec.id`) and places each into slot `(slotIndex % rowMax, slotIndex / rowMax)`. Origin is `BlockPos(sx*(cellSize.x+gap), yBase, sz*(cellSize.z+gap))` — all offsets are region-relative.
- **UC-MAN-04.b** Any spec whose `bounds` exceeds `cellSize` on any axis is excluded with a `LayoutError`; it does not consume a slot and its error is reported in `LoadFolderReport.errors`.
- **UC-MAN-04.c** `placeCell` reads `<spec.structure ?: spec.id>.nbt` beside the spec file. If the file exists it loads the `StructureTemplate` via `NbtIo.readCompressed` and calls `tpl.placeInWorld`. If it does not exist the cell is placed empty (new spec path).
- **UC-MAN-04.d** *(removed)* Anchor blocks (`GARNET_RUNNER_BLOCK`/`GARNET_RECORDER_BLOCK`) used to be placed beside each cell, bound to the spec file via `SpecBlockEntity.projectSourcePath`. Both the blocks and the binding were deleted along with the recorder/runner surface (see [architecture/module-map.md](../architecture/module-map.md)) — `EditorDimLifecycle.placeCell` now places only the structure NBT itself; a cell is just its structure, with no anchor block and no source-path binding on any block entity.
- **UC-MAN-04.e** After placement, `StructureTemplate.fillFromWorld` captures the cell volume as `loadedSnapshot` and stores it in `LoadedSpec`. This snapshot is the dirty-diff baseline used by `EditorCellSaver`.
- **UC-MAN-04.f** `world.perFolder[subpath]` is replaced atomically (via `ConcurrentHashMap`) with the newly placed specs so that stale entries from a prior placement of the same subpath are not visible.

---

### UC-MAN-05 — Browse the folder tree in-game and teleport to a folder

A player selects a leaf folder from the in-game UI, which teleports them to that folder's region and marks it as their active focus.

- **UC-MAN-05.a** `/garnet editor` immediately sends `ListEditorTreeC2S`. `EditorTreeHandlers.handleListTree` (via `EditorHandlerSupport.sendTree`) calls `scanFolder(root.path)` on the server and replies with `EditorTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` carrying the full recursive folder tree (every file/folder, not just spec leaves) and the player's current `activeSubpath`.
- **UC-MAN-05.b** `EditorClientNetworking` receives `EditorTreeSnapshotS2C` and feeds it into `ProjectTreeState.onSnapshot(payload)`. The Compose Project Explorer (`ProjectExplorerPanel` in the LEFT dock) reads `ProjectTreeState.snapshot`, converts it via `ExplorerTreeState.buildTreeFrom(snapshot.root)`, and renders it with Jewel's `LazyTree`, recomposing on change. This is the **only** client-side reaction to the snapshot — the legacy `ProjectScreen`, which used to auto-rebuild on snapshot, was deleted in the Compose-dock hard-cut. See [ui/dock-framework.md](../ui/dock-framework.md) for the `LazyTree` render pattern and the `ExplorerTreeState`/`ProjectTreeState` split (expand/select state vs. server data).
- **UC-MAN-05.c** Clicking a "spec-folder" row (a folder directly containing a `*.spec.kts` file) sends `LoadEditorFolderC2S(path)`. `EditorTreeHandlers.handleLoadFolder` validates the subpath via `root.resolveSubpath` (path-traversal guard), calls `EditorTeleport.toFolder`, and sends `EditorFolderLoadedS2C` with the spec-id list and any errors. Clicking a non-spec folder or its expand triangle just toggles expand client-side (no packet); clicking a file row selects/highlights it client-side (no packet).
- **UC-MAN-05.d** `EditorTeleport.toFolder` looks up `EditorDimRegistry.regionOriginOf(subpath)`, teleports the player to `(region.x+0.5, yBase+2, region.z+0.5)` in `projectLevel()`, and calls `EditorSession.setActive(player.uuid, subpath)` so subsequent server actions (save, new-spec) scope to the right folder.
- **UC-MAN-05.e** If the subpath's region has not been assigned (folder not yet placed), `toFolder` returns `false` and the server replies with `EditorErrorS2C`. `ProjectTreeState.onError(payload)` sets `status = "error: ${payload.reason}"`, which the Explorer panel renders as its status line — the same mechanism that used to update `ProjectScreen`'s status label.

---

### UC-MAN-06 — Create a new spec cell in the active folder

A player names a new spec from the in-game UI; the server writes a stub `.spec.kts`, re-places the folder, and the new cell appears in the world. **No live UI currently sends `NewEditorSpecC2S`** — the deleted `ProjectScreen` had the "New Spec" `EditBox`/button; `ProjectExplorerPanel` (the current LEFT-dock panel) does not yet have an equivalent control. The server handler and packet are otherwise unchanged and reachable by sending the payload directly (as the coverage tests below do).

- **UC-MAN-06.a** *(historical UI path)* The player types a name into the `EditBox` in the deleted `ProjectScreen` (validated client-side for non-blank) and clicks "New Spec", sending `NewEditorSpecC2S(name)`.
- **UC-MAN-06.b** `EditorTreeHandlers.handleNewSpec` reads `EditorSession.get(player.uuid)?.activeSubpath`; if no folder is active it replies with `EditorErrorS2C("no folder selected")`.
- **UC-MAN-06.c** `EditorNewSpec.create(folder, name)` enforces that `name` matches `[a-zA-Z0-9_-]+`, that the target file does not already exist, then writes a minimal stub via `RecordingDslEmitter.emitStub(name)`. Throws on any violation so the caller can catch and report.
- **UC-MAN-06.d** After creating the stub, `handleNewSpec` calls `EditorDimLifecycle.placeFolder(server, root, activeSubpath)` to re-scan and re-place the entire folder. The new file appears as an empty cell with a `GARNET_RECORDER_BLOCK` anchor.
- **UC-MAN-06.e** `EditorFolderLoadedS2C` is sent back with the updated spec-id list and any parse/layout errors from the re-place.

---

### UC-MAN-07 — Save edited cell blocks back to disk

When the player has modified blocks inside a spec's cell AABB, the server detects the diff and overwrites the source `.spec.kts` structure file. **No live UI currently sends `SaveNowC2S`** — the deleted `ProjectScreen` had the "Save Now" button; `ProjectExplorerPanel` does not yet have an equivalent control. The server handler is otherwise unchanged and reachable by sending the payload directly.

- **UC-MAN-07.a** *(historical UI path)* The player clicks "Save Now" in the deleted `ProjectScreen`, sending `SaveNowC2S`. `EditorTreeHandlers.handleSaveNow` calls `EditorDimLifecycle.saveAll(server)`, which iterates every subpath in `EditorWorld.perFolder` and calls `saveFolder`.
- **UC-MAN-07.b** `saveFolder` resolves each spec's absolute cell origin via `EditorWorld.absoluteCellOrigin` (adds `regionOrigin.x/z` to the region-relative `cell.origin.x/z`; Y from `cell.origin.y` is already absolute). It then passes `level`, `loaded`, and `absoluteCellOrigin` to `EditorCellSaver.captureAndSaveIfDirty`.
- **UC-MAN-07.c** `EditorCellSaver` captures the live cell volume with `StructureTemplate.fillFromWorld`, serializes both live and baseline snapshots to `CompoundTag`, and returns `CellSaveResult(saved=false)` if the NBT is equal (no-op). Only a structural change in block data triggers the rewrite.
- **UC-MAN-07.d** On a dirty diff, `NbtIo.writeCompressed(liveNbt, structureFile)` overwrites `<structureId>.nbt`. The `.spec.kts` source re-emission is deferred (noted in `EditorCellSaver`); `RecordingDslEmitter` will handle it in a later phase.
- **UC-MAN-07.e** After a successful save, `saveFolder` captures a fresh `StructureTemplate` and stores it as the new `loadedSnapshot` in `EditorWorld.perFolder`, so subsequent saves have an accurate baseline.
- **UC-MAN-07.f** `EditorSaveReportS2C` is sent back to the player with per-spec `"specId|saved=true/false[|err=…]"` strings; `ProjectTreeState.onSaveReport` sets `status = "saved N spec(s)"`, which the Explorer panel renders as its status line (the same mechanism `ProjectScreen.onSaveReport` used before deletion).

---

### UC-MAN-08 — Unload active folder and handle ungraceful session end

A player explicitly unloads their active folder focus, or the session is cleared when the player disconnects or the server stops. **No live UI currently sends `UnloadEditorFolderC2S`** — the deleted `ProjectScreen` had the "Unload" button; `ProjectExplorerPanel` does not yet have an equivalent control. The server handler is otherwise unchanged.

- **UC-MAN-08.a** *(historical UI path)* The player clicks "Unload" in the deleted `ProjectScreen`, sending `UnloadEditorFolderC2S`. `EditorTreeHandlers.handleUnload` calls `EditorSession.clear(player.uuid)` and replies with `EditorSaveReportS2C(emptyList())`.
- **UC-MAN-08.b** After unload, the player's `activeSubpath` is `null`; a subsequent `handleNewSpec` (which requires a folder focus) receives `EditorErrorS2C("no folder selected")`. `handleSaveNow` is deliberately session-independent — it calls `EditorDimLifecycle.saveAll(server)` over every loaded folder in `EditorWorld.perFolder`, so post-unload it still returns a `EditorSaveReportS2C` (empty when nothing is loaded), not an error.
- **UC-MAN-08.c** If a player disconnects without clicking Unload (ungraceful exit), `EditorSession.clear(player.uuid)` must be called from the server-side player disconnect event. The `EditorSession` map is a `ConcurrentHashMap` keyed by `UUID`; the player's slot is released so it does not linger after reconnect.
- **UC-MAN-08.d** On server stop, a `SERVER_STOPPED` listener (registered in `garnet.onInitialize`) calls `EditorDimLifecycle.releaseServerState(server)`, which invokes `EditorDimRegistry.dispose(server)`, `EditorWorld.clear(server)`, and `EditorServerContext.clear(server)` — removing each `WeakHashMap` entry for the server and releasing all server-scoped state promptly rather than waiting for GC.
- **UC-MAN-08.e** Spec cell blocks persist in the singleplayer save across sessions, but on next `placeAll` the cell is **rebuilt from disk, not preserved**: when the `.nbt` exists, `placeCell` calls `StructureTemplate.placeInWorld` over the cell AABB (`EditorDimLifecycle.placeCell`), overwriting whatever was there, then `fillFromWorld` snapshots that freshly-placed structure as the new `loadedSnapshot` baseline. So **only changes previously saved to `.nbt` persist; un-saved in-world edits inside the cell are discarded on the next placement.** Two exceptions: a brand-new spec with no `.nbt` yet is placed empty (nothing to overwrite), and blocks *outside* the cell AABB are never re-placed and survive (see UC-MAN-02.e). "Save Now" (UC-MAN-07) is the mechanism for persisting edits; there is no auto-save on disconnect.

---

### UC-MAN-09 — Re-root the Explorer from a native folder picker

A player opens the Explorer header's option button, chooses **Open Folder**, and picks a folder
in the OS dialog; the workspace root switches to it. **Attach Folder** is present but disabled
(multi-root is Plan B).

- **UC-MAN-09.a** The option button is the toolbar's kebab `IconButton`, opening a Jewel
  `PopupMenu` (a Jewel `Dropdown` filled this role briefly, before the toolbar rework replaced the
  root-name header entirely; both replaced the earlier hand-rolled `RootMenu` overlay +
  `RootPickerController.menuOpen`/`toggleMenu`, deleted once the dock's `ImageComposeScene` was
  confirmed to render Compose `Popup`s in-scene — see [ui/dock-dialogs.md](../ui/dock-dialogs.md));
  clicking it opens the menu itself. **Open Folder**
  calls `RootPickerController.openFolder`, which runs the injectable `FolderPicker` (default
  `NfdFolderPicker` → `NativeFileDialog.NFD_PickFolder`) on a worker thread — inline on the
  render thread on macOS instead; see [ui/dock-dialogs.md](../ui/dock-dialogs.md) for why.
- **UC-MAN-09.b** On a non-null pick, the controller normalizes the path to absolute (matching
  the server's canonical form) and persists it client-side (`ModConfig.projectRootPath` →
  `garnet.json`, also mirrored to `SharedSettings.projectRootPath`) and sends
  `SetEditorRootC2S(path)` on the client thread via `Minecraft.execute`. A cancel (null) sends
  nothing. **Persistence is client-side only:** in singleplayer/LAN the integrated server shares
  the JVM so the choice is restored via `ModConfig.load()`; a dedicated-server root swap is not
  durable across restart.
- **UC-MAN-09.c** `EditorTreeHandlers.handleSetRoot` rejects a non-directory / invalid path
  with `EditorErrorS2C`. Otherwise it first flushes every dirty standalone structure against the
  **old** root via `StructureCommit.commitAll` — once the root swaps, `commit` resolves subpaths
  against the NEW root, so a late commit would write the old root's world blocks over a same-named
  file in a different project. If `commitAll` reports any structure whose **write genuinely failed**
  (`UncommittedStructure.writeFailed` — a `CommitOutcome.Failed`: locked file, read-only checkout,
  AV scan), **the swap is refused** with `EditorErrorS2C` and nothing is touched: the reset below
  unplaces every structure and clears its blocks, so proceeding would destroy the only surviving
  copy of those edits — the world blocks themselves. This is the same rule `handleRename` applies
  before its file move.

  A structure that is merely **unresolvable** (`CommitOutcome.NotApplicable` while still dirty — no
  root configured, file missing) is deliberately *not* grounds to refuse, and here `handleSetRoot`
  diverges from `handleRename` on purpose. Nothing could be written for such a structure however
  long the swap waits, and "Open Folder" is the very action that repairs an unresolvable root — so
  refusing would leave a player holding a stale placed-and-dirty structure with no way out at all.
  It is logged and the swap proceeds. Only once no write has failed does it set
  `SharedSettings.projectRootPath`, pin a new `EditorServerContext`, drop all per-structure state
  via `EditorDimRegistry.resetAllStructures` (clearing each structure's tight `placedBox` out of
  the project level as it goes — regions are never recycled, so blocks left behind would be
  unreachable for the rest of the session), re-run `EditorDimLifecycle.placeAll`, and re-send the
  single-root `EditorTreeSnapshotS2C`. The Explorer re-renders rooted at the new folder.

  Note that the reset iterates `EditorDimRegistry.structureSubpaths()`, not
  `placedStructureSubpaths()`: `getOrAssignStructureRegion` runs before `setPlacedBox`, so a place
  that errored in between leaves a region assignment with no placed box, which the placed-box-keyed
  set does not see and which would otherwise outlive the root it belonged to.
- **UC-MAN-09.d** *(Plan-A rough edges, deferred to Plan B)* Standalone **structures** are now
  fully cleaned up on a swap (see UC-MAN-09.c), but the previous root's already-placed spec-folder
  **cells** still remain in the workspace overworld — those live in `EditorDimRegistry.bySubpath`,
  which `resetAllStructures` deliberately does not touch. **Attach Folder** (a second root) is not
  implemented.

---

### UC-MAN-10 — Place, save, and create standalone structure files *(moved)*

The standalone `.nbt` structure journeys — clicking a `.nbt` to place it, "Save Structure",
"+ Structure", and its debounced auto-save with local history — now live in their own article,
alongside the spec-cell sidecar path:
[structure-lifecycle.md](structure-lifecycle.md#standalone-nbt-structures-uc-man-10). The UC IDs
(`UC-MAN-10.a`–`.g`) are unchanged; only the article that hosts them moved.

---

### UC-MAN-11 — Rejoin a world and find the tree already loaded

A player who quit mid-session (or closed the game window outright) rejoins the same project and
sees the Explorer populate itself, with the same folders expanded and the same node selected as
when they left — no Refresh click required. See
[persistence/explorer-session-state.md](../persistence/explorer-session-state.md) for the full
save/restore mechanics; this use case is the player-visible behavior it produces.

- **UC-MAN-11.a** `ExplorerLifecycle`'s `ClientPlayConnectionEvents.JOIN` handler arms a restore
  from `ExplorerStateStore.load()` and immediately sends `ListEditorTreeC2S` (guarded by
  `ClientPlayNetworking.canSend`, so joining a vanilla server without the mod is a no-op rather
  than a throw) — the same request UC-MAN-05.a's `/garnet editor` command sends, but fired
  automatically on join instead of waiting for the player to type a command.
- **UC-MAN-11.b** When `EditorTreeSnapshotS2C` lands, `EditorClientNetworking` feeds it to
  `ProjectTreeState.onSnapshot` as usual (UC-MAN-05.b) and then calls
  `ExplorerTreeState.applyPendingRestore(payload.root)`, which reopens the persisted folders and
  reselects the persisted node — but only if the record's `root` matches the client's currently
  configured root; a mismatch (a different server, or a root swapped via UC-MAN-09 since the
  record was saved) is silently discarded and the tree opens fresh instead.
- **UC-MAN-11.c** Folders or files renamed or deleted since the session that saved the record are
  dropped from the restore rather than restored as broken references: only paths that still
  resolve in the fresh snapshot are reopened, and only as folders (a persisted path that now
  resolves to a file is dropped, since a file cannot be "expanded").
- **UC-MAN-11.d** The restore is one-shot — applying it, or disconnecting before it applies,
  disarms it — so it never fires twice and never clobbers expansion the player changes after
  rejoining.
- **UC-MAN-11.e** The record itself is written on `ClientPlayConnectionEvents.DISCONNECT` (before
  the tree state resets) and again, idempotently, on `ClientLifecycleEvents.CLIENT_STOPPING` — the
  latter covers closing the game window from inside a world, which does not always fire
  DISCONNECT. A session that never received a tree snapshot (joined a vanilla server, or quit
  before the first snapshot arrived) does not overwrite a good prior record with an empty one.

---

## Coverage matrix

| UC ID | Description | Test | Status |
|---|---|---|---|
| UC-MAN-01 | Declare and persist a project root *(removed — `ProjectRootsConfig` and its test deleted as dead code)* | — | n/a |
| UC-MAN-01.e | `EditorRoot.resolveSubpath` rejects relative and escaping subpaths | `EditorRootTest."resolveSubpath rejects parent traversal"`, `EditorRootTest."resolveSubpath rejects absolute subpath"`, `EditorRootTest."resolveSubpath rejects symlink that escapes root"` | covered |
| UC-MAN-02 | Boot the project singleplayer world | — | **GAP** |
| UC-MAN-02.a | *(removed)* per-root `boot(rootPath)` derived save name via `EditorSaveNaming.saveName`; `EditorSaveNaming` survives as a pure function with its own test | `EditorSaveNamingTest."preserves alphanumeric tail and appends 8-hex hash"`, `EditorSaveNamingTest."same tail at different absolute paths produce different hashes"` | n/a (derivation covered; no live caller) |
| UC-MAN-02.b | `openOrCreateWorld` reopens or creates fresh void world | — | **GAP** |
| UC-MAN-02.c | `SERVER_STARTING` listener picks up `pendingRoot` and calls `EditorServerContext.set` | — | **GAP** |
| UC-MAN-02.d | `SERVER_STARTED` listener calls `EditorDimLifecycle.placeAll` if context present | `EditorDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-02.e | Re-opening same root reuses persistent save; scratch blocks outside bounds preserved | — | **GAP** |
| UC-MAN-03 | Scan the folder tree and assign regions | `EditorFolderTreeTest."leaf folder with one .spec.kts is reported as a leaf"` | **GAP-PARTIAL** |
| UC-MAN-03.a | `EditorFolderTree.scan` walks tree, collects leaves and intermediates, sorted | `EditorFolderTreeTest."leaf folder with one .spec.kts is reported as a leaf"`, `EditorFolderTreeTest."intermediate folder containing only a subfolder is in intermediates; deeper leaf in leaves"` | covered |
| UC-MAN-03.b | `placeAll` iterates `leaves.sortedBy { it.subpath }` for deterministic order | `EditorDimSpec."placeAll handles nested leaf folders with distinct region origins"` | **GAP-PARTIAL** |
| UC-MAN-03.c | `EditorDimRegistry.getOrAssignRegion` computes non-colliding region origins | `EditorDimRegistryTest."getOrAssignRegion is idempotent for the same subpath"`, `EditorDimRegistryTest."distinct subpaths get distinct origins"`, `EditorDimRegistryTest."region width matches cellSize.x * rowMax + gap*(rowMax+1) + REGION_PAD"` | covered |
| UC-MAN-03.d | `projectLevel()` returns `server.overworld()` | `EditorDimSpec."load places cells in the managed dim and registers a session"` | covered |
| UC-MAN-03.e | `EditorDimRegistry` keyed by `MinecraftServer`; `dispose` called on server stop | `EditorDimRegistryTest."regionOriginOf returns null for unassigned subpath"`, `EditorLifecycleReleaseTest."UC-MAN-08.d: releaseServerState disposes registry, world, and context"` | **GAP-PARTIAL** |
| UC-MAN-04 | Lay out and place spec cells in the grid | `EditorDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-04.a | `GridLayout.compute` places specs row-major at derived origins | `GridLayoutTest."single spec lands at (0, yBase, 0)"`, `GridLayoutTest."row wraps at rowMax"`, `GridLayoutTest."sort is filename-case-insensitive"` | covered |
| UC-MAN-04.b | Oversized spec excluded with `LayoutError` and does not consume a slot | `GridLayoutTest."oversized spec excluded with error"` | covered |
| UC-MAN-04.c | `placeCell` reads `.nbt` beside spec file and calls `tpl.placeInWorld` | `EditorDimSpec."load places cells in the managed dim and registers a session"` | **GAP-PARTIAL** |
| UC-MAN-04.d | *(removed)* Anchor block + `SpecBlockEntity.projectSourcePath` binding — both deleted with the recorder/runner surface; a cell is now just its structure | n/a | n/a |
| UC-MAN-04.e | `StructureTemplate.fillFromWorld` captures cell volume as `loadedSnapshot` | `EditorCellSaverSpec."no mutation -> not saved"` | **GAP-PARTIAL** |
| UC-MAN-04.f | `world.perFolder[subpath]` replaced atomically via `ConcurrentHashMap` | `EditorDimSpec."re-place after adding a new spec keeps region origin and includes new spec"` | **GAP-PARTIAL** |
| UC-MAN-05 | Browse folder tree in-game and teleport to a folder | `EditorNetworkRegistrySpec."handleListTree sends a recursive snapshot matching scanFolder"` | **GAP-PARTIAL** |
| UC-MAN-05.a | `ListEditorTreeC2S` triggers scan and `EditorTreeSnapshotS2C` reply | `EditorNetworkRegistrySpec."handleListTree sends a recursive snapshot matching scanFolder"` | covered |
| UC-MAN-05.b | `EditorClientNetworking` receives snapshot and feeds `ProjectTreeState`; the Compose Explorer renders it | `ProjectExplorerSpec."Explorer renders a recursive project tree snapshot"` | covered |
| UC-MAN-05.c | `LoadEditorFolderC2S` triggers path-traversal guard, teleport, and `EditorFolderLoadedS2C` reply | `EditorNetworkRegistrySpec."handleLoadFolder rejects path traversal with EditorErrorS2C"`, `EditorNetworkRegistrySpec."handleLoadFolder happy path sends EditorFolderLoadedS2C and sets session"` | covered |
| UC-MAN-05.d | `EditorTeleport.toFolder` teleports player and calls `EditorSession.setActive` | `EditorTeleportSpec."toFolder teleports player to region and sets active subpath"` | covered |
| UC-MAN-05.e | Unknown subpath: `toFolder` returns `false`, server replies with `EditorErrorS2C` | `EditorTeleportSpec."toFolder returns false for unknown subpath and does not change session"` | covered |
| UC-MAN-06 | Create a new spec cell in the active folder *(orphaned: no live UI caller)* | `EditorNetworkRegistrySpec."handleNewSpec with active session creates file and sends EditorFolderLoadedS2C"` | **GAP-PARTIAL** |
| UC-MAN-06.a | *(historical UI path)* Player sends `NewEditorSpecC2S(name)` after typing non-blank name in the deleted `ProjectScreen` | — (`ProjectEntryFlowSpec`, the client test that covered this, was deleted with the screen it tested; the server-side handler is covered under UC-MAN-06) | **GAP** |
| UC-MAN-06.b | No active folder → `EditorErrorS2C("no folder selected")` | `EditorNetworkRegistrySpec."handleNewSpec without active session returns 'no folder selected'"` | covered |
| UC-MAN-06.c | `EditorNewSpec.create` validates name regex, non-duplicate, writes stub | `EditorNewSpecTest."create writes <name>.spec.kts with stub content"`, `EditorNewSpecTest."illegal characters in name throw"`, `EditorNewSpecTest."file already exists throws"` | covered |
| UC-MAN-06.d | `handleNewSpec` calls `placeFolder` to re-scan and re-place entire folder | `EditorNetworkRegistrySpec."handleNewSpec with active session creates file and sends EditorFolderLoadedS2C"`, `EditorDimSpec."EditorNewSpec.create writes a stub spec.kts to the leaf folder"` | covered |
| UC-MAN-06.e | `EditorFolderLoadedS2C` sent back with updated spec-id list and errors | `EditorNetworkRegistrySpec."handleNewSpec with active session creates file and sends EditorFolderLoadedS2C"` | covered |
| UC-MAN-07 | Save edited cell blocks back to disk | `EditorCellSaverSpec."mutation inside cell -> saved and .nbt written"` | covered |
| UC-MAN-07.a | "Save Now" sends `SaveNowC2S`; `handleSaveNow` calls `saveAll` | `EditorNetworkRegistrySpec."handleSaveNow returns formatted EditorSaveReportS2C"` | covered |
| UC-MAN-07.b | `saveFolder` resolves absolute cell origin and passes to `captureAndSaveIfDirty` | `EditorDimSpec."saveNow writes only specs whose cell volume changed"` | covered |
| UC-MAN-07.c | `EditorCellSaver` returns `saved=false` when NBT is equal | `EditorCellSaverSpec."no mutation -> not saved"`, `EditorCellSaverSpec."mutation outside cell bounds -> not saved"` | covered |
| UC-MAN-07.d | Dirty diff: `NbtIo.writeCompressed` overwrites `.nbt`; `.spec.kts` re-emission deferred | `EditorCellSaverSpec."mutation inside cell -> saved and .nbt written"` | **GAP-PARTIAL** |
| UC-MAN-07.e | After save, fresh snapshot stored as new `loadedSnapshot` baseline | `EditorCellSaverSpec."snapshot refresh: second saveFolder after a save returns saved=false"` | covered |
| UC-MAN-07.f | `EditorSaveReportS2C` sent with per-spec result strings | `EditorNetworkRegistrySpec."handleSaveNow returns formatted EditorSaveReportS2C"` | covered |
| UC-MAN-08 | Unload active folder and handle ungraceful session end | `EditorNetworkRegistrySpec."handleUnload clears session and sends empty save report"` | **GAP-PARTIAL** |
| UC-MAN-08.a | "Unload" sends `UnloadEditorFolderC2S`; `handleUnload` clears session and sends empty report | `EditorNetworkRegistrySpec."handleUnload clears session and sends empty save report"` | covered |
| UC-MAN-08.b | Post-unload `handleNewSpec` returns `EditorErrorS2C("no folder selected")`; `handleSaveNow` is session-independent (saves all loaded folders) | `EditorNetworkRegistrySpec."handleNewSpec without active session returns 'no folder selected'"` | covered |
| UC-MAN-08.c | Ungraceful disconnect: `EditorSession.clear` called from disconnect event | `EditorNetworkRegistrySpec."ungraceful disconnect clears the player's managed session"` | covered |
| UC-MAN-08.d | Server stop: `dispose` and `clear` calls release all server-scoped state | `EditorLifecycleReleaseTest."UC-MAN-08.d: releaseServerState disposes registry, world, and context"` | covered |
| UC-MAN-08.e | On next `placeAll`, cell is rebuilt from on-disk `.nbt` (un-saved in-world edits overwritten); only saved changes persist | — | **GAP** |
| UC-MAN-09 | Re-root the Explorer from a native folder picker | `RootPickerSpec`, `EditorNetworkRegistrySpec` | **GAP-PARTIAL** |
| UC-MAN-09.a | Kebab menu toggles the menu; Open Folder runs the `FolderPicker` on a worker thread | `RootPickerSpec."openFolder sends SetEditorRootC2S and persists the picked path"`, `ProjectExplorerSpec."Explorer header renders the root name"`, `JewelExplorerSpec."the kebab menu opens in-scene over the Blaze3D FBO"` | covered |
| UC-MAN-09.b | Non-null pick persists + sends `SetEditorRootC2S`; cancel sends nothing | `RootPickerSpec."openFolder sends SetEditorRootC2S and persists the picked path"`, `RootPickerSpec."openFolder sends nothing when the picker is cancelled"` | covered |
| UC-MAN-09.c | `handleSetRoot` validates dir, commits the old root's dirty structures (refusing the swap if any fails), swaps root, clears the old root's structure blocks and all region assignments, re-places, re-snapshots; non-dir → `EditorErrorS2C` | `EditorNetworkRegistrySpec."handleSetRoot switches root, persists it, and sends a snapshot of the new folder"`, `EditorNetworkRegistrySpec."handleSetRoot rejects a non-directory path with EditorErrorS2C"`, `EditorNetworkRegistrySpec."handleSetRoot commits the OLD root's dirty structure and never touches the NEW root's same-named file"`, `EditorNetworkRegistrySpec."a failed commit during a root swap aborts the swap and reports an error"`, `EditorNetworkRegistrySpec."a root swap clears the old root's blocks and drops region assignments that never got a placed box"` | covered |
| UC-MAN-09.d | *(Plan B)* old grid persists; region assignments accumulate; Attach not implemented | — | n/a |
| UC-MAN-10 | Place, save, create standalone `.nbt` structures; debounced auto-save + local history *(moved)* | see [structure-lifecycle.md — coverage matrix](structure-lifecycle.md#coverage-matrix) | moved |
| UC-MAN-11 | Rejoin a world and find the tree already loaded, with last session's expansion restored | `ExplorerStateStoreSpec`, `ExplorerTreeStateSpec` | **GAP-PARTIAL** |
| UC-MAN-11.a | `JOIN` handler arms the restore and sends `ListEditorTreeC2S` (guarded by `canSend`) | — | **GAP** |
| UC-MAN-11.b | Snapshot dispatch applies the pending restore; a root mismatch discards it | `ExplorerTreeStateSpec."an armed restore reopens the persisted folders when the snapshot lands"`, `ExplorerTreeStateSpec."a restore captured against a different root is discarded"` | covered |
| UC-MAN-11.c | Stale/renamed paths dropped; only folders are restored to `openNodes` | `ExplorerTreeStateSpec."paths that no longer exist are dropped from the restore"`, `ExplorerTreeStateSpec."a file path is never restored as an expanded node"` | covered |
| UC-MAN-11.d | Restore is one-shot; `reset()` disarms a pending restore | `ExplorerTreeStateSpec."the restore is one-shot: a second snapshot does not clobber live expansion"`, `ExplorerTreeStateSpec."reset disarms a pending restore"` | covered |
| UC-MAN-11.e | `ExplorerStateStore` round-trips `garnet-explorer.json`; blank root / no-snapshot skip writing | `ExplorerStateStoreSpec."a session round-trips through garnet-explorer.json"`, `ExplorerStateStoreSpec."a null selection round-trips as null rather than an empty string"`, `ExplorerStateStoreSpec."a missing file loads as null"`, `ExplorerStateStoreSpec."a malformed file loads as null instead of throwing"`, `ExplorerStateStoreSpec."a record with no root loads as null"`, `ExplorerStateStoreSpec."saving with a blank root writes nothing"` | covered |

**UI-caller gap (not a test gap):** UC-MAN-01/02/06/07/08's `.a` rows above are marked historical
because their only client trigger (`ProjectScreen`/`ProjectRootListScreen`) was deleted in the
Compose-dock hard-cut and `ProjectExplorerPanel` does not yet expose New Spec / Save Now / Unload /
multi-root controls. The server handlers remain fully covered by their gametest specs; what's missing
is a UI to drive them, tracked as future dock-panel work, not a coverage regression to backfill with
more tests.
