# Structure auto-save with local history

**Date:** 2026-07-31
**Status:** design approved, not yet implemented

## Context

This is sub-project 1 of three, decomposed from a request for a structure info panel:

1. **This spec** — standalone `.nbt` structures auto-save directly to disk; the `.nbt.unsaved`
   sidecar model is removed; a JetBrains-style local-history store provides the safety net that
   the sidecar used to provide.
2. **Local-history browser UI** — a revision list with diff and revert, reading the store this
   spec writes.
3. **Structure info panel** — a details pane below the Explorer showing the open structure's
   size, block count, entity count, and last-saved time.

Auto-save and the history store are specced together because auto-save without a safety net means
an accidental edit is committed with no way back. Spec 2 adds the UI for browsing what spec 1
already records.

## Scope

Standalone `.nbt` structures only — the ones placed into their own region by
`EditorNetworking.handlePlaceStructure`. Spec-folder cells (`.spec.kts` + structure, saved through
`EditorCellSaver` / `SaveNowC2S`) keep their existing explicit-save path untouched.

## 1. The sidecar model is removed

The `<name>.nbt.unsaved` dirty buffer disappears entirely.

Deleted:

- `StructurePersistence.unsavedSidecarOf`, `StructurePersistence.flushUnsavedSidecar`
- `FileNode.hasUnsaved`, its `boolean` in `FILE_TREE_STREAM_CODEC`, its detection in `scanFolder`
  (the `"${entry.name}.unsaved" in names` check and the `.nbt.unsaved` filter), and the `●` marker
  it drives in `ProjectExplorerPanel.TreeRow`. The `●` survives only for `snapshot.currentSubpath`.
- `StructureResultS2C.hasUnsaved` and its codec field
- `DiscardStructureC2S` and `EditorNetworking.handleDiscardStructure` — "revert to the committed
  file" has no meaning once every edit *is* the committed file. Revert becomes a local-history
  rollback in spec 2.
- `EditorNetworking.flushDirtyStructures` — replaced by the commit path in §3.

Changed:

- `SaveStructureC2S` / `handleSaveStructure` is re-purposed as **force-commit now**: it flushes the
  pending debounce for that subpath rather than doing its own region capture. It keeps its existing
  guard that the structure must be placed.
- `handlePlaceStructure` no longer chooses between the sidecar and the committed file; it always
  places from the file.
- `handleRename` no longer moves a sidecar. It does gain a local-history directory move (§4).

## 2. Edit tracking: a dirty box, not a region scan

A structure region is `SharedSettings.structureRegionChunks * 16` wide (144 by default) and spans
the full world height, so `StructurePersistence.captureAutoFit` reads roughly 8M block positions.
That is acceptable once per world-save. It is not acceptable on an edit debounce.

New `StructureAutoSave`, one instance per `MinecraftServer` (same `WeakHashMap` + `@Synchronized`
pattern as `EditorDimRegistry`). Per placed subpath it holds:

- `dirtyBox: BoundingBox?` — the union of every edited position observed since the last commit
- `firstEditTick: Long` — when the current dirty span began
- `lastEditTick: Long` — the most recent edit

`ServerLevelSetBlockMixin`'s `RETURN` inject gains a second consumer,
`StructureEditWatcher.onBlockChanged(level, pos)`. It must be invoked **before** the
`SKIP_SENTINEL` early-return: today the `HEAD` hook pushes the sentinel and bails whenever no
`StateRecorder` is interested in the position, and the watcher needs no before-state — only the
position and the fact that the write succeeded (`cir.getReturnValue()`). Guard on
`instanceof ServerLevel` as the existing code does.

`StructureEditWatcher` maps a changed position to the placed structure whose region contains it: an
O(number of placed structures) test against each region origin and the region width, on the
structure lane. A miss is the overwhelmingly common case and costs a few comparisons.

Commit capture then scans `union(placedBox, dirtyBox)` through a new
`StructurePersistence.captureAutoFitIn(level, box)` — the structure's own extent plus wherever the
player actually touched, rather than the whole region. The invariant that makes this correct: the
region starts empty, and every block in it arrived either through `placeStructureCentered` (inside
`placedBox`) or through an observed `setBlock` (inside `dirtyBox`).

## 3. When a commit fires

`Garnet.onInitialize` registers `ServerTickEvents.END_SERVER_TICK` — alongside the existing
`BEFORE_SAVE` listener — driving `StructureAutoSave.tick(server)`. Note that `mc/McLifecycle.kt`
registers the same event but belongs to the testing module and has no `fabric.mod.json` entrypoint,
so it cannot be relied on here.

A dirty structure commits when either:

- `now - lastEditTick >= autoSaveDebounceTicks` — the quiet-period debounce, so a burst of
  placements is one write; or
- `now - firstEditTick >= autoSaveMaxDirtyTicks` — the cap, so a long uninterrupted build session
  still checkpoints. After a capped commit the span re-arms from the current tick.

Backstop commits, each flushing any pending dirty state synchronously: world-save (`BEFORE_SAVE`),
`SaveStructureC2S`, unplace and rename, and server stop.

A commit:

1. captures `union(placedBox, dirtyBox)` via `captureAutoFitIn`
2. compares the captured tag to the on-disk `.nbt`; **identical content is a complete no-op** — no
   revision, no write, dirty state cleared
3. writes the **newly captured** content as a local-history revision with reason `autosave` (§4).
   Revisions record states as they are committed, not the state being replaced; the pre-edit state
   is already on record as the previous revision, or as the `placed` baseline for the first commit.
4. rewrites the `.nbt` and updates `EditorDimRegistry.setPlacedBox` with the new tight box
5. clears the dirty state and broadcasts `StructureAutoSavedS2C` (§6)

