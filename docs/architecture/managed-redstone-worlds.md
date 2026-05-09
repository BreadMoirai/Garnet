---
title: Managed redstone worlds
tags: [managed-worlds, grid, persistence, overworld]
summary: Folder of `.spec.kts` files laid out as a fixed grid in the integrated server's overworld; only per-spec bounds save back to disk.
---

# Managed redstone worlds

A managed redstone world is a region of the integrated server's overworld whose contents are
deterministically laid out from a folder of `.spec.kts` files. The overworld is the working
surface for both authoring new specs and running existing ones in-place. Saving back is
scoped: only blocks inside each spec's bounding region are written to its source file;
everything else (decoration, scratch builds, the void between cells) is discarded on unload.

See `docs/superpowers/specs/2026-05-08-managed-redstone-worlds-design.md` for the full design.

## Canvas: `server.overworld()`

The managed canvas is the overworld of whichever singleplayer world the user opens. There is
no custom dimension type, dimension JSON, or runtime datapack — the user is expected to
create a flat-void singleplayer world (any world will work, but cells overwrite blocks at
their origin AABB on load). Region partitioning maps each loaded folder to a distinct
overworld origin via counter-based assignment in `ManagedDimRegistry.getOrAssignRegion`, so
multiple folders coexist in the same overworld without colliding.

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
- **Cell-origin Y is absolute** (`yBase`); X/Z are region-relative.

## Components

Pure data:
- `ManagedRoot` — absolute folder + path-traversal-safe `resolveSubpath` (with symlink defeat).
- `ManagedFolderTree` — leaves vs intermediates scan.
- `GridLayout` — `(specs, cellSize, gap, rowMax, yBase) → cells`.
- `DimIdSanitizer` — subpath → `managed/<sanitized>`.
- `ManagedRootsConfig` — client-side persistent root list.

Server lifecycle:
- `ManagedDimRegistry` — per-server. `managedLevel()` returns `server.overworld()`; `getOrAssignRegion(subpath)` assigns a region origin via counter on first load.
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
- `client/managed/ManagedIntegratedBoot` — pins context on next server start.
- `client/mixin/SelectWorldScreenMixin` (Java) — injects "Managed Specs…" button.

## Where to start reading

- *"How does loading a folder work?"* → `ManagedDimLifecycle.load`.
- *"What gets saved?"* → `ManagedCellSaver.captureAndSaveIfDirty`.
- *"How are folders placed in the overworld?"* → `ManagedDimRegistry.getOrAssignRegion`.
- *"How does the GUI show the folder tree?"* → `ManagedScreen` + `ManagedTreeSnapshotS2C`.

## Known limitations (v1)

- **Managed canvas assumes the overworld is flat-void.** In non-void overworlds, cells
  overwrite blocks at their origin AABB on load. The user is expected to create a flat-void
  world (Singleplayer → Create World → World Type: Superflat → preset "The Void").
- **Gametest coverage is skipped**: the three intended gametests in
  `src/gametest/.../managed/ManagedDimSpec.kt` are `xtest` placeholders; manual `runClient`
  smoke is the verification path for v1.
