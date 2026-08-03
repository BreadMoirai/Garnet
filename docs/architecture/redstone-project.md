---
title: Redstone project
tags: [redstone-project, grid, persistence, overworld]
summary: Folder of `.spec.kts` files laid out as a fixed grid in a mod-managed singleplayer overworld; only per-spec bounds save back to disk.
---

# Redstone project

A redstone project is a region of an integrated-server overworld whose contents are
deterministically laid out from a folder of `.spec.kts` files. The overworld is the working
surface for both authoring new specs and running existing ones in-place. Saving back is
scoped: only blocks inside each spec's bounding region are written to its source file;
everything else (decoration, scratch builds, the void between cells) is ignored when saving.

See `docs/superpowers/specs/2026-05-08-managed-redstone-worlds-design.md` for the full design.

## Canvas: a mod-created flat-void singleplayer save

The mod creates the singleplayer save itself. From the main menu, the "Redstone Projects…"
button calls `EditorIntegratedBoot.bootWorkspace()`, which opens (or creates) a single
shared flat-void workspace save named `garnet-workspace` — root-agnostic; project
folders are loaded/unloaded in-world. `bootWorkspace()` uses the private `openOrCreateWorld`
helper: either re-open the existing save, or create a fresh flat-void singleplayer save
(creative, peaceful, allow-commands) via `WorldOpenFlows.createFreshLevel` with
`FlatLevelGeneratorPresets.THE_VOID`.

The workspace save name is the fixed `garnet-workspace`. The save is **persistent
across sessions** — re-opening reuses the same save and any user-placed scratch outside spec
bounds is preserved between opens. Spec contents are re-placed from disk on each `placeAll`.

The canvas is `server.overworld()` directly — no custom dimension type, no datapack. Each
loaded folder maps to a distinct **region** in the overworld via counter-based assignment in
`EditorDimRegistry.getOrAssignRegion`. Multiple folders coexist spatially.

## Key invariants

- **Save scope = spec bounds.** AABB = `cellOrigin..cellOrigin+spec.bounds`. Anything outside
  that AABB is ignored by `EditorCellSaver`. No decoration in the cell margin persists to
  the spec file.
- **No persisted slot.** Slot index = filename-sorted index, recomputed each `placeFolder`.
  Renaming a spec shuffles slots.
- **Server-authoritative.** Clients propose, server validates against `EditorRoot.resolveSubpath`
  (path-traversal guard) and acts — see
  [persistence/network-payload-contract.md](../persistence/network-payload-contract.md) for the
  full authority model (there is no block-entity trust anchor here; the old
  recorder/runner blocks and their `SpecBlockEntity` were deleted along with the network protocol
  that used to key off them).
- **A cell is just its placed structure.** There is no anchor block bound to the spec file
  anymore — `EditorDimLifecycle.placeCell` places the structure NBT and nothing else.
- **Cell-origin Y is absolute** (`yBase`); X/Z are region-relative.

## Components

Pure data:
- `EditorRoot` — absolute folder + path-traversal-safe `resolveSubpath` (with symlink defeat
  and `InvalidPathException` guard).
- `EditorFolderTree` — leaves vs intermediates scan; used by `EditorDimLifecycle.placeAll` for
  region placement. Separate concern from the Explorer's tree model (below).
- `FileTree` — recursive tree model (`FolderNode`/`FileNode` under sealed `FileTreeNode`, package
  `com.breadmoirai.garnet.editor.data`) built by `scanFolder(path)`; mirrors the whole folder
  (all files/folders, incl. empty), folders-first ordering. Paths are **computed, not stored** —
  `FolderNode.walk()` (node→path) and `FolderNode.resolve(path)` (path→node), both relative to
  whichever folder is the root, so re-rooting is free. This is the tree carried by
  `EditorTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` and rendered recursively by
  `ProjectExplorerPanel` (below) — the old flat `leaves`/`intermediates`/`ProjectLeafEntry` payload
  fields are gone. `FileNode` is just `(name, extension)` — there is no per-node dirty flag; a
  `.nbt`'s auto-save state lives server-side in `StructureAutoSave` (see below), not in the tree.
- `GridLayout` — `(specs, cellSize, gap, rowMax, yBase) → cells`.
- `EditorCell` — pure cell record (origin + size).
- `EditorSaveNaming` — `rootPath → project-<tail>-<8-hex-sha1>` save-name derivation (pure
  data; no live caller since the per-root boot entry was removed — retained with its unit test).

