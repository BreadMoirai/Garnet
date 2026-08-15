# Local History Panel: Browsing and Restoring Structure Revisions

**Date:** 2026-08-15
**Status:** Design approved, pending implementation

## Problem

`LocalHistoryStore` has banked a revision of every structure on every commit since the autosave
feature landed — `placed`, `autosave`, `manual`, `external`, and (since the undo feature)
`pre-delete`. Up to 100 revisions per structure, pruned by age and count, sitting on disk with a
complete read API: `revisions(file)`, `readTag`, `readRawBytes`.

None of it is reachable from the UI.

The only user-facing rollback is the Explorer's Undo/Redo toolbar, which reverses *file operations*
— delete, rename, move, create — and restores deleted files from their `pre-delete` revisions. It
cannot move a structure's *contents* back to an earlier state. So rolling back an edit today means
either catching it inside the undo stack's reach (only if the change was a file op, in this session)
or hand-copying a blob out of `<instance>/.garnet/local-history/<stem>-<hash8>/` with an NBT tool.

There is also a trap waiting for whoever builds this. Revisions are **POST-commit** snapshots: each
one records what the `.nbt` *became*, not what it was before. The newest revision is byte-identical
to what is on disk right now, so "undo the last edit" is `revisions[size - 2]`, not
`revisions.last()`. A naive list-and-restore gets this exactly wrong and silently does nothing.

## Scope

A **Local History** panel, tabbed alongside the Project Explorer in the dock's LEFT region, showing
the revisions of the currently-placed structure, with a Restore action that moves the world and the
`.nbt` back to a chosen revision — itself undoable.

**In scope:**

- A second LEFT-region panel and the tab strip needed to reach it.
- Right-click → *Local History* on a `.nbt` node: places the structure if it is not already placed,
  then switches to the tab.
- A revision list: timestamp, dimensions, block count, reason. Newest first. The newest row is
  marked current and is not restorable.
- Restore: re-place the revision into the editor world, then commit, banking the restore as a new
  revision.
- Undo/redo of a restore, through the existing `EditorUndoStack`.
- Server-pushed refresh, so the list never goes stale while it is open.

**Out of scope, deliberately:**

- **Undeleting a file that no longer exists.** History outlives the file it describes, so the
  revisions of a deleted structure are still on disk — but reaching them means enumerating orphan
  history directories and mapping opaque hashes back to paths, which is a feature of its own.
- **Diffing two revisions.** Useful, unrelated to recovery, and considerably more work than the
  whole of this design.
- **Restoring a `.spec.kts`.** Raw (`garnetRaw`) revisions are banked for every file type by the
  delete path, but this panel is structure-only.
- **A manual "take a snapshot" action.** A `manual` revision is already just a forced commit; there
  is no separate snapshot concept and this design does not add one.

## The invariant

**The History panel only ever shows a structure that is currently placed in the editor world.**

Right-click → *Local History* places the structure first if needed, then switches to the tab.
Nothing else can point the panel at a file.

This is the load-bearing decision of the design. It removes the branch that would otherwise dominate
it — restoring into a file nobody has open, with no world copy to reconcile against — so restore
always has a placed footprint to clear and a `StructureCommit` path to write through. Every
"not placed" case collapses into a single refusal rather than a second code path.

Two consequences to pin down:

- The context-menu item is **enabled only on `.nbt` file nodes**, disabled on folders, the project
  root, and `.spec.kts` files — those have no structure to place.
- With no structure placed (fresh session, or the placed one was deleted), the panel shows a
  "no structure open" state and sends `WatchStructureHistoryC2S("")`. It never shows a list for
  something that is not in the world.

## Architecture

### New client files (`client/.../editor/ui/`)

- **`LocalHistoryPanel.kt`** — the panel body: revision list, Restore button, empty and
  history-disabled states.
