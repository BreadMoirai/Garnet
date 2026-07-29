# Explorer toolbar & context menu — design

Date: 2026-07-28

## Problem

The Project Explorer panel is a prototype UI wearing IDE clothes. Three things are wrong:

1. **The dock's tab strip is unthemed.** `GarnetDock.RegionColumn` hand-rolls the strip from `Box` +
   `BasicText` with hardcoded colours (`TAB_BG = 0xFF2D6DA3`), and it sits *outside* `IntUiTheme` —
   the theme only begins inside `ProjectExplorer()`. With exactly one panel registered
   (`explorerPanel()` in `LEFT`) the strip earns nothing.
2. **The panel's actions are a debug bar, not a toolbar.** A root-name `Dropdown`, a bare name
   `TextField`, and `+ New` / `Save` / `Discard` buttons occupy two rows above the tree, and the
   comment block in `StructureActions` documents a fight to fit four controls into 300px.
3. **Creation is context-free.** `NewStructureC2S(name)` creates in the *session's active folder* —
   whichever folder was last loaded as a project — not in the folder the user is looking at. There
   is no way to create a folder at all, and no way to rename anything.

## Goals

- Remove the tab strip; the panel body owns the region.
- Replace both action rows with one IntelliJ-style toolbar: kebab menu, Refresh, Collapse All.
- Right-click any tree node for `New ▸ (Folder | Structure)` and `Rename`, with the name typed into
  an inline field rendered in the tree at the position the item will occupy.
- Back all of that with real packets and server handlers.

## Non-goals

- Multi-root / "Attach Folder" — still deferred (see `2026-07-26-explorer-root-picker-design.md`).
- Delete / Copy / Move / drag-and-drop.
- Restoring Save/Discard to the UI (see *Accepted regressions*).

## Design

### 1. Pointer buttons reach the Compose scene

`DockInputRouter.onGlfwPress(button)` and `onGlfwRelease(button)` currently **discard** their
`button` argument, and `ComposeSceneHost.pointerPress/pointerRelease` call
`scene.sendPointerEvent(type, pos)` with no button. Compose therefore sees every click in the dock
as `PointerButton.Primary`, and a right-click is indistinguishable from a left-click.

`MouseHandlerMixin` already forwards the real GLFW button index, so the fix is downstream only:
thread it through `DockInputRouter` → `ComposeSurface.sendPointerPress/Release` →
`ComposeSceneHost` → `sendPointerEvent(..., button = ...)`, mapping GLFW `0/1/2` to
`PointerButton.Primary/Secondary/Tertiary` and dropping higher indices.

Everything else in this design depends on this step.

### 2. The tab strip goes

`RegionColumn` renders the active panel's body and nothing else. Delete the tab `Row`, the `TAB_H`,
`TAB_BG`, `TAB_BG_INACTIVE`, `TEXT` constants, and the `detectTapOrDown` helper.

Deliberately **kept**: `DockState.leftActiveTab`/`rightActiveTab`/`bottomActiveTab`/
`centerActiveTab`, `panelsFor`, `Panel.title`, and the `key(DockState.mountEpoch(region),
panels[active].id)` wrapper. The multi-panel model and the popup-lifecycle guard it enforces are
unaffected — only the chrome that let a user *switch* tabs is removed. `Panel.title` stays as panel
identity for tests and for whatever chrome replaces the strip later.

`PANEL_BG` stays as the region backdrop, since a panel is not obliged to paint its own background.

### 3. The Explorer toolbar

`Header()` and `StructureActions()` are replaced by a single `ExplorerToolbar()` row:

```
[⋮]                                          [↻]  [⇱]
kebab                                   refresh  collapse-all
```

- **Kebab** — `IconButton(AllIconsKeys.Actions.More)`. Verified against the artifacts:
  `Actions.More` maps to `expui/general/moreVertical.svg` (the vertical three-dot kebab), and that
  SVG ships in `com.jetbrains.intellij.platform:icons:262.8665.369`. `Actions.MoreHorizontal` is the
  horizontal variant — not what we want. Menu content: a single `Open Folder…` item calling
  `RootPickerController.openFolder()`.
- **Refresh** — the existing `IconButton(AllIconsKeys.Actions.Refresh)` sending
  `ListProjectTreeC2S.INSTANCE`, moved into the toolbar unchanged.
- **Collapse All** — `IconButton(AllIconsKeys.Actions.Collapseall)` (note the lowercase `a` in
  Jewel's generated name; the key maps to `expui/general/collapseAll.svg`, also present in the icons
  artifact). Calls a new `ExplorerTreeState.collapseAll()` that clears `treeState.openNodes`.

Removed outright: the root-name `Dropdown` (including its disabled `Attach Folder (soon)` item), the
name `TextField`, and the `+ New`, `Save`, `Discard` buttons — along with the now-unused imports
(`Dropdown`, `DefaultSlimButton`, `OutlinedSlimButton`, `rememberTextFieldState`, `clearText`,
`SaveStructureC2S`, `DiscardStructureC2S`).

### 4. The tree gains its root node

