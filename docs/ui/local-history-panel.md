---
title: The Local History panel
tags: [screens, widgets, history, dock, restore, explorer]
summary: The revision list reached via the LEFT stripe's second icon — why it only ever shows a placed structure, why the newest revision is shown but inert, why a revision is addressed by timestamp rather than list index, how the server pushes refreshes to watchers, and the ordering rules the restore sequence cannot be reordered without losing data.
---

# The Local History panel

`LocalHistoryPanel.kt` (`src/client/kotlin/.../editor/history/ui/`) is the second LEFT-region dock panel,
reached via the [dock stripe](dock-stripe.md)'s second icon — sharing the LEFT region with the Project
Explorer and the [Structure Info panel](structure-info-panel.md), not tabbed beside them. It lists a
structure's banked revisions and offers **Restore**, which moves both the world copy and the `.nbt`
back to a chosen revision — undoably.

The store behind it is documented in [persistence/local-history.md](../persistence/local-history.md);
the undo entry a restore pushes is documented in
[persistence/editor-undo-stack.md](../persistence/editor-undo-stack.md). This article is about the
decisions that are *not* visible in either — the ones that make the feature one code path instead of
several, and the orderings that silently lose edits if reversed.

## The invariant: the panel only ever shows a **placed** structure

The only way to point the panel at anything is right-click → *Local History* on a `.nbt` node, which
places the structure first if it is not already placed
(`ExplorerActions.openLocalHistory` sends `PlaceStructureC2S` unless
`OpenStructureState.subpath` already names it) and then calls `DockState.showPanel("garnet.localHistory")`,
switching LEFT to it. Nothing else can set the panel's subject.

**This is the load-bearing decision of the whole feature, and it is a decision about the *server*,
not the UI.** It removes the branch that would otherwise dominate `StructureRestoreOps`: restoring
into a file nobody has open, with no world copy to reconcile against. Because the invariant holds:

- there is *always* an old footprint to clear before placing the revision, and
- there is *always* a `StructureCommit` path to write the `.nbt` through, so nothing in the restore
  path writes a `.nbt` directly — `StructureCommit` remains the **sole writer** of any `.nbt`, the
  same rule the autosave and manual-save paths obey.

Every "not placed" situation therefore collapses into a single refusal
(`"place the structure before restoring"`), instead of becoming a second implementation of restore
that would have to grow its own history banking, its own external-edit detection, and its own
failure modes.

Two consequences follow directly:

