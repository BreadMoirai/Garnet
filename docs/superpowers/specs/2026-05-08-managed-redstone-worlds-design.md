---
title: Managed Redstone Worlds — Design
tags: [managed-worlds, dimensions, grid, persistence, design]
summary: A void-dim-per-folder workspace that lays out a folder of .spec.kts files in a fixed grid for in-world authoring and running, persisting only per-spec bounding-region changes.
---

# Managed Redstone Worlds — Design

**Status:** approved 2026-05-08, awaiting plan.

## 1. Purpose

A "managed redstone world" is a void dimension whose contents are deterministically laid out from a folder of `.spec.kts` files. The world is the working surface for both **authoring** new specs and **running** existing ones in-place. Saving back is scoped: only blocks inside each spec's bounding region are written to its source file; everything else (decoration, scratch builds, the empty void between cells) is discarded on unload.

Use cases:

- Open a folder (typically a git repo) of related specs and walk through them as a side-by-side gallery.
- Author new specs in a clean, predictable layout instead of cluttering a survival/creative world.
- Iterate: edit a spec's contents in its cell, save, run, commit via git outside the game.

## 2. Scope

**In scope (v1):**

- One `ManagedRoot` selected from the world-selection screen (via an injected button) on the client; one `managedRoot` server-config entry on dedicated servers.
- A nested folder tree under the root; each leaf folder (folder containing `.spec.kts` files) maps to one void dimension.
- Fixed-size grid layout per cell, deterministic slot assignment by filename sort.
- Recorder block at cell origin for new specs; runner block for existing specs (with a preloaded structure).
- On-demand save and on-unload save of dirty cells back to `.spec.kts` + structure NBT.
- Singleplayer (integrated server) and dedicated-server, server-authoritative.

**Out of scope (v1):**

- Git operations from the mod (the folder is just a directory; user runs git themselves).
- Rename/move/copy specs from in-world (do it on the filesystem and reload).
- Cross-folder operations.
- Per-spec custom cell sizes.
- Lazy/paged grid materialization.
- Mirroring the folder client-side on dedicated servers.

## 3. Component Map

New top-level package `managed/`. Reuses existing recorder/runner/finalize/persistence pipelines unchanged.

| Unit | Layer | Responsibility |
|---|---|---|
| `ManagedRoot` | data | Value type wrapping an absolute folder path. |
| `ManagedFolderTree` | data | Recursive scan of a `ManagedRoot`. Distinguishes leaf folders (have `.spec.kts`) from intermediates (navigation only). |
| `GridLayout` | data | Pure function `(specs, cellSize, rowMax) → Map<spec-id, ManagedCell>`. |
| `ManagedDimRegistry` | server | Owns dynamic `ServerLevel` registration keyed by subpath; maintains `Map<BlockPos, spec-id>` per dim. |
| `ManagedDimLifecycle` | server | Orchestrates load (parse → layout → place) and unload (capture-and-save → close). |
| `ManagedCellSaver` | server | For one spec, scan the cell volume and rewrite `.spec.kts` + structure NBT iff dirty. |
| `ManagedNetwork` | network | New C2S/S2C payloads (Section 6). |
| `ManagedScreen` | client | In-game folder browser. |
| `ManagedRootListScreen` | client | World-selection-screen extension: list and pick a `ManagedRoot`. |

**Dependency direction** (extends existing arrows):

```
data/managed/  ←  runner/ (reused)  ←  block/ (reused)  ←  network/managed/  ←  client/managed/
```

No new upward dependencies.

## 4. Folder → Dimension Mapping

The relative subpath under the root is the dim path:

```
<root>/pistons/doors/2x2/  →  redstonespecs:managed/pistons/doors/2x2
```

**Sanitization.** Lowercase; replace any character outside `[a-z0-9_/.-]` with `_`. The sanitized id is the wire form; the canonical lookup key is the **original subpath**. The original subpath is stored in the dim's persistent state so we can round-trip even if two paths sanitize the same.

