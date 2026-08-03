---
title: Explorer toolbar and context menu
tags: [explorer, toolbar, context-menu, inline-edit, jewel, packets]
summary: The Project Explorer's kebab/Refresh/Collapse-All toolbar, its right-click New/Rename menu, and the inline name field that commits through ExplorerActions.
---

# Explorer toolbar and context menu

`ExplorerToolbar.kt` (the panel's single top row: kebab overflow, Refresh, Collapse All) and
`ExplorerContextMenu.kt` (the right-click `New Folder` / `New Structure` / `Rename` menu) together
replaced the old root-name `Dropdown` header and the `+ New`/`Save`/`Discard` structure-action row.
See [dock-framework.md](dock-framework.md#first-real-panel-the-project-explorer-live-data-pattern-now-on-jewel)
for the panel walkthrough and [jewel-widget-layer.md](jewel-widget-layer.md) for the Jewel
mechanics (`PopupMenu` overloads, the NUL-suffixed placeholder id, the `BasicLazyTree` prune). This
article covers the *why* behind five decisions that aren't visible just from reading the code.

## Why validation runs on both client and server

`ExplorerActions.commitCreate`/`commitRename` re-run `EditorNames.validate` against the client's
own tree snapshot before sending `CreateFolderC2S`/`NewStructureC2S`/`RenamePathC2S`. This looks
redundant next to `EditorFileOpsHandlers.handleCreateFolder`/`EditorStructureHandlers.handleNewStructure`/`EditorFileOpsHandlers.handleRename`,
which validate the *same* name again server-side. It isn't: the two checks run against different
data with different trust levels.

The client's tree snapshot is a point-in-time copy — it can be stale the moment another player (or
a prior action this session) renames, deletes, or creates a sibling, and `ExplorerActions.siblingsOf`
degrades to "no known siblings" whenever the snapshot doesn't resolve the parent folder at all (no
snapshot loaded, the path resolves to a file, or the folder was since renamed/removed server-side).
That's a legitimate return value, not a bug, precisely *because* the client check is a pre-check,
not the source of truth. Its only job is to let the inline field stay open and show an error
immediately, instead of closing, sending a doomed packet, and surfacing a `EditorErrorS2C` a
network round-trip later. The server re-validates against the real directory listing and is the
only check that can actually refuse a write — the client check exists purely for latency, not
correctness.

## Why the pending-create placeholder id embeds a NUL

`Creating(parentPath, kind)` (part of `ExplorerEdit`) has no existing tree row to swap into an
inline field — the item doesn't exist on disk yet. `ExplorerTreeState.buildTreeFrom` handles this
by synthesizing a placeholder leaf and giving it the id `ExplorerEdit.pendingIdFor(parentPath)`,
which is literally `parentPath` + `/` + a NUL character + `"new"` (written here as the escape
`\0`, never as a raw byte in source or docs).

Jewel's `TreeState` keys *all* selection and expansion off these id strings — they're the same
`/`-joined relative-path space the server uses for `currentSubpath` and `FolderNode.walk()`/
`resolve()`. A NUL is illegal in a filename on every filesystem this mod supports, so appending one
guarantees the placeholder's id can never collide with a real path, no matter how the user names
things. Without that guarantee, a folder someone deliberately or accidentally named to match the
placeholder's shape could let the placeholder silently inherit or corrupt a real node's selection
or expansion state — a bug that would only show up as intermittent, hard-to-repro tree state
corruption, not a crash. `ExplorerEdit.isPendingId(id)` is the inverse check `TreeRow` uses to
detect the placeholder and render the field in its place instead of a label.

## Why `ExplorerMenuState` must be panel-scoped, not a top-level object

