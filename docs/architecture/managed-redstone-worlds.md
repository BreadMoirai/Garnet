---
title: Managed redstone worlds
tags: [managed-worlds, dimensions, grid, persistence, datapack]
summary: Void-dim workspace per folder, generated as a runtime datapack; specs laid out in a fixed grid; only per-spec bounds save back to disk.
---

# Managed redstone worlds

A managed redstone world is a void dimension whose contents are deterministically laid out
from a folder of `.spec.kts` files. The world is the working surface for both authoring new
specs and running existing ones in-place. Saving back is scoped: only blocks inside each
spec's bounding region are written to its source file; everything else (decoration, scratch
builds, the void between cells) is discarded on unload.

See `docs/superpowers/specs/2026-05-08-managed-redstone-worlds-design.md` for the full design.

## Two-mode runtime (per-folder dim with single-dim fallback)

Vanilla creates dimensions at server bootstrap from JSON files under `data/<ns>/dimension/`.
Fabric's runtime-dim API (`FabricDimensions.add`) was removed in v5 (MC 26.1), so we
generate the JSONs ourselves into a per-save **runtime datapack** at server start. Vanilla
loads them on the *next* server restart (timing limitation — registry freezes before our
hook fires).

That gives two runtime modes for any given folder:

- **Per-folder dim** (preferred): `redstonespecs:managed/<sanitized-subpath>`. Cell origins
  are absolute; player teleports into a dedicated dim per folder. Available after one
  full restart following first-time folder discovery.
- **Single-dim + region fallback**: a statically-registered `redstonespecs:managed` dim
  hosts all folders. Each loaded folder gets a region-origin assigned by counter
  (`ManagedDimRegistry.getOrAssignRegion`), and cell origins are offset by the region
  origin. Used for folders created mid-session, or on the first run of any save.

`ManagedDimLifecycle.load` checks `perFolderLevel(subpath)` first and falls back to
`managedLevel() + region` if it's null.

## Key invariants

- **Save scope = spec bounds.** AABB = `cellOrigin..cellOrigin+spec.bounds`. Anything outside
  is discarded on unload. No decoration in the cell margin persists.
- **No persisted slot.** Slot index = filename-sorted index, recomputed each load.
  Renaming a spec shuffles slots. Slots are recomputed on every load — the dim is ephemeral.
- **Server-authoritative.** Same model as `network/Packets.kt`: clients propose, server
  validates against `ManagedRoot.resolveSubpath` (path-traversal guard) and acts.
- **`SpecBlockEntity.managedSourcePath`** binds a recorder/runner block in a managed cell
  back to its source `.spec.kts`. NOT persisted to NBT — managed cells rebuild from disk
  every load.
- **Cell-origin Y is absolute** (`yBase`); only X/Z are region-relative under fallback.

## Components

Pure data:
- `ManagedRoot` — absolute folder + path-traversal-safe `resolveSubpath` (with symlink defeat).
- `ManagedFolderTree` — leaves vs intermediates scan.
- `GridLayout` — `(specs, cellSize, gap, rowMax, yBase) → cells`.
- `DimIdSanitizer` — subpath → `managed/<sanitized>`.
- `ManagedRootsConfig` — client-side persistent root list.

Server lifecycle:
- `ManagedDimensions` — `DIMENSION_TYPE_KEY`, single `MANAGED_LEVEL_KEY`, per-folder `levelKey(sanitizedPath)`.
- `ManagedDatapackWriter` — generates per-folder LevelStem JSONs into `<saveDir>/datapacks/redstonespecs-managed/`.
- `ManagedDimRegistry` — per-server. `perFolderLevel(subpath)` (preferred) and `managedLevel() + getOrAssignRegion(subpath)` (fallback).
- `ManagedSession` — per-player loaded-folder state with `regionOrigin`, in-memory dirty-diff snapshot.
- `ManagedDimLifecycle` — `load`, `saveNow`, `unload`.
- `ManagedCellSaver` — diff cell volume vs snapshot, write `.spec.kts` + structure NBT iff dirty.
- `ManagedNewSpec` — stub-file creator.
- `ManagedServerContext` — per-server pin for the active root.
- `ManagedCommand` — `/redstonespecs managed`.

Network:
- `network/managed/ManagedPackets` + `ManagedNetworkRegistry` — same authority pattern as `network/Packets.kt`.

Client:
- `client/managed/ManagedScreen` — folder browser GUI.
- `client/managed/ManagedRootListScreen` — world-list-screen popup; persisted root list.
- `client/managed/ManagedClientNetworking` — S2C receivers (also opens `ManagedScreen` on tree-snapshot when none open).
- `client/managed/ManagedIntegratedBoot` — pins context + writes datapack on next server start.
- `client/mixin/SelectWorldScreenMixin` (Java) — injects "Managed Specs…" button.

## Where to start reading

- *"How does loading a folder work?"* → `ManagedDimLifecycle.load`.
- *"What gets saved?"* → `ManagedCellSaver.captureAndSaveIfDirty`.
- *"How are dims registered?"* → `ManagedDatapackWriter.writeForRoot` + the static
  `src/main/resources/data/redstonespecs/dimension/managed.json`.
- *"How does the GUI show the folder tree?"* → `ManagedScreen` + `ManagedTreeSnapshotS2C`.

## Known limitations (v1)

- **Per-folder dims appear on the second server start**, not the first. Reason: the datapack
  is written from a `SERVER_STARTING` listener, which fires after vanilla's level-stem
  registry has frozen. The fallback single-dim + region path covers the first-start case.
- **Gametest coverage is skipped**: Fabric's `GameTestServer` doesn't load datapack-defined
  dimensions. The three intended gametests in `src/gametest/.../managed/ManagedDimSpec.kt`
  are `xtest` placeholders; manual `runClient` smoke is the verification path for v1.
- **No data-gen at build time**: dim JSONs are generated at runtime per-save, since folder
  contents aren't known at build time.
