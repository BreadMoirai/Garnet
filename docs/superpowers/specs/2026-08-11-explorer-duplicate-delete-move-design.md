# Duplicate, Delete, and Move in the Project Explorer

**Date:** 2026-08-11
**Status:** Design approved, pending implementation

## Problem

The Project Explorer can create and rename, and nothing else. `EditorFileOpsHandlers` exposes
`handleCreateFolder` and `handleRename`; `ExplorerContextMenu` offers New Folder, New Structure, and
Rename. There is no way to duplicate a structure, delete anything, or move a node between folders
without leaving the editor and touching the project directory by hand.

The obstacle was never the file IO. It is that `handleRename` encodes six invariants learned the
hard way, and each new operation needs a different subset of them:

1. Commit every dirty structure at or under the path **before** the move, and abort if that fails —
   dirty state is keyed by subpath, so relocating first strands edits under a key nothing resolves
   again.
2. Do the fallible IO first, destructive world teardown second — so a failed operation leaves the
   world intact.
3. Drop the registry entry **before** `clearBounds` writes AIR, or the setBlock mixin re-attributes
   those writes to the old subpath and immediately re-dirties what was just committed.
4. Carry `LocalHistoryStore` revisions across for every moved `.nbt`, not just the named node.
5. Rekey every `EditorDimRegistry` entry nested under the path.
6. Repoint the active session when it sat at or under the path.

Getting the subset wrong is how edits get stranded silently. This spec adds the three missing
operations and factors the shared part out rather than copying it three ways.

## Scope

Three operations, server and client, with test coverage at the depth rename set:

- **Duplicate** — in place, on the clicked node. No clipboard, no cross-folder targeting.
- **Delete** — recursive, behind a confirmation dialog, with history retained.
- **Move** — via a "Move to…" item opening an in-project target-folder dialog.

Out of scope: drag-and-drop in the tree (considered and rejected as the largest single piece of work
in the spec, and a poor fit for click-driven clientTests); a copy/paste clipboard; a trash folder.

## Design

### 1. Shared quiesce helper

Rename's commit-and-abort loop (`EditorFileOpsHandlers.handleRename`, the `dirtyUnderRename` block)
moves to `EditorHandlerSupport`:

```kotlin
/** Null when every dirty structure at or under [subpath] is committed; else the reason to abort. */
fun commitDirtyUnder(server: MinecraftServer, subpath: String): String?
```

It commits every dirty subpath equal to `subpath` or prefixed by `"$subpath/"`, returning a reason
on `CommitOutcome.Failed`, and also on a `CommitOutcome.NotApplicable` whose dirty flag survived the
attempt — that case leaves an entry keyed to a path about to become invalid, which strands the edit
exactly like an outright failure.

`handleRename` switches to the helper. Three call sites justify the extraction, and the extracted
loop is the piece whose subtleties produced four separate review findings.

### 2. Packets

Three new C2S payloads in `EditorPackets`, registered in `EditorNetworkRegistry` and dispatched to
`EditorFileOpsHandlers` through `ctx.server().execute`:

| Payload | Fields |
|---|---|
| `DuplicatePathC2S` | `subpath` |
| `DeletePathC2S` | `subpath` |
| `MovePathC2S` | `subpath`, `destFolderSubpath` |

No new S2C. Failure replies with the existing `EditorErrorS2C`; success resends the tree via
`sendTree`, the same shape every current file op uses.

`DuplicatePathC2S` deliberately carries no name. Only the server sees the real filesystem, so it
derives the deduplicated name itself rather than trusting a client that may hold a stale snapshot.

### 3. Duplicate

```
reject empty subpath (the project root)
commitDirtyUnder(subpath)          -> abort with EditorErrorS2C on failure
derive deduped name
copy file, or recursively copy subtree for a folder
sendTree
```