Server state and lifecycle:
- `EditorWorld` — server-wide. `perFolder: Map<subpath, Map<specId, LoadedSpec>>`,
  `folderAbsoluteByPath`, helpers like `absoluteCellOrigin`. Attached to the `MinecraftServer`.
- `LoadedSpec` — `(cell, spec, sourceFile, loadedSnapshot)`. Snapshot is the cell-volume
  template captured right after placement, used by the dirty diff.
- `EditorSession` — lightweight per-player active-folder pointer (`playerId, activeSubpath?`).
- `EditorDimRegistry` — per-server. `projectLevel()` returns `server.overworld()`;
  `getOrAssignRegion(subpath)` assigns a region origin via counter on first placement.
- `EditorDimLifecycle` — `placeAll(server, root)`, `placeFolder(server, root, subpath)`,
  `saveAll(server)`, `saveFolder(server, subpath)`.
- `EditorCellSaver` — diff cell volume vs snapshot; rewrite `.spec.kts` + structure NBT iff
  dirty.
- `EditorTeleport` — `toFolder(server, player, subpath)`. Separate concern from placement.
- `EditorNewSpec` — stub `.spec.kts` writer.
- `EditorServerContext` — per-server pin for the active root.
- `EditorRootResolver` — `rootFor(server)`: the active managed root, priority-chained —
  loaded world's, else pinned `EditorServerContext`'s, else `SharedSettings.projectRootPath`.
- `EditorCommand` — `/garnet editor`.

Network:
- `editor/network/EditorPackets` + `EditorNetworkRegistry` + `EditorTreeHandlers` /
  `EditorStructureHandlers` / `EditorFileOpsHandlers` — path-containment + per-player-session
  authority; see [persistence/network-payload-contract.md](../persistence/network-payload-contract.md).