- The context-menu item is enabled **only on `.nbt` file nodes** — folders, the project root and
  `.spec.kts` files have nothing to place. See
  [explorer-toolbar-and-context-menu.md](explorer-toolbar-and-context-menu.md#local-history).
- With nothing placed (a fresh session, or the placed structure was deleted), the panel shows a
  *"no structure open"* state and sends `WatchStructureHistoryC2S("")`. It never lists revisions for
  something that is not in the world.

`OpenStructureState` exists solely to make the client side of this checkable. `StructureInfoState`,
the other receiver of a `StructureResultS2C`, records only the *status message* plus the structure's
facts — nothing there is a stable "is this subpath currently placed" flag the panel can key off.
`OpenStructureState.onStructureResult` records just the subpath, which is what the panel actually
needs. All three senders of that payload refer to a structure that is placed (place,
a no-change save, and a completed restore), so tracking it there is sound. It is reset on
disconnect — a placed structure does not survive a world.

## Revisions are POST-commit, so the newest one is inert

Every revision records what the `.nbt` **became**, not what it was before. The newest revision is
therefore byte-identical to what is on disk right now. The trap this sets: *"undo the last edit"* is
`revisions[size - 2]`, **not** `revisions.last()`. A naive list-and-restore aims at the newest entry
and silently does nothing.

The panel renders newest-first and keeps that top row visible, but **inert**:

- theme-disabled foreground, and
- **no click handler at all** — `RevisionRow` only attaches its `pointerInput` when
  `LocalHistoryState.isRestorable(...)`.

Showing it and disabling it beats hiding it: a timeline with a hole at the top invites the reader to
conclude their most recent save was never banked. And this is deliberately not colour-only
signalling — the row does not respond because it is not an action.

The client is not trusted with the rule. `StructureRestoreOps` refuses the newest timestamp
independently, so a stale or hand-built client cannot ask for a no-op restore that would nonetheless
run a quiesce, a clear, a re-place and a commit.

### No glyphs, anywhere in this panel

Jewel's default family is Inter, which has no emoji coverage, so a marker glyph falls through to
whatever Skia finds on the host — a system emoji font on Windows, plausibly nothing on a bare Linux
box. Tofu that only appears on someone else's machine is not an acceptable status indicator. Status
is carried by colour and by whether a row reacts. For the same reason the dimensions read
`12x4x9` with an ASCII `x`, not `×`.

## A revision is addressed by **timestamp**, never by list index

`RestoreRevisionC2S(subpath, timestampMillis)`. A list index is only meaningful against the exact
list the client happened to be holding: an autosave landing between render and click shifts every
index by one, and the restore would then hit the revision *next to* the one that was clicked — with
nothing anywhere able to detect that it happened. A timestamp is stable, and the server refuses an
unknown one rather than guessing at the nearest.

The blob filename never leaves the server, for the same reason: it is a filesystem detail, and a
client that can only name a timestamp cannot name an arbitrary path.

Two details fall out of that choice:

- `LocalHistoryStore` disambiguates same-millisecond writes in the blob *filename* only, so two
  revisions can share a timestamp. `StructureRestoreOps` resolves with `lastOrNull { … }`, picking
  the newest of such a group — which is what a caller holding `revisions().last()` means by that
  timestamp, and what makes the newest-revision refusal exact.
- `LocalHistoryState.onHistory` drops a selection the incoming list no longer contains. A revision
  can be pruned between two pushes, and a Restore aimed at a gone timestamp would only earn a server
  refusal.

## The restore sequence, and why its order cannot be shuffled

`StructureRestoreOps.restore(server, subpath, timestampMillis): RestoreOutcome` — deliberately
player-independent (it returns an outcome rather than sending packets) so the packet handler and the
undo/redo replay each phrase their own message, and so it is testable without a network round trip.
This mirrors how `EditorUndoOps` sits behind `EditorFileOpsHandlers`.

Three of its steps are ordering rules, not style:

**1. Quiesce, and ABORT if it fails.** The pending in-world edits are committed (as `autosave`)
before anything is touched, so the state about to be replaced is itself banked. This **deliberately
diverges from `deleteSubtree`, whose quiesce is best-effort**: a delete is a request to destroy that
content anyway, whereas here a failed quiesce followed by the re-place below would silently eat live
edits nobody asked to lose. (See
[persistence/editor-undo-stack.md](../persistence/editor-undo-stack.md#the-pre-delete-quiesce-lives-in-deletesubtree-not-in-a-caller)
for the delete side of that contrast.)

The revision list is re-read **after** the quiesce, because that commit may have banked a revision,
and it is the one an undo of this restore has to aim back at — hence
`RestoreOutcome.Restored.fromTimestampMillis` rather than "whatever the client thought was newest".

**2. Clear the OLD footprint, then place, then `setPlacedBox` to the NEW box.** A restored structure
may be *smaller* than the one it replaces, and the new box is the only thing bounding the commit's
scan in step 3. Clearing by the new box would strand the old footprint's leftover blocks in the
world — where the very next commit would capture them straight back in, quietly undoing the restore.
`StructureRestoreSpec` pins this with a restore-a-smaller-revision case.

**3. Commit with `REASON_RESTORE`.** `StructureCommit` scans the region, sees content differing from
disk, banks it and writes the `.nbt`. So the restore appears in history as a first-class entry with
a **real `blockCount`** (it was scanned out of the world, unlike `placed`/`external`/`pre-delete`),
and the sole-writer rule holds. Step 1's commit also left a fresh fingerprint, so
`diskIsNewestRevision` holds and no spurious `external` revision is banked in between — also pinned
by a spec case.

**No teleport.** `placeStructureFrom` teleports the player to the placed structure; restore does
not. The player is already standing in the region, and being flung to the new roof height mid-restore
is disorienting. **That single difference is why restore cannot simply call `placeStructureFrom`**,
and it is the reason `StructurePersistence.placeStructureCentered(file, …)` was split: the tag-taking
core is now `placeStructureTagCentered(nbt, …)`, and the `Path` overload is a read-then-delegate
wrapper so every existing caller is untouched. Restore holds a `CompoundTag` read out of a revision
blob, not a file; spooling it to a temp file just to read it back would have added an IO round trip
and a failure mode for nothing.

### Raw revisions are detected by their MARKER, never by size

A `garnetRaw` blob is a `pre-delete` bank of something that was not a parseable structure. Restore
refuses it, and detects it via `LocalHistoryStore.readRawBytes(...) != null`. **Size cannot be used
here**: a real `.nbt` that parses but is not a structure template records zero sizes too, so a
size check would conflate the two and NBT-write a wrapper tag as if it were a template. See
[persistence/local-history.md](../persistence/local-history.md#raw-revisions-banking-a-file-that-is-not-a-structure).

### Failure modes

Every refusal is a message in the [Structure Info panel](structure-info-panel.md)'s status line
(`StructureInfoState.onError`), never a silent no-op. A refusal also
**pushes the refreshed list back**, because the likeliest cause is a revision pruned between render
and click, and a fresh list is what corrects the panel. A refusal pushes no undo entry — nothing
happened.

The one asymmetric case is a `.nbt` write failing *inside* `StructureCommit`: the world holds the
restored content and disk does not, the speculative revision is discarded, and retrying is the
recovery.

## The watcher push model

`HistoryWatchers` is a `Map<UUID, String>` — one watched subpath per player — plus the fan-out that
keeps those panels current. That is the entire server-side footprint.

- **One payload, not a watch/unwatch pair.** `WatchStructureHistoryC2S(subpath)` with an **empty
  subpath means "no longer looking"**. The server's state is a single entry per player, so a
  set-or-clear write matches it exactly, and there is no ordering hazard where an unwatch for the
  old subpath races a watch for the new one.
- **Pushes happen where `StructureCommit` already fans out `StructureAutoSavedS2C`**, and again at
  the end of a restore — a restore banks a revision, so *every* watcher's list is short by one, not
  just the restoring player's.
- **Unsolicited sends are `canSend`-guarded.** On a dedicated server, an unknown play-phase payload
  can get a vanilla/unmodded client **disconnected**. `pushTo` carries the guard even though the
  reply-to-a-C2S path would not need one; sharing one helper is simpler than maintaining two send
  paths, and the guard is a no-op for a client that provably speaks the channel. The client's own
  `LaunchedEffect` send is guarded for a different reason: a bare send with no receiver throws, and
  thrown out of a `LaunchedEffect` it would take the whole composition down.
- **An unresolvable root or subpath pushes an EMPTY list rather than nothing.** The panel has to
  hear that the structure it was watching is gone (deleted, renamed, root repointed); silence would
  leave a stale list on screen indefinitely.
- Watches are dropped on disconnect, alongside the existing per-player editor-state teardown, so a
  rejoining player never inherits a stale watch.

## Two rendering states that must stay distinguished

`LocalHistoryState` holds revisions **newest-first** — the reverse of the wire order, which is the
store's own oldest-first order. Reversing in the panel rather than on the wire keeps the payload
free of a presentation decision a future consumer would inherit.

- **History disabled** (`localHistoryEnabled = false`) shows an explanatory message, *not* an empty
  list. An empty list reads as "this structure has no history", which is a different and wrong claim.
- **The list is only rendered when `LocalHistoryState.subpath == OpenStructureState.subpath`.**
  Between placing a structure and its history push arriving, the state still holds the *previous*
  structure's revisions, and rendering those under this structure's name would offer a Restore that
  silently targets the wrong build.

## Panel state and the dock's mount lifecycle

`LocalHistoryState` and `OpenStructureState` are top-level objects because they are fed by *network
receivers*, which have no composition to live in. Anything genuinely panel-local must be
`remember`-ed inside the panel body instead: the dock composes into a long-lived singleton scene, so
panel state parked in a global survives a re-mount and paints over the next one. See
[dock-framework.md](dock-framework.md#panel-composition-must-not-outlive-its-mount) and
`DockState.mountEpoch`.

Reaching the panel needs the dock's icon stripe — see [dock-stripe.md](dock-stripe.md).

## Test coverage

`StructureRestoreSpec` (`src/gametest/.../editor/`) covers the sequence end-to-end against a real
world: the `placeStructureTagCentered`/`placeStructureCentered` equivalence, a restore landing in
both the world and the `.nbt`, the banked `restore` revision with a non-zero `blockCount`, the
absence of a spurious `external` revision, the smaller-revision footprint trap, every refusal
(not placed, unknown timestamp, newest revision, raw blob, missing file), an aborting quiesce, and
the watch/push handlers including the `canSend` drop and the empty-subpath unwatch.
`EditorUndoNetworkSpec` covers undo/redo of a restore; `EditorNetworkRegistrySpec` covers the three
payload registrations; plain client unit tests cover `LocalHistoryState`'s list model.

## See also

- [persistence/local-history.md](../persistence/local-history.md) — the revision store this panel reads.
- [persistence/editor-undo-stack.md](../persistence/editor-undo-stack.md) — `RestoreRevision` and why it carries no content.
- [explorer-toolbar-and-context-menu.md](explorer-toolbar-and-context-menu.md) — the context-menu entry point.
- [dock-framework.md](dock-framework.md) — the region model.
- [dock-stripe.md](dock-stripe.md) — the icon stripe and per-panel visibility model.
- [structure-info-panel.md](structure-info-panel.md) — the sibling LEFT panel with the same no-glyph
  rule.
