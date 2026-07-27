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
button calls `ProjectIntegratedBoot.bootWorkspace()`, which opens (or creates) a single
shared flat-void workspace save named `redstonespecs-workspace` — root-agnostic; project
folders are loaded/unloaded in-world. `bootWorkspace()` uses the private `openOrCreateWorld`
helper: either re-open the existing save, or create a fresh flat-void singleplayer save
(creative, peaceful, allow-commands) via `WorldOpenFlows.createFreshLevel` with
`FlatLevelGeneratorPresets.THE_VOID`.

The workspace save name is the fixed `redstonespecs-workspace`. The save is **persistent
across sessions** — re-opening reuses the same save and any user-placed scratch outside spec
bounds is preserved between opens. Spec contents are re-placed from disk on each `placeAll`.

The canvas is `server.overworld()` directly — no custom dimension type, no datapack. Each
loaded folder maps to a distinct **region** in the overworld via counter-based assignment in
`ProjectDimRegistry.getOrAssignRegion`. Multiple folders coexist spatially.

## Key invariants

- **Save scope = spec bounds.** AABB = `cellOrigin..cellOrigin+spec.bounds`. Anything outside
  that AABB is ignored by `ProjectCellSaver`. No decoration in the cell margin persists to
  the spec file.
- **No persisted slot.** Slot index = filename-sorted index, recomputed each `placeFolder`.
  Renaming a spec shuffles slots.
- **Server-authoritative.** Same model as `network/Packets.kt`: clients propose, server
  validates against `ProjectRoot.resolveSubpath` (path-traversal guard) and acts.
- **`SpecBlockEntity.projectSourcePath`** binds a recorder/runner block in a project cell
  back to its source `.spec.kts`. NOT persisted to NBT — set directly on the BE during
  `placeFolder` and reset on every re-place.
- **Cell-origin Y is absolute** (`yBase`); X/Z are region-relative.

## Components

Pure data:
- `ProjectRoot` — absolute folder + path-traversal-safe `resolveSubpath` (with symlink defeat
  and `InvalidPathException` guard).
- `ProjectFolderTree` — leaves vs intermediates scan; used by `ProjectDimLifecycle.placeAll` for
  region placement. Separate concern from the Explorer's tree model (below).
- `FileTree` — recursive tree model (`FolderNode`/`FileNode` under sealed `FileTreeNode`, package
  `com.breadmoirai.redstonespecs.project`) built by `scanFolder(path)`; mirrors the whole folder
  (all files/folders, incl. empty), folders-first ordering. Paths are **computed, not stored** —
  `FolderNode.walk()` (node→path) and `FolderNode.resolve(path)` (path→node), both relative to
  whichever folder is the root, so re-rooting is free. This is the tree carried by
  `ProjectTreeSnapshotS2C(root: FolderNode, currentSubpath: String?)` and rendered recursively by
  `ProjectExplorerPanel` (below) — the old flat `leaves`/`intermediates`/`ProjectLeafEntry` payload
  fields are gone. `FileNode.hasUnsaved: Boolean = false` flags a `<name>.nbt` node that has a
  sibling `<name>.nbt.unsaved` dirty-buffer sidecar; `scanFolder` sets it and omits the sidecar
  file itself from the tree. `FILE_TREE_STREAM_CODEC` (in `ProjectPackets.kt`) serializes it as a
  trailing boolean on the file-node tag.
- `GridLayout` — `(specs, cellSize, gap, rowMax, yBase) → cells`.
- `ProjectCell` — pure cell record (origin + size).
- `ProjectSaveNaming` — `rootPath → project-<tail>-<8-hex-sha1>` save-name derivation (pure
  data; no live caller since the per-root boot entry was removed — retained with its unit test).

Server state and lifecycle:
- `ProjectWorld` — server-wide. `perFolder: Map<subpath, Map<specId, LoadedSpec>>`,
  `folderAbsoluteByPath`, helpers like `absoluteCellOrigin`. Attached to the `MinecraftServer`.
- `LoadedSpec` — `(cell, spec, sourceFile, loadedSnapshot)`. Snapshot is the cell-volume
  template captured right after placement, used by the dirty diff.
- `ProjectSession` — lightweight per-player active-folder pointer (`playerId, activeSubpath?`).
- `ProjectDimRegistry` — per-server. `projectLevel()` returns `server.overworld()`;
  `getOrAssignRegion(subpath)` assigns a region origin via counter on first placement.
- `ProjectDimLifecycle` — `placeAll(server, root)`, `placeFolder(server, root, subpath)`,
  `saveAll(server)`, `saveFolder(server, subpath)`.
- `ProjectCellSaver` — diff cell volume vs snapshot; rewrite `.spec.kts` + structure NBT iff
  dirty.
- `ProjectTeleport` — `toFolder(server, player, subpath)`. Separate concern from placement.
- `ProjectNewSpec` — stub `.spec.kts` writer.
- `ProjectServerContext` — per-server pin for the active root.
- `ProjectCommand` — `/redstonespecs project`.

Network:
- `network/project/ProjectPackets` + `ProjectNetworkRegistry` — same authority pattern as
  `network/Packets.kt`.