- **`LocalHistoryState.kt`** — panel-scoped snapshot state: the subpath being shown and its
  revisions. `remember`-ed in the panel, never a top-level object, for the reason documented on
  `ExplorerMenuState` and `ExplorerDialogState`: the dock composes into a long-lived singleton scene,
  so panel state held globally survives a re-mount and paints over the next one. See
  `DockState.mountEpoch`.
- **`OpenStructureState.kt`** — the client's notion of "the placed structure", which does not exist
  today. `ProjectTreeState.onStructureResult` currently keeps only a status string. This records the
  subpath from `StructureResultS2C` and clears it on disconnect.

### Modified client

- **`GarnetDock.kt`** — `RegionColumn` renders only `panels[active]`, and the tab strip was
  deliberately deleted once it was clear only one panel per region was ever registered. Two panels in
  LEFT brings it back: a compact strip above the panel body plus a writer for `leftActiveTab` (the
  old `setActiveTab` went with the strip). The existing `key(mountEpoch, panels[active].id)` guard
  already makes swapping which panel occupies the slot a fresh composition, so tab switching inherits
  that protection.
- **`ExplorerContextMenu.kt`** — one new item, flat. Not a submenu: Jewel opens a flyout as a second
  focusable popup layer, and this scene's `isInteractive(owner)` check stops routing pointer events
  to every layer below the focused one, so nested menus are broken here.
- **`GarnetClient.onInitializeClient`** — seeds the second LEFT panel beside the Explorer.

### New server files (`main/.../editor/history/`)

- **`HistoryWatchers.kt`** — a `Map<UUID, String>` of which subpath each player has open, cleared on
  disconnect. The entire server-side footprint of the push model.
- **`StructureRestoreOps.kt`** — the restore sequence, kept out of the packet handler so it is
  testable without a network round trip, mirroring how `EditorUndoOps` sits behind
  `EditorFileOpsHandlers`.

### Modified server

- **`EditorPackets.kt`** + **`EditorNetworkRegistry.kt`** — three payloads and their registrations.
- **`EditorStructureHandlers.kt`** — the watch and restore handlers, delegating to
  `StructureRestoreOps`.
- **`EditorUndoCommand.kt`** — a `RestoreRevision` case.
- **`StructureCommit.kt`** — one fan-out call where it already broadcasts `StructureAutoSavedS2C`.
- **`LocalHistoryStore.kt`** — a `REASON_RESTORE = "restore"` constant.
- **`StructurePersistence.kt`** — extract the tag-taking core of `placeStructureCentered` (see
  "Required refactor" below).

## Wire protocol

Three payloads, following the existing `EditorPackets.kt` conventions (`CustomPacketPayload.Type` +
`StreamCodec.composite`, registered in `EditorNetworkRegistry.register`).

### `WatchStructureHistoryC2S(subpath: String)`

"I am looking at this structure's history; send it now and keep me posted." An **empty subpath means
"no longer looking"**, sent when the tab deactivates or the dock hides.

One packet doing both halves rather than a watch/unwatch pair: the server's state is a single entry
per player, so a set-or-clear write matches it exactly, and there is no ordering hazard where an
unwatch for the old subpath races a watch for the new one.

### `StructureHistoryS2C(subpath: String, revisions: List<RevisionEntry>)`

The reply and the push, same payload for both.

`RevisionEntry` is `Revision` minus its `file` field: `timestampMillis`, `sizeX`, `sizeY`, `sizeZ`,
`blockCount`, `reason`. The blob filename stays server-side — it is a filesystem detail, and a client
that selects by timestamp rather than by filename cannot name an arbitrary path.

Sent **oldest-first**, as `LocalHistoryStore.revisions` returns it. The panel reverses for display.
Reversing in the panel rather than on the wire keeps the payload in the store's own order, so a
future consumer does not inherit a presentation decision.

### `RestoreRevisionC2S(subpath: String, timestampMillis: Long)`

Identifies the revision by **timestamp, not list index**. An index is only meaningful against the
list the client happened to be holding, and an autosave landing between render and click would
silently shift it — restoring the revision next to the one that was clicked, with nothing to detect
it. The timestamp is stable, and the server refuses an unknown one rather than guessing at the
nearest.

