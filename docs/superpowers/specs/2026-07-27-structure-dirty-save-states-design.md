# Dirty save states for standalone structures — design

**Date:** 2026-07-27
**Status:** Approved for planning

## Goal

Give standalone `.nbt` structures an IDE-style **unsaved-changes buffer**. When a user edits a
placed structure and Minecraft saves the world (autosave, `/save-all`, or shutdown), the
structure's current edited state is automatically captured to the **filesystem** as "unsaved
changes" — **without** overwriting the committed `.nbt`. On re-opening (placing) that structure
later, the user resumes from their unsaved edits, and can **discard** them to fall back to the
last explicitly-saved version.

This closes the gap where structure edits live only as blocks in the *ephemeral* overworld
region: `ProjectDimRegistry` assigns regions by in-memory placement order, so after a reload a
structure can be re-assigned a different (empty) region, orphaning the previous edits. Capturing
edits to disk on world-save makes the edit survive reload independent of region assignment.

Non-goals: unsaved buffers for spec-folder structures (this covers standalone `.nbt` only);
a conflict/merge UI beyond continue-or-discard; renaming/deleting `.nbt` from the tree;
persisting the region→file mapping.

## Core model: the dirty sidecar

Unsaved edits are captured to a **dirty sidecar file adjacent to the structure file**:

```
<name>.nbt            ← committed structure (touched only by explicit Save Structure)
<name>.nbt.unsaved    ← recovery buffer; its existence IS the dirty flag
```

- **Adjacent to the actual file**, per the requirement — same directory, derived name.
- Suffix is **`.nbt.unsaved`**, chosen so `subpath.endsWith(".nbt")` is **false**: the sidecar can
  never be mistaken for a placeable structure, and it is filtered out of the Explorer tree
  (see Client). The committed `.nbt` is never modified except by explicit Save.
- The sidecar lives on disk, so the dirty state survives world reload and server restart.

## Trigger: `ServerLifecycleEvents.BEFORE_SAVE`

Fabric fires `BEFORE_SAVE` on **every** world save — periodic autosave, `/save-all`, and the
shutdown save. Signature: `onBeforeSave(server, flush, force)`. Registered in
`Redstonespecs.kt` alongside the existing `SERVER_STARTING` / `SERVER_STARTED` /
`SERVER_STOPPED` hooks. No mixin on the save path is needed.

On each fire, for **every structure currently placed this session**
(`ProjectDimRegistry.placedBoxes` keys):

1. **Capture** the structure's region via the auto-fit scan (the same scan
   `StructurePersistence.saveAutoFitToFile` already performs), producing a `StructureTemplate`
   tag — factored so it can capture *to a tag* without writing the committed file.
2. **Diff** the captured tag against the committed `<name>.nbt` (pure helper, below).
3. **Differs** → write `<name>.nbt.unsaved` (compressed NBT).
   **Identical** → delete `<name>.nbt.unsaved` if it exists (structure was edited back to the
   saved state → clean again).

Runs on the server thread. Cost is one region scan per placed structure — identical to what an
explicit Save already does, bounded by how many structures are placed this session. The existing
"a later optimization can skip empty chunk sections" caveat carries forward.

### What "placed this session" means

`placedBoxes` is in-memory and ephemeral. That is exactly the set we want: only structures the
user has actually opened this session can have live edits in the world to capture. Structures
never placed this session keep whatever sidecar they already had on disk, untouched.

## Edit-lifecycle operations

### Place — `handlePlaceStructure` (modify)

Before loading, check for `<name>.nbt.unsaved`:

- **Sidecar exists** → load and place **the sidecar** (the unsaved version).
- **No sidecar** → load the committed `<name>.nbt`, as today.

Everything else (region assignment, prior-box clear, centered placement, teleport, placed-box
record) is unchanged. The `StructureResultS2C` reply gains `hasUnsaved: Boolean` reflecting
whether a sidecar was loaded.

### Save Structure — `handleSaveStructure` (modify)

Capture the region and write the committed `<name>.nbt` (unchanged behavior), then **delete
`<name>.nbt.unsaved`** — the committed file now reflects the edits, so the buffer is clean.
Reply with `hasUnsaved = false`.