Client:
- `client/ide/ProjectExplorerPanel` + `client/ide/ProjectTreeState` — the Compose dock panel that
  recursively renders `snapshot.root.children` (`FolderNode`/`FileNode`), with per-folder
  expand/collapse (LEFT region, hidden by default — Shift+1 reveals it). `ProjectTreeState` is
  `mutableStateOf`-backed client state fed by the S2C receivers; `explorerPanel()` returns the
  LEFT-dock `Panel`. A folder is a "spec-folder" (loadable) iff it directly contains a `FileNode`
  named `*.spec.kts`: clicking its label then sends `LoadProjectFolderC2S(path)`; otherwise the
  label (like the triangle) just toggles expand via `ProjectTreeState.toggleExpanded(path)`.
  Clicking a file calls `ProjectTreeState.select(path)` (highlight only, no packet) — except a
  `.nbt` `FileNode` (`node.extension == "nbt"`), which selects **and** sends
  `PlaceStructureC2S(path)` (rendered with a `▶` prefix as a distinct affordance). A
  `StructureActions()` row under `Header()` provides "+ Structure" (a `BasicTextField` name input
  that sends `NewStructureC2S(name)`) and "Save Structure" (sends `SaveStructureC2S(selectedPath)`
  when `ProjectTreeState.selectedPath` ends with `.nbt`). Paths are
  `/`-joined relative to root, matching the server's `FolderNode.walk()` keys and
  `currentSubpath`. The Refresh row sends `ListProjectTreeC2S`. This is the **only** live client UI
  for browsing the project tree — `ProjectScreen` and `ProjectRootListScreen` (the legacy
  folder-browser GUI and world-list-screen root picker) were deleted in the Compose-dock hard-cut.
  See [ui/dock-framework.md](../ui/dock-framework.md) for the render pattern (recursion, expand
  state, and why it's `Column`+`verticalScroll` rather than `LazyColumn`).
- `client/project/ProjectClientNetworking` — S2C receivers. They feed `ProjectTreeState`
  (snapshot/folder-loaded/save-report/error/structure-result); no client screen is opened in
  response. `StructureResultS2C` → `ProjectTreeState.onStructureResult` sets `status` to
  `r.message` (place/save/new-structure outcomes all surface through the same status line as
  folder load/save results).
- `client/project/ProjectIntegratedBoot` — `bootWorkspace()` (the only boot entry, reachable from
  the UI via `TitleScreenMixin`) opens/creates the single shared `redstonespecs-workspace` save
  with no root pinned. The dormant `pendingRoot`/`ProjectServerContext` pinning machinery is
  retained for programmatic use, but no caller sets `pendingRoot`, so the SERVER_STARTING listener
  is a no-op.
- `client/mixin/TitleScreenMixin` (Java) — injects "Redstone Projects…" button into the main
  menu (calls `ProjectIntegratedBoot.bootWorkspace()` directly) so it is reachable even with no
  singleplayer worlds.

## Standalone structure files

`.nbt` files are also first-class in the Explorer, independent of specs. Clicking a `.nbt`
places it (`StructurePersistence.placeStructureCentered`) centered in an auto-assigned region
(`ProjectDimRegistry.getOrAssignStructureRegion`, a disjoint +X lane at
`z = STRUCTURE_LANE_Z = 4096`), floored at `projectGridYBase` (64) — or vertically centered when
the structure's height ≥ `TALL_THRESHOLD` (256). "Save Structure" auto-fits the tight non-air box
in the region (`StructurePersistence.saveAutoFitToFile` → `project.autoFit`) and rewrites the file.
"New Structure" (`ProjectNewStructure.create`) writes an empty `<name>.nbt` into the active folder.

- **Region size:** `SharedSettings.structureRegionChunks` (default 9 → 144×144 blocks), full
  world height.
- **Cheap re-clear:** the registry tracks the last-placed `PlacedBox` per structure subpath;
  re-placing clears only that footprint, not the whole region.
- **Packets:** `PlaceStructureC2S` / `SaveStructureC2S` / `NewStructureC2S` → `StructureResultS2C`,
  handled by `ProjectNetworkRegistry.handlePlaceStructure/handleSaveStructure/handleNewStructure`.

## Where to start reading

- *"How is the world created?"* → `ProjectIntegratedBoot.bootWorkspace` and its private
  `openOrCreateWorld`.
- *"How does placement work for the whole tree?"* → `ProjectDimLifecycle.placeAll`.
- *"How does placement work for one folder?"* → `ProjectDimLifecycle.placeFolder` (and
  internal `placeFolderInto` / `placeCell`).
- *"What gets saved?"* → `ProjectCellSaver.captureAndSaveIfDirty`, called from
  `ProjectDimLifecycle.saveFolder`.
- *"How are folders placed in the overworld?"* → `ProjectDimRegistry.getOrAssignRegion`.
- *"How does the GUI show the folder tree?"* → `ProjectExplorerPanel` reading `ProjectTreeState`
  (fed from `ProjectTreeSnapshotS2C`) — the only client UI for this since the legacy `ProjectScreen`
  was hard-cut.

## Known limitations (v1)

- **Region partitioning is counter-based and in-memory.** Region origins are stable within a
  server lifetime (subpath-sorted assignment) but rebuild on each server start. The blocks
  in the overworld persist; the registry mapping does not.