`ExplorerTreeState.buildTreeFrom` currently drops the root folder and emits its children as the
top-level rows. Two things now need it back:

- The root's name lost its only display when the `Dropdown` was removed.
- `New ▸ Folder` needs a right-click target that means "at the project root".

So `buildTreeFrom` emits the root `FolderNode` as the single top-level node with id `""`, its
children nested beneath. The empty-string id needs no new plumbing — `FolderNode.resolve("")`
already returns the root (`FileTree.kt:80`), and `ProjectRoot.resolveSubpath("")` resolves to the
root directory and passes its own containment check (`ProjectRoot.kt:21-33`). The `/`-joined path
key space documented in `docs/ui/jewel-widget-layer.md` is preserved; `""` is simply its origin.

The root node starts expanded.

### 5. Context menu

New file: `src/client/kotlin/com/breadmoirai/garnet/client/ide/ExplorerContextMenu.kt`.

A secondary-button press on a tree row (detected with `Modifier.pointerInput` on the row content,
filtering `PointerButton.Secondary`) selects that row via `ExplorerTreeState.select(path)` and
records `(targetPath, pointerOffset)` into the menu state. The panel renders a Jewel
`PopupContainer` anchored at that offset with:

```
New            ▸   Folder
─────────          Structure
Rename
```

`New` is a Jewel `MenuScope.submenu(...)` — native nesting, no hand-rolled flyout. The menu is
identical on files and folders; `New` targets the right-clicked folder, or the **parent** folder of
a right-clicked file, matching IDE behaviour. `Rename` targets the clicked node itself, and is
disabled on the root node.

Dismissal: outside click (`PopupProperties(focusable = true)` + `onDismissRequest`) or Escape.
`DockInputRouter` already offers Escape to the scene before dropping dock focus, so this works for
free — see `docs/ui/dock-input-routing.md`.

This is a second popup-bearing widget in the panel, so it inherits the `mountEpoch` +
`markSceneStale()` lifecycle guards described in `docs/ui/jewel-widget-layer.md`. Menu state must be
panel-scoped (`remember`-ed inside the panel composable, not a top-level `object`) so a panel
re-mount cannot resurrect an open menu.

### 6. Inline editing

One state type drives both operations:

```kotlin
sealed interface ExplorerEdit {
    data class Creating(val parentPath: String, val kind: NewNodeKind) : ExplorerEdit
    data class Renaming(val path: String, val original: String) : ExplorerEdit
}
enum class NewNodeKind { FOLDER, STRUCTURE }
```

**Renaming** swaps that node's `TreeRow` label for a `TextField` prefilled with the current name and
fully selected.

**Creating** injects a synthetic placeholder child into the target folder's children inside
`buildTreeFrom`, so the field renders *in the tree, at the depth and position the new item will
occupy*:

```
▾ my-project
  ▾ redstone
      ▸ clock.nbt
      📁 [__________]   ← the field, in place
    ▸ assets
```

Two consequences of the synthetic-node approach, both load-bearing:

- **`buildTreeFrom` becomes a function of `(root, edit)`,** so the `remember(snap.root)` key in
  `ProjectExplorer` must widen to `remember(snap.root, edit)`. The existing comment on that
  `remember` explains why the key matters (an un-keyed call rebuilds the whole tree on every S2C
  packet); widening it keeps that property while letting the placeholder appear and disappear.
- **The sentinel id must be uncollidable with a real path.** Use `parentPath + "/\u0000new"` — NUL
  is illegal in a filename on every supported filesystem, so the id can never collide with a real
  `/`-joined path. `TreeRow` switches on that id to render the field instead of a label, using the
  folder or structure icon per the pending `NewNodeKind`.

Starting a create expands the target folder (`treeState.openNodes += parentPath`) so the field is
actually on screen.

**Commit / cancel.** Enter validates and, if valid, sends the packet and clears the edit state.
Escape or focus loss cancels. An invalid name keeps the field open, renders it with Jewel's
`Outline.Error`, and sends nothing.

