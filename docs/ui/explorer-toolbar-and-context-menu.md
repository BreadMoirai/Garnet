---
title: Explorer toolbar and context menu
tags: [explorer, toolbar, context-menu, inline-edit, jewel, packets, keyboard]
summary: The Project Explorer's kebab/Undo/Redo/Refresh/Collapse-All toolbar, its right-click New/Rename/Duplicate/Move/Local History/Delete menu and the two dialogs it opens, the inline name field that commits through ExplorerActions (opening fully selected and carrying a file's extension over when the typed name omits it), and why the LazyTree's preview key handler had to stop eating that field's caret/selection keys.
---

# Explorer toolbar and context menu

`ExplorerToolbar.kt` (the panel's single top row: kebab overflow, Undo, Redo, Refresh, Collapse All) and
`ExplorerContextMenu.kt` (the right-click `New Folder` / `New Structure` / `Rename` / `Duplicate` /
`Move to…` / `Local History` / `Delete` menu) together
replaced the old root-name `Dropdown` header and the `+ New`/`Save`/`Discard` structure-action row.
See [dock-framework.md](dock-framework.md#first-real-panel-the-project-explorer-live-data-pattern-now-on-jewel)
for the panel walkthrough and [jewel-widget-layer.md](jewel-widget-layer.md) for the Jewel
mechanics (`PopupMenu` overloads, the NUL-suffixed placeholder id, the `BasicLazyTree` prune). This
article covers the *why* behind the decisions that aren't visible just from reading the code.

## The Undo and Redo buttons hold no client state

The toolbar row is, left to right: the kebab overflow, a weight spacer, **Undo**, **Redo**, Refresh,
Collapse All. The two new buttons are the thinnest possible clients of a server-side feature — each
sends a **bare singleton** (`UndoC2S.INSTANCE` / `RedoC2S.INSTANCE`, which `StreamCodec.unit`
requires be the registered instance and not a fresh construction) and names nothing. The client has
no stack and derives nothing: `UndoState` is a pure mirror of the last `UndoStateS2C`, whose two
nullable labels drive both `enabled` and the `contentDescription` ("Undo delete 'redstone/clock.nbt'"
when a label is present, a bare "Undo" when it is not). A null label means "that button is
disabled".

This is not just economy — it is required. Undo is **per player over shared server state**, so only
the server can know what the next undo means or whether it is still valid, and a client-side guess
would go stale the moment another player touched the tree. `ExplorerLifecycle` calls
`UndoState.reset()` on `ClientPlayConnectionEvents.DISCONNECT`, alongside the tree and expansion
resets, so the buttons do not carry a previous session's labels into the next one. The stack itself
is documented in [persistence/editor-undo-stack.md](../persistence/editor-undo-stack.md).

## What the inline name field does with what you type

The field opens with the current name **fully selected**: the common case is replacing the name
outright, and tweaking it instead is one arrow key away. It is also the reason the field seeds with
`setTextAndSelectAll` rather than `setTextAndPlaceCursorAtEnd`.

A rename that omits the extension keeps the old one — `house.nbt` → typing `cottage` commits
`cottage.nbt`. `EditorNames.resolveRenameName(typed, currentName, isFolder)` owns that rule, next to
the create-side `resolveFinalName`, so both name-shaping decisions sit in one place. Three edges are
deliberate: an explicitly typed extension always wins (including *changing* it, `cottage.txt`);
folders are exempt, because a folder name may legitimately contain dots (`my.stuff`) and has no
extension to preserve; and a leading dot names the file rather than introducing an extension
(`.gitignore`), on both sides of the rule. `ExplorerActions.commitRename` decides `isFolder` from the
tree snapshot, defaulting to "file" when the snapshot cannot answer — the worst case is a dotted
folder keeping a suffix the user dropped, which is visible and redoable, whereas guessing "folder"
would silently strip a real file's extension.

The caret you type against is only visible because the field is a `GarnetTextField`, which bridges
its focus into its own interaction source; see
[text-field-caret-in-raster-scene.md](text-field-caret-in-raster-scene.md) for why the dock's scene
needs that and what breaks without it.

A failed commit's message (`editError`, set by `ExplorerActions.commitCreate`/`commitRename`) renders
**inline, directly under the field**, not on a panel-wide status line — the Explorer no longer has one;
transient status now lives in the [Structure Info panel](structure-info-panel.md#why-editerror-stayed-in-the-explorer-rendered-at-the-field).
Structure Info can be closed at the moment a rename fails, so a message parked there would leave the
field showing its red `Outline.Error` border with no explanation anywhere on screen; rendering it at
the field guarantees the two always appear together.

## Local History

One flat `selectableItem`, between its own two `separator()`s, enabled **only when the target is a
`.nbt` file node** (`target != ROOT_PATH && target.endsWith(".nbt")`). Folders, the project root and
`.spec.kts` files have no structure to place, and the panel behind this item only ever shows a
structure that is actually in the world.

**Flat, not a submenu.** Jewel opens a flyout as a *second focusable popup layer*, and this scene's
`isInteractive(owner)` check stops routing pointer events to every layer below the focused one — so
nested menus are simply broken here. Anything that would want to be a submenu has to be a top-level
item instead. See [jewel-widget-layer.md](jewel-widget-layer.md) for the layer routing itself.

`ExplorerActions.openLocalHistory(path)` is the whole client action: refuse a non-`.nbt` path, send
`PlaceStructureC2S(path)` unless `OpenStructureState.subpath` already names it, then
`DockState.showPanel("garnet.localHistory")`, switching LEFT onto it. It is
"place, *then* look at", never "look at without placing" — that ordering is what upholds the
server-side invariant the restore path depends on. See
[local-history-panel.md](local-history-panel.md) for what that invariant buys.

## Why validation runs on both client and server

`ExplorerActions.commitCreate`/`commitRename`/`commitMove` re-run `EditorNames.validate` against the
client's own tree snapshot before sending `CreateFolderC2S`/`NewStructureC2S`/`RenamePathC2S`/
`MovePathC2S`. This looks redundant next to `EditorFileOpsHandlers.handleCreateFolder`/`EditorStructureHandlers.handleNewStructure`/`EditorFileOpsHandlers.handleRename`/`EditorFileOpsHandlers.handleMove`,
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
top-level `object` the way `ExplorerTreeSnapshot`/`ExplorerTreeState` are. A popup layer belongs to the
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

## Why relocating a placed structure unloads and reloads it — and why relocating a folder rekeys instead

`EditorDimRegistry` tracks placed structures in three maps keyed by subpath: `bySubpath` (a
loaded folder's own region), `structureBySubpath` (a standalone structure's assigned region), and
`placedBoxes` (the last-placed footprint, used for cheap re-clearing). All three are keyed by
subpath, so a rename that only moves the file on disk without touching the registry strands every
entry under the OLD subpath: the structure's placed blocks become unreachable by the new name
(`StructureCommit.commit` resolves the subpath via `EditorRootResolver.rootFor(server).resolveSubpath(subpath) ?:
return null` and silently skips it forever), and a fresh `PlaceStructureC2S(newSubpath)` finds no
registry entry and re-places a second copy in a brand-new region, orphaning the first in the world.

A rename IS a move that happens to keep its parent, so `handleRename` and `handleMove` share an
`internal relocate(oldSubpath, source, target, newSubpath, operation, placedMessage, kind,
record = true): Boolean` in
`EditorFileOpsHandlers` holding everything below; each caller only computes its own target and runs
its own validation first. It is `internal` rather than `private` because `EditorUndoOps` reuses it
to move a node back: `kind` (`RelocateKind.RENAME`/`MOVE`) only picks the wording of the undo
label, and `record = false` suppresses the undo-stack push so an inverse move does not record a
second entry for a move the player never performed. The `Boolean` return says whether the node
actually moved — false means the operation was abandoned and the player has ALREADY been told why,
so the caller must not report it twice. Both handlers ignore it (a handler has nothing left to do
either way); the undo path does not, since it must keep its stack entry when the inverse never
happened. Everything here was already written in `oldSubpath → newSubpath` terms,
which is why it generalized to a parent change without modification. `relocate` handles two distinct
shapes of this problem differently:

- **The renamed node itself is a placed structure** (`registry.placedBoxOf(payload.subpath) !=
  null`): an unload/reload, not a rekey. `relocate` first commits any pending auto-save edits for
  the OLD subpath through `EditorHandlerSupport.commitDirtyUnder` — BEFORE the file move, since the
  dirty state is keyed by subpath and moving first would strand it under a name nothing will ever
  commit again — then calls `EditorDimRegistry.unplaceStructure(oldSubpath)` (clearing
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

`handleDelete` reuses neither branch — it has no destination to rekey onto — but it does repeat the
same `unplaceStructure`-before-`clearBounds` ordering, and for the same reason. See
[use-cases/structure-lifecycle.md](../use-cases/structure-lifecycle.md) UC-MAN-10.j.

**The teardown must run only after the file move succeeds, never before.** `relocate` moves the
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

## Why the two dialogs are one popup layer, opened after the menu closes

`Delete` and `Move to…` need a second surface — a confirmation and a destination picker — and the
obvious way to build that in Jewel, a submenu, is exactly what the nested-popup defect below
forbids. `ExplorerDialogs.kt` sidesteps it rather than fighting it: every menu item calls
`state.close()` *before* invoking its callback, so the menu's popup layer is already gone by the time
`ExplorerDialogState` flips to a pending dialog. At no point are two layers alive at once, so the
`isInteractive(owner)` blocking never comes into play.

Both dialogs reuse `FixedOffsetPositionProvider` and the anchor the menu recorded (`menu.anchor`
survives `close()`, which nulls only `target`), so a dialog opens exactly where the item that
triggered it was rather than jumping to a corner. `ExplorerDialogState` is `remember`-ed in the panel
for the same reason `ExplorerMenuState` is — see the section above; a top-level object would survive
a panel re-mount and repaint over the next one.

**Illegal move destinations are never offered, rather than offered and then refused.**
`moveDestinationsFor` walks the snapshot for folders and drops both the moved node's own subtree and
the folder it already lives in — the same two rules `ExplorerActions.commitMove` enforces and
`handleMove` re-checks server-side. The delete prompt counts `.nbt` files beneath a folder rather
than all nodes, because intermediate folders are not what anyone is afraid of losing; a file or an
empty folder is named without a count.

## The `BasicLazyTree` first-composition prune

Jewel's `LazyTree`/`BasicLazyTree` compute their flattened row list once per `(tree, treeState)`
identity and, as part of that, intersect `treeState.openNodes` down to only the ids reachable by
descending from already-open roots — silently discarding anything else, including a `LaunchedEffect`
that hasn't run yet. This governs both the project-root auto-open and the context menu's
"open the target folder before showing its new placeholder row" step, and is already documented in
full — including why the fix must be synchronous, not a `LaunchedEffect` — in
[jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model](jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model).
Not duplicated here.

## The tree steals the name field's keys

`InlineNameField` is a Jewel `TextField` over a Compose `TextFieldState`, rendered *in place of* a
tree row's label — so it is a **descendant of the `LazyTree`**. That nesting silently broke it as a
text field: the caret would not move, keyboard selection did nothing, and Ctrl+A selected nothing,
while typed characters landed normally. The half-working combination is what made it read as a fake
text box rather than a dead one.

The cause is dispatch order, not delivery. Jewel's `SelectableLazyColumn` (which `LazyTree` is built
on) installs its keybindings with **`Modifier.onPreviewKeyEvent` on the tree container**, and Compose
dispatches preview events **root → leaf** — so the tree gets first refusal on every key before the
focused field nested inside one of its rows. Its default `KeyActions` bind exactly the keys a text
field needs: Left/Right/Up/Down (move selection), Home/End (first/last item), PageUp/PageDown, and
**Ctrl+A** (its own select-all-rows). Each one is reported handled and never reaches the field.
Typed characters survive only because the tree has no binding for them. Mouse input is unaffected:
click-to-place-caret and drag-to-select go through pointer dispatch, which has no such ancestor
veto, and always worked.

A nested widget cannot out-rank an ancestor's preview handler, so the fix has to come from the tree
side. `EditAwareKeyActions` (`ExplorerKeyActions.kt`) wraps the stock
`DefaultTreeViewKeyActions(treeState)` and overrides `handleOnKeyEvent` to return a handler that
never reports a key as handled **while an edit is open** (`edit != null`); `keybindings` and
`actions` delegate untouched, so ordinary tree navigation is byte-for-byte the default whenever no
name field is showing. The tree has no use for keyboard navigation while the user is typing a
filename, and Enter/Escape are still the field's own (`onPreviewKeyEvent` on the field commits and
cancels).

Regression coverage is `InlineNameFieldKeyRoutingTest` (plain JVM `src/test`, no Minecraft): it
drives a `TextField` nested in a real `LazyTree` inside a raster `ComposeSceneHost`, feeding
synthetic key events built exactly the way `DockInputRouter.onGlfwKey` builds them. Three cases pin
the fixed behavior (Ctrl+A, Home + Shift+Right, arrow keys) and a fourth pins the *stock* actions
still swallowing all of them — if that guard ever starts passing the others' assertions, the wrapper
has stopped being load-bearing and the spec has stopped proving anything.

Clipboard (Ctrl+C/X/V) needs no help here — Compose Desktop's clipboard path is not a tree binding,
so it reached the field before this change and still does.