### Discard — `handleDiscardStructure` + `DiscardStructureC2S` (new)

1. Delete `<name>.nbt.unsaved`.
2. Re-place from the committed `<name>.nbt`: clear the prior placed box, place centered, record
   the new placed box, teleport — so the live world reverts to the committed state.
3. Reply with `hasUnsaved = false`.

If no committed file content applies (empty committed structure) the re-place is an empty place,
consistent with the existing place path.

## Pure diff helper (`src/test`-covered)

```
fun structuresDiffer(committed: CompoundTag, captured: CompoundTag): Boolean
```

Normalizes each structure tag to a `Set<Pair<relativePos, blockStateString>>` by resolving each
block entry's palette index to its state, then compares the two sets (and the size vector).
Normalizing — rather than the raw `CompoundTag` equality the existing
`StructurePersistence.hasChanges` uses — makes the result robust to palette-ordering differences
between two otherwise-identical structures. Pure: constructed from hand-built tags, no live level.

A missing committed file counts as "differs" (any placed content is unsaved).

## Client

- **`StructureResultS2C`** gains `hasUnsaved: Boolean`. `ProjectTreeState` surfaces it as a
  status line (e.g. `placed sub/foo.nbt — unsaved changes`) matching the existing
  `onFolderLoaded` / `onSaveReport` status pattern, and enables a **Discard** action when the
  selected `.nbt` has unsaved changes. Discard sends `DiscardStructureC2S(selectedPath)`.
- **Dirty dot in the Explorer:** `FileNode` gains `hasUnsaved: Boolean`, set during `scanFolder`
  when a sibling `<name>.nbt.unsaved` exists. This gives an IDE-style dirty indicator on the
  `.nbt` node *before* it is placed, driven purely by the filesystem so it is correct across
  reloads. The `.nbt.unsaved` file itself is **filtered out** of the scanned tree so it never
  appears as its own node.
- `DiscardStructureC2S` registered in `ProjectNetworkRegistry.register()` with the other project
  packets; `StructureResultS2C`'s widened stream codec updated on both ends.

## Packets

- `DiscardStructureC2S(subpath: String)` — new C2S.
- `StructureResultS2C(subpath, sizeX, sizeY, sizeZ, hasUnsaved: Boolean, message: String)` —
  extend the existing packet with `hasUnsaved`.
- Failures continue to reuse `ProjectErrorS2C`.

## Testing

- **Unit (`src/test`):**
  - `structuresDiffer`: identical-but-palette-reordered → not different; a changed block state →
    different; a changed auto-fit size → different; missing committed → different.
  - `scanFolder`: hides `<name>.nbt.unsaved` from the tree and sets `hasUnsaved = true` on the
    sibling `.nbt` node when a sidecar exists (false otherwise).
- **Gametest:**
  - place → edit region → `BEFORE_SAVE` writes `<name>.nbt.unsaved`; committed `.nbt` unchanged.
  - edit region back to the committed content → `BEFORE_SAVE` deletes the sidecar.
  - place when a sidecar exists → the unsaved version is placed (not committed); reply
    `hasUnsaved = true`.
  - Save Structure → writes committed `.nbt`, deletes sidecar, `hasUnsaved = false`.
  - Discard → deletes sidecar, live region matches committed, `hasUnsaved = false`.
- **Client spec:** `hasUnsaved` in `StructureResultS2C` drives the status line / Discard
  affordance; invoking Discard sends `DiscardStructureC2S`.

## Known cost / follow-ups

- `BEFORE_SAVE` capture scans each placed structure's region (auto-fit) — same cost as a manual
  Save, bounded by placed-structure count. Skipping empty chunk sections is a later optimization.
- Blocks left in the ephemeral overworld region after reload (region assignment is not persisted)
  are **pre-existing** and unchanged by this feature; the sidecar makes them irrelevant because
  re-place reads structure content from disk.
- No unsaved buffers for spec-folder-attached structures, and no conflict/merge UI beyond
  continue-or-discard — explicit non-goals for this iteration.