Quiescing first is what makes the copy reflect the structure as it stands in the world rather than a
stale `.nbt` — duplicating a structure mid-edit and getting yesterday's blocks would be a silent
wrong answer, so a failed commit aborts the duplicate rather than producing one.

Name derivation goes in `EditorNames`:

```kotlin
/** `house.nbt` -> `house copy.nbt` -> `house copy 2.nbt`; folders have no extension to preserve. */
fun duplicateName(sourceName: String, siblings: Collection<String>): String
```

The extension is preserved (the suffix goes before `.nbt`, not after it), and the counter starts at 2
on the second collision. Folders take the same path with an empty extension.

**The duplicate starts with empty history.** `LocalHistoryStore` keys revisions by absolute path, so
nothing is inherited automatically, and nothing should be: cloning revisions would claim an edit
history the copy never had, and would double blob storage on every duplicate.

Duplicate touches no registry state, marks nothing dirty, and does not place the copy in the world.
Nothing is keyed to a path that did not exist a moment ago, which is what makes this the simplest of
the three operations.

### 4. Delete

```
reject empty subpath (the project root)
commitDirtyUnder(subpath)          -> log and PROCEED on failure (see below)
delete file, or recursively delete subtree     <- fallible IO first
for each placed structure at or under subpath:
    registry.unplaceStructure(sub)             <- registry key dropped BEFORE
    StructurePersistence.clearBounds(...)      <- ...AIR is written
clear StructureAutoSave entries for the whole subtree
clear the active session if it sat at or under subpath
sendTree
```

**The leftover dirty entry is the real hazard, not the unlink.** A subpath left in
`StructureAutoSave` after its file is gone makes `StructureCommit.tick` retry on every tick forever,
either failing repeatedly or recreating the file the user just deleted. Clearing the subtree's dirty
state is therefore not cleanup — it is the correctness step.

Handlers and `StructureCommit.tick` both run on the server thread, so the whole handler is atomic
against ticks. The window between unlink and the dirty-state clear cannot be observed, which is what
makes this ordering safe to reason about at all.

IO comes before teardown, matching rename: if the unlink fails, nothing has been unplaced and the
world is untouched, so the error the player sees is the whole story.

`unplaceStructure` before `clearBounds` is invariant 3 above, unchanged from rename: `clearBounds`
writes AIR through the 3-arg `level.setBlock`, which the mixin hooks unconditionally, so a still-
registered subpath would be re-marked dirty by the very writes that are erasing it.

**Delete deliberately diverges from rename on commit failure.** It quiesces best-effort, because that
is what banks a final recovery revision into the history being kept — but a *failed* commit is logged
and the delete proceeds. Blocking a delete because history could not be banked for a node the user is
explicitly destroying would make a broken structure undeletable from the editor. This gets an inline
comment and a dedicated gametest so it is not "corrected" into rename's abort later.

**History is retained.** Deleting a `.nbt` leaves its `LocalHistoryStore` directory in place, which is
the recovery route. The consequence to document: a new file later created at the same path inherits
those revisions. That is a defensible undelete affordance rather than a bug, but it is surprising
enough to belong in the docs.

Session handling needs a small `EditorSession` addition — `repointSession` rewrites an active subpath
onto a new one, but delete has no new one. A sibling that clears the active subpath to `null` when it
equals or is nested under the deleted path.

### 5. Move

`handleRename` and `handleMove` collapse onto a shared core:

```kotlin
private fun relocate(
    server: MinecraftServer, player: ServerPlayer,
    oldSubpath: String, source: Path, target: Path, newSubpath: String,
)
```

containing, unchanged from today's rename: quiesce → `moveTo` → `moveDescendantHistories` →
unplace/`clearBounds`/replace → `rekeyForRename` → `repointSession` → `sendTree`. Every one of those
steps is already written in `oldSubpath → newSubpath` terms and generalizes to a parent change
without modification.