### Refresh

The server pushes `StructureHistoryS2C` to every watcher of a subpath at the one place
`StructureCommit` already fans out `StructureAutoSavedS2C` (`StructureCommit.broadcast`), and again
at the end of a restore. Watchers are dropped on disconnect, alongside the existing per-player editor
state teardown, so a rejoining player never inherits a stale watch.

## Restore sequence

`StructureRestoreOps.restore(server, player, subpath, timestampMillis)`:

1. **Resolve and validate** — root, `resolveSubpath`, `.nbt` extension, and
   `placedBoxOf(subpath) != null`. Not placed is a hard refusal, not a fallback to writing the file
   directly: the invariant is what keeps this path single.
2. **Find the revision by timestamp**, refusing an unknown one. Also refuse the newest — it is the
   row the panel renders inert, and this is the server-side half of that rule rather than trusting
   the client to enforce it.
3. **Reject a raw revision.** `readRawBytes` returning non-null means the blob is a `garnetRaw`
   wrapper — a `pre-delete` bank of something that was not a parseable structure. Detection is by
   that marker, **never by size**: a real `.nbt` that parses but is not a structure template records
   zero sizes too, so size cannot tell the two apart.
4. **Quiesce, aborting if it fails.** Commit whatever is still only in the world, with
   `REASON_AUTOSAVE`, so the state about to be replaced is banked. This deliberately diverges from
   `deleteSubtree`, whose quiesce is best-effort: a delete is a request to destroy that content
   anyway, whereas here a failed quiesce followed by a re-place would silently eat live edits nobody
   asked to lose. Abort with a message and touch nothing.
5. **Re-place.** Clear the *old* `placedBox` footprint, place the revision's tag at the same region
   origin, then `setPlacedBox` to the new box. Old-then-new ordering matters: a restored structure
   may be smaller than what it replaces, and the new box is the only thing bounding the scan in step
   6 — clearing by the new box would strand the old footprint's leftover blocks in the world, where
   the very next commit would capture them back.
   **No teleport**, unlike `placeStructureFrom`: the player is already standing in the region, and
   being flung to the new roof height mid-restore is disorienting. That difference is why restore
   does not simply call `placeStructureFrom`.
6. **Commit with `REASON_RESTORE`.** `StructureCommit` scans the region, sees content differing from
   disk, banks it, and writes the `.nbt` — so the restore appears in history as a first-class entry
   with a real `blockCount`, and `StructureCommit` remains the sole writer of the file.
   Note the interaction with external-edit detection: step 4's commit left a fresh fingerprint, so
   `diskIsNewestRevision` holds and no spurious `external` revision is banked in between.
7. **Push the refreshed list** to every watcher of that subpath, and `sendUndoState`.

### Required refactor

`StructurePersistence.placeStructureCentered` takes a `Path` and reads it internally. Step 5 holds a
`CompoundTag`, not a file. Extract the body into a tag-taking function; leave the existing
`Path` overload as a read-then-delegate wrapper so every current caller is untouched.

The alternative — spooling the revision to a temp file just to read it back — adds an IO round trip
and a failure mode for nothing.

## Undo

`EditorUndoCommand.RestoreRevision(subpath, fromTimestampMillis, toTimestampMillis)`, pushed onto the
existing `EditorUndoStack`.

Undo is **the same operation aimed at `from`**: the pre-restore state is itself a banked revision
(step 4 guaranteed it), so reversing a restore is just restoring the other one. Redo aims at `to`.
No new inverse machinery, and no content carried on the command.

Replay-time preconditions follow `EditorUndoOps`' existing style: if the structure is no longer
placed, or the target revision has been pruned away, refuse with a message rather than half-applying.

## Presentation

Rendered under `IntUiTheme(isDark = true)`, as the Explorer panel is.