`ExplorerMenuState` (`target`, `anchor`) is `remember`-ed inside `ProjectExplorer()`, never a
top-level `object` the way `ProjectTreeState`/`ExplorerTreeState` are. A popup layer belongs to the
composition that opened it — the dock composes into a **long-lived singleton scene** that survives
across panel hide/show and world-session boundaries (see
[dock-framework.md#panel-composition-must-not-outlive-its-mount](dock-framework.md#panel-composition-must-not-outlive-its-mount)
for `DockState.mountEpoch`, the general version of this guard). If `ExplorerMenuState` were a
top-level singleton instead, a menu opened before a panel re-mount (e.g. a world disconnect/
reconnect, or the panel being hidden and re-shown) would still hold a non-null `target`, and the
*next* mount of the panel would immediately repaint that stale menu over itself — a popup with no
current right-click behind it, anchored at a coordinate from a previous session. Scoping the state
to the composable that owns it means a re-mount gets a fresh `ExplorerMenuState` with `target =
null`, for free, instead of needing an explicit reset call site to remember.

## Why renaming a placed structure unloads and reloads it — and why renaming a folder rekeys instead

`EditorDimRegistry` tracks placed structures in three maps keyed by subpath: `bySubpath` (a
loaded folder's own region), `structureBySubpath` (a standalone structure's assigned region), and
`placedBoxes` (the last-placed footprint, used for cheap re-clearing). All three are keyed by
subpath, so a rename that only moves the file on disk without touching the registry strands every
entry under the OLD subpath: the structure's placed blocks become unreachable by the new name
(`StructureCommit.commit` resolves the subpath via `EditorRootResolver.rootFor(server).resolveSubpath(subpath) ?:
return null` and silently skips it forever), and a fresh `PlaceStructureC2S(newSubpath)` finds no
registry entry and re-places a second copy in a brand-new region, orphaning the first in the world.

`handleRename` handles two distinct shapes of this problem differently:

- **The renamed node itself is a placed structure** (`registry.placedBoxOf(payload.subpath) !=
  null`): an unload/reload, not a rekey. `handleRename` first commits any pending auto-save edits
  for the OLD subpath through `StructureCommit.commit` — BEFORE the file move, since the dirty
  state is keyed by subpath and moving first would strand it under a name nothing will ever commit
  again — then calls `EditorDimRegistry.unplaceStructure(oldSubpath)` (clearing
  `structureBySubpath` and `placedBoxes`), then re-places it under the new subpath via
  `EditorStructureHandlers.placeStructureFrom`. This lands the structure in a freshly-assigned region
  (`nextStructureIndex` is monotonic and never recycled) rather than reusing the old one —
  intended, matching how every other region assignment in the registry behaves.
- **Descendants of a renamed folder** (a structure or sub-folder nested *under* the renamed path,
  not the renamed path itself): `EditorDimRegistry.rekeyForRename(oldSubpath, newSubpath)`
  rewrites every entry across all three maps whose subpath is `oldSubpath` or begins with
  `"$oldSubpath/"` — the same path-segment boundary `EditorSession.repointSession` uses (a bare `startsWith`
  would wrongly also rekey an unrelated sibling like `redstoneworks/clocks` when renaming
  `redstone`). This is a pure in-memory bookkeeping move: it does **not** touch the world. The
  structure's blocks stay exactly where they were placed — only the file's path changed, not its
  position — so registry state and world state still agree once it returns. `handleRename` calls
  it after the file move and after the "renamed node itself" handling above, so by the time it
  runs, that case's exact-match key is already gone and only descendants remain to rekey.

**The teardown must run only after the file move succeeds, never before.** `handleRename` moves the
`.nbt` first, inside a `try` — carrying its `LocalHistoryStore` revisions across via
`LocalHistoryStore.moveDescendantHistories` (which walks the moved subtree and calls the
single-file `moveHistory` primitive for each `.nbt` under it) — and only calls
`EditorDimRegistry.unplaceStructure`/`rekeyForRename` in the success path afterward. A file move is
an IO operation that can fail (a lock, a permission problem, a full disk) for reasons the server
can't always predict up front. If the registry teardown ran first — clearing the placed blocks and
dropping the registry keys before the move was confirmed — a failed move would leave the player told
"rename failed" while the structure's blocks are already erased from the world and its (untouched,
still-old-named) file sits on disk unrecoverably out of sync with what the player just saw. Ordering
the teardown strictly after a successful move means a failed rename is a true no-op: the file didn't
move, so the registry and the world are left exactly as they were.

## The `BasicLazyTree` first-composition prune

Jewel's `LazyTree`/`BasicLazyTree` compute their flattened row list once per `(tree, treeState)`
identity and, as part of that, intersect `treeState.openNodes` down to only the ids reachable by
descending from already-open roots — silently discarding anything else, including a `LaunchedEffect`
that hasn't run yet. This governs both the project-root auto-open and the context menu's
"open the target folder before showing its new placeholder row" step, and is already documented in
full — including why the fix must be synchronous, not a `LaunchedEffect` — in
[jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model](jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model).
Not duplicated here.