Each caller computes `target` and `newSubpath` its own way and applies its own validation. Rename's is
unchanged. Move's is new:

- the destination resolves and is a folder;
- the destination is not the source itself, nor any descendant of it (`redstone/` into
  `redstone/sub/` would either throw or nest a folder inside itself, depending on the filesystem);
- the destination is not already the node's current parent (a no-op);
- the node's name does not collide with an existing child of the destination.

The descendant check is the one genuinely new invariant in this spec — rename never had a way to
express it — and it is the case most worth a gametest.

### 6. Client

`ExplorerContextMenu` gains **Duplicate**, **Delete**, and **Move to…**, each targeting the clicked
node and each disabled on the project root. The menu stays one level deep: Jewel's `submenu { }`
opens a second focusable popup layer, and the dock's `CanvasLayersComposeScene` blocks pointer input
to every layer below the focused one, so nested menus are broken by construction here. The reasoning
is already recorded in `ExplorerContextMenu`'s KDoc and `docs/ui/jewel-widget-layer.md`.

Both dialogs render through **one panel-scoped dialog host** with two contents. The context menu
closes before either opens, so each is a single popup layer and sidesteps the nesting defect:

- **Delete confirmation** — names the node and, for a folder, the count of `.nbt` files anywhere
  beneath it, read from `ProjectTreeState.snapshot` ("Delete 'redstone/' and the 3 structures inside
  it?"). Structures rather than all nodes: intermediate folders are not what the player is afraid of
  losing. A file, or an empty folder, is named without a count. Cancel sends nothing.
- **Move target picker** — a folders-only view of the snapshot, with the source's own subtree and its
  current parent disabled.

Dialog state is `remember`-ed in the panel, never a top-level object — the same constraint
`ExplorerMenuState` documents: the dock composes into a long-lived singleton scene, so global popup
state survives a panel re-mount and repaints over the next one.

`ExplorerActions` gains `commitDuplicate`, `commitDelete`, and `commitMove`, each returning null on
success or the reason nothing was sent, and each sending through the existing `sender` seam so
clientTests can assert on payloads without a live connection. As with the current actions, client
validation is a pre-check that keeps a dialog open with an inline error instead of closing and
surfacing an `EditorErrorS2C` a round-trip later; the server re-validates against the real filesystem
and remains authoritative.

Duplicate fires immediately with no dialog.

## Testing

**`EditorFileOpsNetworkSpec`** (Kotest, existing temp-root + mock-player harness):

*Duplicate* — dedupes the name, and again on a second duplicate; captures a dirty placed structure's
in-world edits rather than stale disk content; aborts when that commit fails; copies a folder subtree;
leaves the registry untouched and places nothing; leaves the source's history intact and gives the
copy none; refuses the project root.

*Delete* — unplaces a placed structure and clears its blocks; clears dirty state so a subsequent
`StructureCommit.tick` neither recreates the file nor retries; removes a folder subtree; retains
history; proceeds despite a failed commit; leaves the world and registry intact when the unlink
fails; clears an active session at or under the deleted path; refuses the project root.

*Move* — relocates the file, rekeys nested registry entries, moves descendant histories, and repoints
the session; rejects a destination inside the source's own subtree; rejects a name collision in the
destination; rejects a file as a destination; treats the current parent as a no-op; aborts on commit
failure leaving everything intact.

**`ExplorerUiSpec`** — the three menu items render and are disabled on the root; delete's dialog
appears, cancel sends nothing, confirm sends `DeletePathC2S`; the move picker disables the source
subtree and current parent, and sends `MovePathC2S` on selection; duplicate sends `DuplicatePathC2S`
with no dialog.

## Documentation

Per `CLAUDE.md`, after implementation: extend UC-MAN-10 in `docs/use-cases/structure-lifecycle.md`
with the three operations, note the retained-history/path-reuse consequence in
`docs/persistence/local-history.md`, and register any new article in its category `INDEX.md`.