**Color and weight, not glyphs.** Jewel's default family is Inter, which has no emoji coverage, so
an emoji falls through to whatever Skia finds on the host — a system emoji font on Windows,
plausibly nothing on a bare Linux box. Tofu that only appears on someone else's machine is not an
acceptable status indicator.

- Timestamp and dimensions: normal foreground.
- Reason: a plain lowercase word (`autosave`, `manual`, `placed`, `external`, `restore`) in a muted
  foreground.
- The current (newest) row: the theme's **disabled** foreground, with no marker character, and
  genuinely non-interactive — no hover response, no click handler. This is not color-only signalling:
  the row does not react because it is not an action.

Colors come from the Jewel theme rather than being hardcoded. The two hardcoded panel backgrounds in
this codebase are an exception, not a pattern to copy.

**Empty states are distinguished.** History disabled (`localHistoryEnabled = false`) shows an
explanatory message, not a bare empty list — an empty list reads as "this structure has no history",
which is a different and wrong claim.

## Failure modes

Every refusal is a message via `EditorHandlerSupport.fail`, never a silent no-op. It lands on the
Explorer status line, which is where the player is looking.

| Condition | Response |
|---|---|
| Structure not placed at restore time | Refuse: "place the structure before restoring" |
| Timestamp not in the index (pruned between render and click) | Refuse **and** push the refreshed list, so the panel corrects itself |
| Target is the newest revision | Refuse — the client should not have offered it |
| Blob missing or unreadable (`readTag` null) | Refuse. The index entry stays: deleting it would destroy a record the user may still want to see |
| Raw (`garnetRaw`) blob | Refuse: "not a structure snapshot" |
| Quiesce commit fails | Abort before touching the world (step 4) |
| `.nbt` write fails inside `StructureCommit` | Already handled there — the speculative revision is discarded and the world keeps the restored content, so the restore is recoverable by retrying |
| History disabled | Panel shows the explanatory empty state |
| A watcher's structure is deleted or renamed | The push finds no file and sends an empty list; the panel falls back to its no-structure state |

## Testing

Following existing homes rather than creating new ones.

- **`LocalHistoryStoreSpec`** — the `restore` reason round-trips like any other reason.
- **New `StructureRestoreSpec`** (`src/gametest/.../editor/`) — the sequence end-to-end against a
  real world:
  - restore an older revision; assert the world blocks **and** the `.nbt` both match it;
  - assert a `restore` revision was banked, with a non-zero `blockCount`;
  - assert no spurious `external` revision appeared;
  - restore a **smaller** revision and assert the old footprint's leftover blocks are gone (the
    step-5 ordering trap);
  - assert the not-placed, unknown-timestamp, newest-revision, and raw-blob refusals;
  - assert a failed quiesce aborts without mutating the world.
- **`EditorUndoNetworkSpec`** — undo of a restore returns to the pre-restore content, redo
  re-applies, and a pruned target refuses instead of half-applying.
- **`EditorNetworkRegistrySpec`** — the three new payload registrations, matching how the existing
  ones are covered.
- **`JewelExplorerSpec`** — a pixel-probe case for the tab strip. Pixel probing is the established
  technique for this dock because every state flag reads clean while a stale composition is still
  painting.
- **Plain client unit tests** for the panel's list model: newest-first ordering, the current row
  being non-interactive, and the history-disabled empty state.

## Docs to update on implementation

- `docs/persistence/local-history.md` — add `restore` to the reason table and to "The writers";
  the "Rollback implication" note can then point at the panel as the supported route.
- `docs/persistence/editor-undo-stack.md` — the `RestoreRevision` command.
- `docs/ui/dock-framework.md` — the tab strip's return, and the note that only one panel per region
  is ever registered is no longer true.
- `docs/ui/explorer-toolbar-and-context-menu.md` — the new context-menu item.
- A new `docs/ui/local-history-panel.md`, registered in `docs/ui/INDEX.md`.
- `docs/use-cases/structure-lifecycle.md` — a coverage-matrix row for restore.