When `autoSaveEnabled` is false, the tick watcher still tracks dirtiness but never commits on its
own; `SaveStructureC2S` and the backstops still work.

## 4. Local history store

New `com.breadmoirai.garnet.history.LocalHistoryStore`.

```
<instance>/.garnet/local-history/<stem>-<hash8>/<epochMillis>-<seq>.nbt
<instance>/.garnet/local-history/<stem>-<hash8>/index.json
```

`<instance>` is `FabricLoader.getInstance().gameDir`, overridable by `localHistoryDir`. `<seq>` is a
per-millisecond disambiguator, `0` unless two revisions land in the same millisecond; sorting by
`(epochMillis, seq)` gives chronological order without consulting the index.

**Keying.** `hash8` is a short hash of the structure file's *own* normalized absolute path — not of
the project root. This is deliberate: the editor's root is swappable via "Open Folder…", and a file
must resolve to the same history whether you opened its folder or an ancestor as the project root.
Normalization lowercases the path on Windows, so the same file reached through two casings cannot
fork into two histories. `<stem>` is the filename, included purely so the directory is browsable by
hand; the hash is what identifies it.

**Index.** `index.json` holds the absolute path the directory was keyed from — for debugging, and to
detect the (astronomically unlikely) hash collision — plus one entry per revision recording its
filename, `timestampMillis`, size (`x`/`y`/`z`), block count, and reason (`placed`, `autosave`, or
`manual`).

**Baseline.** `handlePlaceStructure` seeds a `placed` revision from the on-disk file, so the
pre-edit state is always recoverable even if the first edit lands seconds later.

**Pruning** runs on write: delete revisions older than `localHistoryDays`, and any beyond
`localHistoryMaxRevisions`, whichever bites first.

**Rename.** `handleRename` moves the history directory to the new path's hash, since the absolute
path changes. **Deletion** of a structure deliberately leaves its history behind — recovering a
deleted structure is exactly what the store is for.

When `localHistoryEnabled` is false, commits still happen; no revisions are written.

## 5. Config

Every tunable lives in `SharedSettings` and round-trips through `ModConfig` / `config/garnet.json`,
which today persists only `projectRootPath` while every other field is a code default.

| Setting | Default | Meaning |
|---|---|---|
| `autoSaveEnabled` | `true` | Off = manual `SaveStructureC2S` and backstops only |
| `autoSaveDebounceTicks` | `20` | Quiet ticks after the last edit before committing |
| `autoSaveMaxDirtyTicks` | `600` | Cap forcing a checkpoint during continuous editing |
| `localHistoryEnabled` | `true` | Off = commits happen, no revisions written |
| `localHistoryDays` | `5` | Age cutoff (matches JetBrains' default) |
| `localHistoryMaxRevisions` | `100` | Per-structure depth cap |
| `localHistoryDir` | `""` | Blank = `<instance>/.garnet/local-history` |

The existing unpersisted fields — `structureRegionChunks`, `projectCellSize`, `projectCellGap`,
`projectRowMax`, `projectGridYBase` — are persisted at the same time, so `garnet.json` becomes the
whole settings surface rather than a one-key file.

**Known limitation, inherited from `projectRootPath`:** `ModConfig` lives in `src/client`, so this
config only reaches an integrated server. A dedicated server runs the compiled defaults. Fixing that
is out of scope here.

## 6. Client feedback

New `StructureAutoSavedS2C(subpath, sizeX, sizeY, sizeZ, blockCount, savedAtMillis)`, broadcast to
all players on every commit.

In this spec it feeds only `ProjectTreeState.status` — e.g. `auto-saved clock.nbt (5×3×7)`. It is
deliberately shaped as the packet **spec 3's info panel will consume directly**; that is the seam
between the two sub-projects.

## 7. Testing

New gametest specs, registered explicitly in `GametestSentinel` (autoscan is off, so an
unregistered spec silently does not run):

- **`StructureAutoSaveSpec`** — debounce commits after quiet ticks; the max-dirty cap fires
  mid-burst and re-arms; a clean structure never writes; `SaveStructureC2S` force-commits
  immediately; `autoSaveEnabled = false` suppresses the tick commit but not the backstops; the
  captured box equals `union(placedBox, dirtyBox)` and not the region.
  Tests drive `StructureEditWatcher.onBlockChanged` directly rather than going through
  `level.setBlock`, because the `setBlock` mixin is unreliable under the gametest harness.
- **`LocalHistoryStoreSpec`** — revision written on commit; `placed` baseline seeded; age pruning;
  count pruning; `index.json` round-trip; rename moves the directory; two different roots containing
  the same absolute file resolve to one history; Windows casing does not fork the history.
  Mostly filesystem work, needs no world.

Deleted: `StructureSidecarPersistenceSpec`.

Rewritten against the commit path: `EditorStructureNetworkSpec` and `EditorFileOpsNetworkSpec`, both
of which assert sidecar behavior today (`flushDirtyStructures`, sidecar-survives-rename).

## 8. Documentation

New article `docs/persistence/local-history.md`, registered in `docs/persistence/INDEX.md`.

Sidecar references to update: `docs/architecture/redstone-project.md`,
`docs/architecture/module-map.md`, `docs/persistence/spec-on-disk-format.md`,
`docs/persistence/INDEX.md`, `docs/ui/explorer-toolbar-and-context-menu.md`,
`docs/use-cases/persistence.md`, `docs/use-cases/redstone-project.md`,
`docs/use-cases/structure-lifecycle.md`, and the affected `INDEX.md` summaries.

`docs/superpowers/` is left alone — those are commit-time snapshots, not living docs.
