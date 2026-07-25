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
folders are loaded/unloaded in-world. Both `bootWorkspace()` and the per-root
`ProjectIntegratedBoot.boot(rootPath)` reuse the same private `openOrCreateWorld` machinery:
either re-open the existing save, or create a fresh flat-void singleplayer save (creative,
peaceful, allow-commands) via `WorldOpenFlows.createFreshLevel` with
`FlatLevelGeneratorPresets.THE_VOID`.

The save name is `project-<root-tail>-<8-hex-pathHash>` (see `ProjectSaveNaming`). Saves are
**persistent across sessions** — re-opening a project root opens the same save and any
user-placed scratch outside spec bounds is preserved between opens. Spec contents are
re-placed from disk on each `placeAll`.

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
- `ProjectFolderTree` — leaves vs intermediates scan.
- `GridLayout` — `(specs, cellSize, gap, rowMax, yBase) → cells`.
- `ProjectCell` — pure cell record (origin + size).
- `ProjectSaveNaming` — `rootPath → project-<tail>-<8-hex-sha1>` save-name derivation.
- `ProjectRootsConfig` — client-side persistent root list.

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
  renders the folder tree (LEFT region, hidden by default — Shift+1 reveals it). `ProjectTreeState` is
  `mutableStateOf`-backed client state fed by the S2C receivers; `explorerPanel()` returns the
  LEFT-dock `Panel`. Clicking a leaf sends `LoadProjectFolderC2S`; the Refresh row sends
  `ListProjectTreeC2S`. This is the **only** live client UI for browsing the project tree —
  `ProjectScreen` and `ProjectRootListScreen` (the legacy folder-browser GUI and world-list-screen
  root picker) were deleted in the Compose-dock hard-cut. See [ui/dock-framework.md](../ui/dock-framework.md).
- `client/project/ProjectClientNetworking` — S2C receivers. They feed `ProjectTreeState`
  (snapshot/folder-loaded/save-report/error); no client screen is opened in response.
- `client/project/ProjectIntegratedBoot` — `bootWorkspace()` (the only path reachable from the UI,
  via `TitleScreenMixin`) opens/creates the single shared `redstonespecs-workspace` save with no
  root pinned. `boot(rootPath)` (per-root save, pins a `ProjectServerContext`) and
  `ProjectRootsConfig` (persisted multi-root list) still exist and are exercised by unit tests, but
  are **orphaned from the UI** — their only caller was `ProjectRootListScreen`, which no longer
  exists. Nothing currently calls `boot(rootPath)` or `ProjectRootsConfig.load/save` outside tests.
- `client/mixin/TitleScreenMixin` (Java) — injects "Redstone Projects…" button into the main
  menu (calls `ProjectIntegratedBoot.bootWorkspace()` directly) so it is reachable even with no
  singleplayer worlds.

## Where to start reading

- *"How is the world created?"* → `ProjectIntegratedBoot.boot` and its private
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

- **Per-root save name uses an 8-hex SHA-1 of the absolute path** to disambiguate roots that
  share the same final path component. Collisions are astronomically unlikely but
  theoretically possible — two distinct roots hashing to the same 8 hex chars would alias to
  the same save.
- **Switching project roots within a session leaves the previous root's regions in the world
  visually only.** The data is on the previous save's disk and is unaffected; the next time
  you open that root, the same save reopens and `placeAll` repopulates the regions from disk.
- **Region partitioning is counter-based and in-memory.** Region origins are stable within a
  server lifetime (subpath-sorted assignment) but rebuild on each server start. The blocks
  in the overworld persist; the registry mapping does not.