**Validation** lives in one place: a new `com.breadmoirai.garnet.project.ProjectNames` in `src/main`,
so the client pre-check, the server re-check, and the unit tests all execute the same rule. A name is
invalid when it is blank, contains `/` or `\`, is `.` or `..`, or matches an existing sibling's name
in the current snapshot. `STRUCTURE` creation appends `.nbt` if the typed name lacks it, and the
sibling check runs against the resolved final name.

The client's snapshot can be stale, so the server re-validates independently and answers
`ProjectErrorS2C` on anything that slips through; that error surfaces in the panel's existing status
line.

### 7. Packets and server handlers

**`NewStructureC2S` changes shape** from `(name)` to `(parentSubpath, name)`, and
`ProjectNetworkRegistry.handleNewStructure` resolves `parentSubpath` against the root instead of
reading `ProjectSession.activeSubpath`. Changing the existing payload rather than adding a parallel
one avoids leaving a caller-less packet behind; the cost is updating `StructurePacketsTest` and
`ProjectStructureNetworkSpec`.

**Two new payloads**, registered alongside the rest in `ProjectNetworkRegistry`:

- `CreateFolderC2S(parentSubpath: String, name: String)`
- `RenamePathC2S(subpath: String, newName: String)`

All three handlers follow the shape `handleNewStructure` already uses: resolve the root, resolve the
target through `ProjectRoot.resolveSubpath` (which rejects absolute paths and anything escaping the
root), re-validate the name through `ProjectNames`, perform the filesystem operation, then
`sendTree(server, player)`. Failures send `ProjectErrorS2C`.

Rename has two sharp edges that the handler must address explicitly:

- **Sidecars.** Renaming `x.nbt` must also move `x.nbt.unsaved` if it exists
  (`StructurePersistence.unsavedSidecarOf`), or the unsaved edits silently detach from their
  structure.
- **Placed structures.** `ProjectDimRegistry` keys placed structures by subpath
  (`placedStructureSubpaths()`, `structureRegionOriginOf(subpath)`). Renaming a currently-placed
  structure would strand those entries. Rather than rekeying live world state, **the handler refuses
  the rename** with `ProjectErrorS2C("structure is placed — unplace it first")`. Rekeying is a
  larger change than this spec should carry.
- **Active session.** Renaming a folder that is, or contains, `ProjectSession.activeSubpath`
  invalidates that session's path. The handler repoints the stored `activeSubpath` to the new path
  when it is a prefix match, so a loaded project survives a rename of one of its ancestors.

## Accepted regressions

Removing the `Save` and `Discard` buttons leaves **no UI path to `SaveStructureC2S` or
`DiscardStructureC2S`**. Both packets, their handlers, and their gametest coverage stay in place and
keep working; only the buttons are gone. Restoring them as structure-specific context-menu items is
the natural follow-up, and is deliberately out of scope here — this spec's context menu is
`New`/`Rename` only, as specified.

`ExplorerTreeState.selectedHasUnsaved()` loses its only production caller. It is kept: dirty markers
still render in `TreeRow`, it is covered by an existing spec, and it is exactly what a future
`Save`/`Discard` context-menu item will need for its enablement predicate.

## Implementation phases

The plan sequences this in three reviewable phases:

1. **Chrome** — pointer-button plumbing, tab-strip removal, toolbar, `collapseAll()`, root tree node.
   Independently verifiable in the running client.
2. **Context menu + inline edit** — popup, submenu, `ExplorerEdit`, synthetic node, `ProjectNames`
   validation, wired to the packets from phase 3 behind a sender seam.
3. **Packets + server** — `NewStructureC2S` reshape, `CreateFolderC2S`, `RenamePathC2S`, handlers,
   sidecar/placed/session handling.

## Testing

**`src/test`** (unit)
- `ProjectNamesTest` — blank, separators, `.`/`..`, sibling collision, `.nbt` appending.
- Codec round-trips for `CreateFolderC2S`, `RenamePathC2S`, and the reshaped `NewStructureC2S`
  (`StructurePacketsTest` update).
- `ExplorerTreeStateSpec`-adjacent tree tests for the new root node and the synthetic placeholder id.

**`src/gametest`** (server handlers)
- Create a folder at the root and in a nested folder.
- Create a structure in a folder that is *not* the session's active folder — the behaviour change
  this spec exists for.
- Rename a file and a folder; assert the `.nbt.unsaved` sidecar moves with its structure.
- Reject a subpath that escapes the root.
- Refuse renaming a placed structure.
- Repoint `ProjectSession.activeSubpath` when an ancestor folder is renamed.
- `ProjectStructureNetworkSpec` updated for the new `NewStructureC2S` shape.

**`src/clientTest`** (`ClientSpec`)
- A secondary-button press opens the menu — the direct test of the phase-1 pointer plumbing.
- `New ▸ Folder` opens an inline field inside the target folder, which auto-expands.
- An invalid name keeps the field open and sends nothing.
- A valid name sends the expected packet (through a sender seam, as `RootPickerController` does).
- Escape cancels the edit; Escape closes the menu.
- Screenshot: toolbar renders three icons and no magenta placeholder squares (the missing-artwork
  failure mode from `jewel-widget-layer.md`), and no tab strip is drawn.

**Existing specs to audit and update**: `DockRenderSpec` (tab-strip pixels), `ProjectExplorerSpec`,
`JewelExplorerSpec`, `ExplorerTreeStateSpec` (root node, removed controls), `StructureExplorerSpec`
(Save/Discard buttons gone).

## Docs to update

- `docs/ui/dock-framework.md` — tab strip removed; regions render the active panel directly.
- `docs/ui/dock-input-routing.md` — pointer buttons are now carried into the scene.
- `docs/ui/jewel-widget-layer.md` — `MenuScope.submenu`, `PopupContainer`, the verified
  `Actions.More` = kebab / `Actions.Collapseall` icon-key names, and the root-node id `""`.
- `docs/persistence/` — the new and reshaped project packets.
- A new `docs/ui/` article for the Explorer toolbar + context menu + inline-edit model, registered in
  `docs/ui/INDEX.md`.