Client:
- `editor/ui/ProjectExplorerPanel` + `editor/ui/ExplorerToolbar` + `editor/ui/ProjectTreeState` +
  `editor/ui/ExplorerTreeState` — the Compose dock panel, built on Jewel widgets (`LazyTree`,
  `PopupMenu`, `IconButton`), that renders `snapshot.root` (`FolderNode`/`FileNode`) via
  `ExplorerTreeState.buildTreeFrom`, with per-folder expand/collapse (LEFT region, hidden by
  default — Shift+1 reveals it). `ProjectTreeState` is `mutableStateOf`-backed server-driven state
  (snapshot + status) fed by the S2C receivers; `ExplorerTreeState` owns selection/expansion by
  wrapping a single Jewel `TreeState`; `explorerPanel()` returns the LEFT-dock `Panel`. A folder is
  a "spec-folder" (loadable) iff it directly contains a `FileNode` named `*.spec.kts`: clicking it
  sends `LoadEditorFolderC2S(path)`; other folders just toggle expand (Jewel `LazyTree`'s own
  click-to-toggle behavior). Clicking a file calls `ExplorerTreeState.select(path)` (highlight
  only, no packet) — except a `.nbt` `FileNode` (`node.extension == "nbt"`), which selects **and**
  sends `PlaceStructureC2S(path)` (rendered with a Jewel `AllIconsKeys.FileTypes.Archive` icon, plus
  a leading `● ` marker when the row is `currentSubpath`).
  Paths are `/`-joined relative to root, matching the server's `FolderNode.walk()` keys and
  `currentSubpath`. `ExplorerToolbar()` is the panel's single top row: a kebab `IconButton` opening
  a Jewel `PopupMenu` with "Open Folder…" (`RootPickerController.openFolder()`), plus Refresh
  (sends `ListEditorTreeC2S`) and Collapse All (`ExplorerTreeState.collapseAll()`) icon buttons.
  This replaced the previous root-name `Dropdown` and the "+ New"/"Save"/"Discard" structure-action
  row. `New`/`Rename` now have a client UI trigger again — the right-click `ExplorerContextMenu` (see
  [ui/explorer-toolbar-and-context-menu.md](../ui/explorer-toolbar-and-context-menu.md)) — while
  `Save` (`SaveStructureC2S`, now a force-commit through `StructureCommit`) still has none: it
  remains fully wired server-side and covered by `EditorStructureNetworkSpec`, with no tree-row
  action sending it yet. There is no `Discard` any more — placed structures auto-save continuously,
  so there is nothing to discard back to; see [Standalone structure files](#standalone-structure-files)
  below. `NewStructureC2S` was reshaped to
  `NewStructureC2S(parentSubpath, name)` so the context-menu "New Structure" action targets the
  folder that was right-clicked instead of the session's active folder; `handleNewStructure` now
  resolves `folder` strictly via `EditorRoot.resolveSubpath(payload.parentSubpath)` and no longer
  reads `EditorSession.activeSubpath` or `EditorWorld.folderAbsoluteByPath` at all.
  `CreateFolderC2S(parentSubpath, name)` and `RenamePathC2S(subpath, newName)` are new payloads
  registered alongside it (`PayloadTypeRegistry.serverboundPlay()`); both now have server receivers:
  `handleCreateFolder` (same folder-resolution path) and `handleRename`. `handleNewStructure`,
  `handleCreateFolder`, and `handleRename` all re-validate the final name server-side through
  `EditorNames.validate` against the destination folder's real directory listing, since the
  client's tree snapshot can be stale. `handleRename` additionally: refuses `subpath == ""` (the
  client already disables the menu item for the root, but the server does not trust that), commits
  a placed-and-dirty structure's pending edits through `StructureCommit.commit` BEFORE the file
  move (so the dirty state, keyed by subpath, is never stranded under a name nothing will commit
  again), then moves the file and carries its `LocalHistoryStore` revisions across via
  `LocalHistoryStore.moveDescendantHistories` (walks the moved subtree and calls the single-file
  `moveHistory` primitive for each `.nbt` under it, so a folder rename carries history for every
  descendant, not just the renamed node), unloads and re-places a currently-placed structure under the new
  subpath (`EditorDimRegistry.unplaceStructure` then `EditorStructureHandlers.placeStructureFrom` — the structure lands in
  a fresh region since `nextStructureIndex` is never recycled), rekeys
  every OTHER registry entry nested under a renamed folder onto the new subpath
  (`EditorDimRegistry.rekeyForRename`, same `"$oldSubpath/"` boundary as below — a pure bookkeeping
  move that never touches the world, since only the file's path changed, not its placed position),
  and repoints `EditorSession.activeSubpath` when it equals or is nested under the renamed subpath
  (boundary-safe: matching on `"$oldSubpath/"` so renaming `redstone` repoints `redstone/clocks` but
  not a sibling like `redstoneworks/clocks`). See [use-cases/structure-lifecycle.md](../use-cases/structure-lifecycle.md)
  (UC-MAN-10) for the structure-unload/reload detail and
  [use-cases/redstone-project.md](../use-cases/redstone-project.md) for the folder-rename/session
  detail. This is the **only** live client UI for browsing the project
  tree — `ProjectScreen` and `ProjectRootListScreen` (the legacy folder-browser GUI and
  world-list-screen root picker) were deleted in the Compose-dock hard-cut. See
  [ui/dock-framework.md](../ui/dock-framework.md) for the `LazyTree` render pattern and the
  `ExplorerTreeState`/`ProjectTreeState` split.
- `editor/network/EditorClientNetworking` — S2C receivers. They feed `ProjectTreeState`
  (snapshot/folder-loaded/save-report/error/structure-result/auto-saved); no client screen is
  opened in response. `StructureResultS2C` → `ProjectTreeState.onStructureResult` sets `status` to
  `r.message` (place/save/new-structure outcomes all surface through the same status line as
  folder load/save results). `StructureAutoSavedS2C` → `ProjectTreeState.onAutoSaved` sets
  `status` to `"auto-saved $subpath ($sizeX×$sizeY×$sizeZ, $blockCount blocks)"`. The packet is
  broadcast by `StructureCommit` (see the "Standalone structure files" section below), from both
  the end-of-tick debounce pass and the `commitAll` backstop.
- `editor/world/EditorIntegratedBoot` — `bootWorkspace()` (the only boot entry, reachable from
  the UI via `TitleScreenMixin`) opens/creates the single shared `garnet-workspace` save
  with no root pinned. The dormant `pendingRoot`/`EditorServerContext` pinning machinery is
  retained for programmatic use, but no caller sets `pendingRoot`, so the SERVER_STARTING listener
  is a no-op.
- `client/mixin/TitleScreenMixin` (Java) — injects "Redstone Projects…" button into the main
  menu (calls `EditorIntegratedBoot.bootWorkspace()` directly) so it is reachable even with no
  singleplayer worlds.

## Standalone structure files

`.nbt` files are also first-class in the Explorer, independent of specs. Clicking a `.nbt`
places it (`StructurePersistence.placeStructureCentered`) centered in an auto-assigned region
(`EditorDimRegistry.getOrAssignStructureRegion`, a disjoint +X lane at
`z = STRUCTURE_LANE_Z = 4096`), floored at `projectGridYBase` (64) — or vertically centered when
the structure's height ≥ `TALL_THRESHOLD` (256). "Save Structure" is a force-commit through
`StructureCommit.commit`, which auto-fits the tight non-air box over
`union(placedBox, dirtyBox)` (`StructurePersistence.captureAutoFitIn` → `project.autoFit`) and
rewrites the file.
"New Structure" (`EditorNewStructure.create`) writes an empty `<name>.nbt` into the folder named
by `NewStructureC2S.parentSubpath` (`""` = the project root).

- **Region size:** `SharedSettings.structureRegionChunks` (default 9 → 144×144 blocks), full
  world height.
- **Cheap re-clear:** the registry tracks the last-placed `PlacedBox` per structure subpath;
  re-placing clears only that footprint, not the whole region.
- **Packets:** `PlaceStructureC2S` / `SaveStructureC2S` / `NewStructureC2S` →
  `StructureResultS2C`, handled by
  `EditorStructureHandlers.handlePlaceStructure/handleSaveStructure/handleNewStructure`. There is no
  `DiscardStructureC2S` any more — see below.
- **Debounced auto-save + local history (the only auto-persist path):** `StructureCommit` writes
  the `.nbt` directly; there is no dirty-buffer sidecar. `ServerTickEvents.END_SERVER_TICK` calls
  `StructureCommit.tick`, which commits any placed structure whose `StructureAutoSave` dirty state
  has gone quiet for `SharedSettings.autoSaveDebounceTicks` (or has been continuously dirty for
  `autoSaveMaxDirtyTicks`); `ServerLifecycleEvents.BEFORE_SAVE` and `SERVER_STOPPING` both call
  `StructureCommit.commitAll` as a backstop flush regardless of timing — both fire while every
  level is still live (`SERVER_STOPPED`, by contrast, fires at `stopServer` TAIL after every level
  has closed, so retrying a failed commit there against a closed `ServerLevel` is unsafe; it's used
  only for the disposal calls below, not for `commitAll`). `SaveStructureC2S` →
  `handleSaveStructure` is a force-commit through the same `StructureCommit.commit` (reason
  `REASON_MANUAL`), so "Save Structure" and auto-save write through the identical path. A commit
  scans only `union(placedBox, dirtyBox)` via `StructurePersistence.captureAutoFitIn` — never the
  full 144×full-height region — records a `LocalHistoryStore` revision capturing the NEWLY CAPTURED
  content BEFORE overwriting the `.nbt` with it (so that content is durably banked before it
  becomes the live file; the pre-edit content itself lives in the *previous* revision, banked by an
  earlier commit — see `docs/persistence/local-history.md` for the rollback implication), and
  broadcasts `StructureAutoSavedS2C`. `SERVER_STOPPED` calls `StructureAutoSave.dispose`
  so per-server dirty state cannot leak across server lifecycles. Since there is no dirty buffer to
  revert to, there is no "Discard" action — an edit's only rollback path is `LocalHistoryStore`.
  See `docs/persistence/local-history.md` for the on-disk layout and pruning policy, and
  `docs/superpowers/specs/2026-07-31-structure-autosave-local-history-design.md` for the design
  history.

## Where to start reading

- *"How is the world created?"* → `EditorIntegratedBoot.bootWorkspace` and its private
  `openOrCreateWorld`.
- *"How does placement work for the whole tree?"* → `EditorDimLifecycle.placeAll`.
- *"How does placement work for one folder?"* → `EditorDimLifecycle.placeFolder` (and
  internal `placeFolderInto` / `placeCell`).
- *"What gets saved?"* → `EditorCellSaver.captureAndSaveIfDirty`, called from
  `EditorDimLifecycle.saveFolder`.
- *"How are folders placed in the overworld?"* → `EditorDimRegistry.getOrAssignRegion`.
- *"How does the GUI show the folder tree?"* → `ProjectExplorerPanel` reading `ProjectTreeState`
  (fed from `EditorTreeSnapshotS2C`) — the only client UI for this since the legacy `ProjectScreen`
  was hard-cut.

## Known limitations (v1)

- **Region partitioning is counter-based and in-memory.** Region origins are stable within a
  server lifetime (subpath-sorted assignment) but rebuild on each server start. The blocks
  in the overworld persist; the registry mapping does not.