**Collision.** If two distinct subpaths sanitize to the same dim id, the second `LoadManagedFolder` fails with a `ManagedError("dim id collision")`; user must rename a folder.

**Dim type.** A single registered `DimensionType` `redstonespecs:managed_void`: no skylight, no spawning, void chunk generator (empty chunks, fixed sea level). Per-folder `LevelStem`s reuse this type; only the `ResourceKey<Level>` differs per folder.

**Dynamic registration.** Use Fabric's `Dimensions.add(...)` to register a `LevelStem` at runtime when a folder is loaded for the first time in a session. The `ServerLevel` is created via vanilla's level map. Once registered, the key stays alive for the session (rapid folder swap doesn't churn registration); only the level instance is recreated on reload.

## 5. Lifecycle

### Load (`ManagedDimLifecycle.load(server, root, subpath)`)

1. Resolve folder; refuse if intermediate (no direct `.spec.kts`).
2. Parse every `.spec.kts` via `KtsSpecLoader`. Failures collected as per-file `ManagedError` entries; survivors continue.
3. Compute `GridLayout` (Section 7). Specs with `bounds.size > cellSize` are excluded with an error.
4. Register the dim if new; obtain `ServerLevel`. Add a forced spawn ticket at cell `(0, 0)` so the entry area is always loaded.
5. For each surviving spec:
   - Place its structure (if any) at `cellOrigin` via existing `StructurePersistence`.
   - For an existing spec (has structure), place a **runner** block at `cellOrigin + (bounds.size.x, 0, 0) + (1, 0, 0)` (just past the +X face of the bounds, on the floor), bound to spec id.
   - For a new spec (no structure yet), place a **recorder** block at `cellOrigin`, bound to spec id and source path.
   - Place a cell-corner label (text-display entity) at `cellOrigin + (0, bounds.size.y + 1, 0)` showing the filename.
6. Take an in-memory snapshot of each cell's bounds region (the "loaded snapshot") for later dirty-diff.
7. Teleport the requesting player to the dim spawn (cell `(0, 0)` origin, +y a few blocks).

### Unload (`ManagedDimLifecycle.unload(server, key)`)

Triggered by: GUI folder-swap, GUI "Save & close", world close, server stop.

1. For every spec in the dim, run `ManagedCellSaver.captureAndSaveIfDirty`.
2. Discard the level (no MC-level save). Forced spawn ticket released.
3. Keep the `ResourceKey<Level>` registered for the session.

### Save now (on-demand)

Same per-spec save step as unload, but the dim stays open. After a successful save, the in-memory snapshot for that spec is refreshed so subsequent dirty checks compare against the just-saved state.

## 6. Save Semantics

**Per-spec save scope** = the AABB `cellOrigin + spec.bounds`. Anything outside this AABB inside the cell is discarded on unload. This is what "ephemeral, only in-scope changes saved" means in practice.

**Dirty check** = block-by-block diff of the cell volume against the loaded snapshot. Empty diff → no file write; non-empty diff → rewrite both `.spec.kts` and structure NBT.

**Write paths.** The spec's source file path is threaded through the BE on auto-bind in a managed dim. `ManagedCellSaver` writes back to that exact path:

- `.spec.kts` via `KtsSpecEmitter` (existing).
- Structure NBT via `StructurePersistence` (existing).

Unchanged specs touch nothing on disk (so git stays clean).

**Failure handling.** Filesystem write failure flags the spec as still dirty (next save retries) and surfaces in `ManagedSaveReport`. Original file untouched.

**Recorder finalize in a managed cell** uses the existing finalize pipeline but writes back to the spec's source path instead of creating a new file. The runner block for that spec is placed (or replaced) on the next dim reload — or eagerly when finalize completes, whichever is simpler to implement; either is acceptable since the dim is ephemeral.

## 7. Grid Layout

**Config (in `SharedSettings`)** — server-authoritative:

| Key | Default | Purpose |
|---|---|---|
| `cellSize` | `Vec3i(32, 32, 32)` | Per-cell volume. Wraps the largest expected spec bounds plus margin. |
| `cellGap` | `4` | Empty void blocks between cells (visual separation, redstone isolation). |
| `rowMax` | `8` | Cells per row in +X before wrapping to next row in +Z. |
| `gridYBase` | `64` | Y of all cell origins. |

**Slot assignment** (`GridLayout.compute`):

1. Sort specs by source filename (relative to folder), case-insensitive lexicographic; tiebreak by spec id.
2. Walk in order; assign `slotIndex = 0..n-1`.
3. `(slotX, slotZ) = (slotIndex % rowMax, slotIndex / rowMax)`.
4. `cellOrigin = BlockPos(slotX * (cellSize.x + cellGap), gridYBase, slotZ * (cellSize.z + cellGap))`.
5. Output: `Map<spec-id, ManagedCell(origin, cellSize)>` plus inverse `Map<BlockPos, spec-id>` for cell-membership lookup.

**Validation.** A spec with `bounds.size > cellSize` on any axis is excluded; GUI surfaces the offending dimensions with hint "increase `cellSize` ≥ X".

**No persisted slot.** Renaming a spec or adding/removing one will shuffle slots on next load. Acceptable because the dim is ephemeral and the contents are reconstructed from disk.

## 8. Cell Contents

| Spec state | Cell contents |
|---|---|
| Existing (has structure) | Structure placed at `cellOrigin`. Runner block at `cellOrigin + (bounds.size.x + 1, 0, 0)` bound to spec id. |
| New (no structure yet) | Recorder block at `cellOrigin`. No structure. (Runner appears after first finalize.) |

**Bounds rendering.** No physical marker blocks. The spec's bounds AABB is rendered client-side as a wireframe overlay when the player is near, reusing the existing recorder bounds overlay. Keeps the cell clean of metadata blocks that would muddle save scans.

**Cell-corner label.** A text-display entity at `cellOrigin + (0, max(bounds.size.y, 1) + 1, 0)` showing the filename. Sits outside the spec's bounds AABB (the `+ 1` ensures separation from the bounds top face), so excluded from save scans automatically. For a new spec with empty bounds (`size = (0,0,0)`), the `max(..., 1)` fallback keeps the label visible above the recorder block.

**Auto-bind in managed dims.** Recorder/runner/editor block entities placed inside a managed dim consult `ManagedDimRegistry`'s `Map<BlockPos, spec-id>` and auto-bind to the cell's spec on load (instead of needing manual binding).

## 9. Network Protocol

All payloads in `network/managed/`. Server-authoritative; clients propose. Same pattern as existing `Packets.kt`.

**C2S:**

- `ListManagedTree(rootId)` — request a fresh tree snapshot.
- `LoadManagedFolder(subpath)` — load a leaf folder, unloading the current one if any.
- `UnloadManagedFolder()` — explicit unload (returns player to overworld).
- `SaveNow()` — save dirty cells in the current loaded folder.
- `NewManagedSpec(name)` — create a stub `.spec.kts` and reload the folder so the new cell appears.

**S2C:**

- `ManagedTreeSnapshot(rootId, tree)` — serialized `ManagedFolderTree`.
- `ManagedFolderLoaded(dimKey, layout, errors)` — load result + per-spec layout + per-file load errors.
- `ManagedSaveReport(perSpec)` — save result per spec id.
- `ManagedError(reason)` — generic error toast.

## 10. Entry Points

### Singleplayer (client)

- A `ManagedRootListScreen` button is injected into `SelectWorldScreen` via mixin.
- The screen lists known managed roots (stored in `config/redstonespecs/managed-roots.json`) with add/remove. Picking one boots an integrated server pinned to that root and with a stub overworld.
- The save dir for this server is a fixed throwaway path (e.g. `<.minecraft>/redstonespecs-managed-session/<root-hash>/`); vanilla world data is discarded on close (the folder of specs is the only thing the user cares about persisting).
- On join, the player is teleported immediately into `ManagedScreen` (folder browser) with the overworld backgrounded.

### Dedicated server

- Server config: `managedRoot=<absolute path>`, `enableManaged=true`. No world-selection UI involved.
- Connected clients open `ManagedScreen` via `/redstonespecs managed` or a key bind.
- Same authority model.

## 11. Error Handling Summary

| Failure | Behavior |
|---|---|
| `.spec.kts` parse failure | Spec excluded from layout; reason surfaced in `ManagedScreen`. Other specs load. |
| Bounds exceed `cellSize` | Spec excluded; GUI shows required `cellSize`. |
| Path traversal (subpath escapes root) | `ManagedError`; load aborts. |
| Filesystem write failure | Spec stays dirty; `ManagedSaveReport` flags; original file untouched. |
| Sanitization collision (two subpaths → same dim id) | Second load fails with `ManagedError("dim id collision")`. |
| Folder vanished between scan and load | `ManagedError("folder missing")`. |

## 12. Testing

**Unit (`src/test/kotlin/.../managed/`):**

- `GridLayoutTest` — sort order, packing, row wrap, cell-origin math, spec-too-big exclusion.
- `PathSanitizationTest` — char replacement, collision detection.
- `PathTraversalGuardTest` — `..`, absolute, symlink-resolved subpaths all rejected unless `startsWith(root)`.
- `ManagedFolderTreeTest` — scan against a `tmp` dir with leaf + intermediate folders.

**Gametest (`src/gametest/kotlin/.../managed/`):**

- `ManagedDimLoadTest` — boot, register a transient root with two synthetic `.spec.kts` files, load the dim, assert structures placed at expected cell origins and runner blocks present.
- `ManagedSaveBackTest` — modify blocks inside one spec's bounds and outside the other's bounds; trigger save; assert exactly the first file is rewritten and the second is byte-identical to before.
- `ManagedNewSpecTest` — `NewManagedSpec("foo")` creates `<folder>/foo.spec.kts`, reloads, recorder block at next free cell, no runner.

**Client gametest (`src/clientTest/kotlin/.../managed/`):**

- `ManagedRootListScreenTest` — button injected on `SelectWorldScreen`; opens `ManagedRootListScreen`; add-root + open boots integrated server and lands on `ManagedScreen`.
- `ManagedScreenLoadTest` — pick a folder, assert client teleported into the managed dim and at least one cell-corner label entity is rendered.

## 13. File Layout

```
src/main/kotlin/com/breadmoirai/redstonespecs/managed/
  ManagedRoot.kt
  ManagedFolderTree.kt
  GridLayout.kt
  ManagedCell.kt
  ManagedDimRegistry.kt
  ManagedDimLifecycle.kt
  ManagedCellSaver.kt
  ManagedVoidChunkGenerator.kt
  ManagedDimensionType.kt
src/main/kotlin/com/breadmoirai/redstonespecs/network/managed/
  ManagedPackets.kt
src/client/kotlin/com/breadmoirai/redstonespecs/managed/screen/
  ManagedScreen.kt
  ManagedRootListScreen.kt
  ManagedFolderTreeWidget.kt
src/client/kotlin/com/breadmoirai/redstonespecs/mixin/
  SelectWorldScreenMixin.kt   (injects the "Managed Specs..." button)
```

Existing files extended (small touches): `SharedSettings.kt` (new fields), `Redstonespecs.kt` (registry wiring), `SpecBlockEntity.kt` (auto-bind in managed dim), `RecordingFinalizer.kt` (write-back-to-source path).

## 14. Open Questions (resolve during planning)

1. **Eager runner placement after finalize**: place runner the moment finalize succeeds, or wait until next reload? Either acceptable; pick the simpler one once we read finalize internals.
2. **Cell-corner label kind**: text-display entity (1.19.4+) vs. sign block — choose based on supported MC versions in this Stonecutter project.
3. **Stub overworld for singleplayer entry**: cheapest way to skip world generation cost on a throwaway integrated server (flat preset? a 1×1×1 spawn chunk?). To be confirmed against MC integrated-server boot path.
