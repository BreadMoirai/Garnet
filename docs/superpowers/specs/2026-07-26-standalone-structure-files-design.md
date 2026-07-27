# Standalone structure files in the Explorer — design

**Date:** 2026-07-26
**Status:** Approved for planning

## Goal

Make Minecraft `.nbt` structure files **first-class, directly manipulable entities in the
project Explorer**, independent of any spec. Today `.nbt` files exist only as spec sidecars
(`spec.structure ?: spec.id` → `<id>.nbt`), placed and saved automatically as part of a spec
folder's lifecycle; in the Explorer they render as inert leaves. This feature lets the user
**place, capture (save), and create** a standalone structure straight from the file tree.

Non-goals: renaming/deleting/moving `.nbt` files from the tree; a structure "asset editor" UI;
changing how spec-attached structures are placed/saved; multi-structure-per-region.

## User-facing operations

Three operations, all driven from the Explorer:

1. **Place** — click a `.nbt` file in the tree. The server places it into the workspace
   overworld in its own auto-assigned region and teleports the player there.
2. **Save (capture)** — a "Save Structure" action operating on the **currently selected**
   `.nbt`. The server scans that structure's region for non-air blocks, computes the tight
   axis-aligned bounding box (auto-fit), and writes exactly that box into the file.
3. **New** — a "New Structure" action prompts for a name and writes an empty `.nbt` into the
   active folder. The new file appears in the tree; the user places it, builds inside its
   region, then Saves. Mirrors the (server-ready) New Spec flow.

## Region model

- **Size:** a new global config field `SharedSettings.structureRegionChunks: Int = 9` →
  the region is `structureRegionChunks * 16` blocks square (144×144 by default), spanning the
  **full world height** (`level.minBuildHeight() .. maxBuildHeight()`).
- **Assignment:** regions are handed out by `ProjectDimRegistry`, keyed by the structure's
  **subpath** (e.g. `sub/foo.nbt`). Structure subpaths are file paths and never collide with
  the folder subpaths spec placement uses, so structure regions and spec-folder regions
  coexist in the same map.
- **No overlap:** `ProjectDimRegistry.computeRegionOrigin` currently sizes each +X slot to the
  spec grid width. Widen it to `max(spec-grid-width, structure-region-width)` so a structure
  region and a spec region assigned to adjacent slots never overlap.
- **Determinism:** assignment is deterministic per subpath for the life of the server (the
  existing counter-based, in-memory, ephemeral scheme). Re-placing the same structure reuses
  the same region.

### Placement anchor

- **X/Z:** the structure's saved bounds are centered in the region.
- **Y (default):** the structure's **floor** sits at sea level, `SharedSettings.projectGridYBase`
  (64) — a plane to build on, consistent with spec cells.
- **Y (tall structures):** if the structure's own height (the Y-extent of its saved bounds) is
  **≥ 256**, floor-at-64 would collide with the build ceiling, so the structure is **centered
  vertically** in the region instead.

Re-placement is a fresh clear + place. Because auto-fit re-centers the captured box on each
save, an asymmetric edit can shift the build's origin on the *next* place; this is acceptable
for standalone structures (placement only happens on explicit click).

## Server components

### `StructurePersistence` (extend)

Add:

```
fun saveAutoFitToFile(
    file: Path,
    level: ServerLevel,
    regionOrigin: BlockPos,
    regionBounds: Vec3i,
): Vec3i?   // captured tight bounds, or null/empty-structure when the region has no non-air
```

Scans `regionOrigin .. regionOrigin+regionBounds` for non-air, computes the tight AABB,
`template.fillFromWorld(level, tightOrigin, tightBounds, ...)`, and `NbtIo.writeCompressed`s to
`file` (the exact resolved path in the tree — **not** the `saveDir/<id>.nbt` convention, since
a standalone structure lives at an arbitrary subpath). An empty region writes a valid empty
structure. The AABB computation is factored into a **pure** helper (predicate over a volume →
`BoundingBox?`) so it is unit-testable without a live level.

### Placement-state tracking

A small server-attached `Map<subpath, PlacedBox(origin, bounds)>` records the last-placed
tight box per structure. On re-place, only that box is cleared (bounded — thousands of blocks),
not the full ~8M-block region. `handlePlaceStructure` seeds it; `handleSaveStructure` updates
it to the freshly captured box.

### Handlers (`ProjectNetworkRegistry`)

Three new handlers, modeled on `handleLoadFolder` / `handleSaveNow` / `handleNewSpec`, all
going through `root.resolveSubpath` (path-traversal guard) and emitting `ProjectErrorS2C` on
failure:

- `handlePlaceStructure(subpath)` — resolve file (must exist, `.nbt`); get/assign region; clear
  the previously-placed box; load + place centered per the anchor rules; record placed box;
  teleport player (reuse `ProjectTeleport`). Reply with `StructureResultS2C`.
- `handleSaveStructure(subpath)` — resolve file; get/assign region; `saveAutoFitToFile`; update
  placed box; reply with `StructureResultS2C(subpath, capturedBounds, message)`.
- `handleNewStructure(name)` — resolve active folder (like New Spec); write an empty `.nbt`;
  re-send the tree snapshot so the new file appears.

## Packets (`network/project`)

- `PlaceStructureC2S(subpath: String)`
- `SaveStructureC2S(subpath: String)`
- `NewStructureC2S(name: String)`
- `StructureResultS2C(subpath: String, capturedBounds: Vec3i?, message: String)` — success/status
  feedback for place and save. Failures reuse existing `ProjectErrorS2C`.

All registered in `ProjectNetworkRegistry.register()` alongside the existing project packets.

## Client

- `ProjectExplorerPanel`: a `FileNode` whose `extension == "nbt"` becomes **clickable to place**
  (sends `PlaceStructureC2S(path)`), with a distinct affordance/icon from an inert file. Files
  are still selectable (selection drives Save Structure).
- Add **"New Structure"** and **"Save Structure"** actions. "Save Structure" acts on
  `ProjectTreeState.selectedPath` when it is a `.nbt` (disabled/no-op otherwise). "New
  Structure" prompts for a name and sends `NewStructureC2S`.
- `ProjectTreeState`: handle `StructureResultS2C` → surface a status line (e.g.
  `placed sub/foo.nbt` / `saved sub/foo.nbt (12×8×15)`), matching the existing
  `onFolderLoaded` / `onSaveReport` status pattern. Register the S2C receiver on the client.

## Testing

- **Unit (`src/test`):** the pure auto-fit AABB helper — empty volume → null/empty; single
  block → 1×1×1 at that block; scattered blocks → tight enclosing box; and the Y-anchor
  decision (floor-at-64 vs vertical-center at the ≥256 threshold) as a pure function of bounds
  + region.
- **Gametest:** place → build → save → re-place round-trip (captured box matches the built
  blocks; re-place clears the prior box and re-centers); empty-region save writes an empty
  structure; region-assignment does not overlap an adjacent spec folder's region.
- **Client spec:** `.nbt`-click sends `PlaceStructureC2S`; non-`.nbt` file click does not;
  `StructureResultS2C` updates `ProjectTreeState.status`.

## Known cost / follow-ups

- Auto-fit save scans the full region volume (144×144×full-height) read-only. Fine for a
  manual, infrequent save; a later optimization can skip empty chunk sections.
- Renaming/deleting `.nbt` from the tree, and per-structure region-size overrides, are explicit
  non-goals for this iteration.
