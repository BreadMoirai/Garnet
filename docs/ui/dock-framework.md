---
title: GarnetDock — full-window Compose dock over the world composite
tags: [compose, dock, layout, panels, input, rendering]
summary: How GarnetDock lays out LEFT/RIGHT/BOTTOM/CENTER regions at real framebuffer pixels via ComposeSceneHost, why the center is transparent by omission, and two Compose API gotchas (verified against 1.11.0, formerly 1.12.0-beta02).
---

# GarnetDock — full-window Compose dock

The dock is a single `@Composable` (`GarnetDock(realW, realH)`,
`src/client/kotlin/.../ui/compose/dock/GarnetDock.kt`) hosted full-window by
`ComposeSceneHost` and blitted over the world composite by `ComposeSurface`. It replaced the
feasibility spike's `ComposeScenePanel` demo (button + `clickCount`), which was deleted.

## Hosting: `ComposeSceneHost`

`ComposeSceneHost(width, height, content)` is a generic `ImageComposeScene` wrapper (the spike's
`ComposeScenePanel` generalized: content is now a constructor parameter). It renders the tree to a CPU
raster `org.jetbrains.skia.Image` each frame (`render(nanos)`) and exposes pointer/scroll/key
forwarders (`pointerMove/Press/Release`, `scroll`, `sendKey`) driven by `DockInputRouter`
(see `dock-input-routing.md`).
`ComposeSurface.ensureHost(w, h)` recreates it on window-size change and hosts `GarnetDock(w, h)`.

## Layout is in **real framebuffer pixels**

The scene runs at `Density(1f)`, so `dp == px`. Region sizes come straight from `DockState`
(`leftWidth`, `rightWidth`, `bottomHeight`, all plain Int px) and are placed with absolute
`Modifier.offset(x.dp, y.dp).width(...).height(...)`. This is deliberate: the same integer geometry
drives both the Compose layout and the viewport framebuffer shrink (`DockInsets`), so a splitter drag
that writes `DockState.setSize(...)` moves the panel edge and the world inset in lockstep with no
density conversion. LEFT/RIGHT columns stop above the BOTTOM region (`height = realH - bottom`); BOTTOM
spans the full width and owns the bottom-left/right corners.

## The center is transparent **by omission**, not by clear-color

`GarnetDock`'s root `Box(Modifier.fillMaxSize())` has **no** `background` modifier. Only the visible
edge regions paint an opaque `PANEL_BG`; the CENTER paints nothing unless a center panel exists. Skia's
canvas is pre-cleared to `0x00000000` in `ComposeSurface`, so every un-painted pixel stays fully
transparent and the composited world shows through. Do not add a background to the root Box — it would
occlude the world. (See `compose-blended-overlay.md` for the premultiplied-alpha blend that composites
these transparent pixels.)

## Regions, panels, and tabs

Each of the four `DockRegion`s (LEFT/RIGHT/BOTTOM/CENTER) holds an independent
`SnapshotStateList<Panel>` (`DockState.leftPanels`/`rightPanels`/`bottomPanels`/`centerPanels`) plus an
`activeTab: Int` index. `Panel(id, title, content)` is a plain data holder — the "tab" concept has no
separate type; a region with 2+ panels renders a tab strip (`RegionColumn` in `GarnetDock.kt`) above
the active panel's `content`, and clicking a tab writes the region's `activeTab` index. LEFT/RIGHT/
BOTTOM are hidden by default (`DockState.leftVisible` etc. all start `false`); CENTER's visibility is
derived (`centerPanels.isNotEmpty()`) rather than an independent flag, since an empty CENTER must stay
transparent. Seeding a panel into a region (e.g. `explorerPanel()` into `leftPanels` at client init)
does not make the region visible — only `setVisible`/`toggleVisible` (driven by the Alt+1/Shift+1
keybinds, see `dock-input-routing.md`) does that, so the dock is off-by-default even once panels exist.

## Input routing and the OFF-by-default guard

The dock never steals input on its own. `DockInputRouter.captured` (`= DockState.focusedRegion !=
null`) gates every GLFW mouse/keyboard mixin — see [dock-input-routing.md](dock-input-routing.md) for
the mixin targets, the Alt+1 (focus)/Shift+1 (visibility) keybinds, and ESC-drops-focus. Both pointer
input (move/press/release/scroll) and key/character input (arrow-key navigation, typed text) are
wired end-to-end into the Compose scene while a region is focused. Every entry point on
`ComposeSurface` is guarded — a native-load or Skia failure sets `ComposeSurface.disabled` and the
whole dock (rendering and input) silently no-ops back to vanilla, never crashing the client.

## Two Compose API gotchas

- **`detectTapGestures` must be imported, not fully-qualified.** A fully-qualified call
  `androidx.compose.foundation.gestures.detectTapGestures(...)` fails to resolve ("Unresolved
  reference") in foundation 1.12; `detectDragGestures` at the same package resolves only because it is
  imported. Import both and call unqualified.
- **Splitter has two overloads that differ only by lambda arity.** The full `Splitter(Modifier,
  (dx, dy) -> Unit)` and the horizontal convenience `(Modifier, (dx) -> Unit)` don't clash at the JVM
  level (`Function2` vs `Function1`), but to avoid call-site overload ambiguity the horizontal one is
  named `SplitterX`. LEFT/RIGHT use `SplitterX`; BOTTOM uses the two-arg `Splitter` and reads `dy`.

## First real panel: the Project Explorer (live-data pattern, now on Jewel)

`client/ide/ProjectExplorerPanel.kt` + `client/ide/ProjectTreeState.kt` +
`client/ide/ExplorerTreeState.kt` are the first non-demo panel and the template future panels
(debugger, timeline) should copy. As of the jewel-widget-layer migration, the panel is built
entirely from JetBrains Jewel components (`LazyTree`, `Dropdown`, `TextField`, `DefaultButton`/
`OutlinedButton`, `IconButton`) under one `IntUiTheme(isDark = true)`, not hand-rolled
`BasicText`/`Box.clickable` rows. The pattern:

- **State is split across two `mutableStateOf`-backed singletons**, neither of which is the
  panel. `ProjectTreeState` holds only the server-driven data: `snapshot:
  ProjectTreeSnapshotS2C?` and `status: String`, mutated by its S2C packet handlers
  (`onSnapshot/onFolderLoaded/onSaveReport/onError`). `ExplorerTreeState` owns all UI-only tree
  state — selection and expansion — by wrapping a single Jewel `TreeState` (hoisted, not
  `rememberTreeState()`'d inside composition, so packet handlers and tests can drive it from
  outside a composable): `selectedPath`/`select(path)` and `expandedPaths`/`toggleExpanded(path)`
  read/write `treeState.selectedKeys`/`openNodes` directly rather than mirroring them in a second
  field, and `buildTreeFrom(root: FolderNode): Tree<FileTreeNode>` converts a snapshot into a
  Jewel `Tree` (node `id`s are the same `/`-joined paths used everywhere else). Keep both state
  objects separate from the `Panel` so packet handlers never touch Compose internals.
- **`explorerPanel(): Panel`** returns the tab (`Panel("garnet.explorer", "Explorer") { … }`);
  it is seeded once into `DockState.leftPanels` at client init (`GarnetClient`). LEFT stays
  hidden by default (Shift+1 reveals it).
- **The tree renders via Jewel's `LazyTree`**, not a hand-written recursive composable.
  `ExplorerTreeState.buildTreeFrom(snap.root)` builds the `Tree<FileTreeNode>` each recomposition;
  `LazyTree(tree, treeState = ExplorerTreeState.treeState, onElementClick = { ... }) { element ->
  TreeRow(...) }` handles expand/collapse and row layout — `LazyTree` has no `selectionMode`
  parameter of its own in Jewel 0.39.1-262.9437.29 despite some Jewel docs implying otherwise;
  selection mode is a constructor argument of the `SelectableLazyListState` that backs
  `TreeState` (`ExplorerTreeState` constructs it with `SelectionMode.Single` explicitly — the
  single-arg `SelectableLazyListState(LazyListState())` convenience constructor defaults to
  `SelectionMode.Multiple`, which is *not* what a single-selection file tree wants).
  `Tree.Element.Node.children` is lazy (only materializes once `open()`/expand is called) while
  node `id`s are eager — this doesn't affect `LazyTree` itself, only direct `Tree` traversal.
- **Clicks dispatch by node kind** (`onElementClick` in `ProjectExplorerPanel.kt`): a folder is a
  "spec-folder" (directly contains a `FileNode` named `*.spec.kts`) iff `node.children.any { it is
  FileNode && it.name.endsWith(".spec.kts") }`; clicking a spec-folder sends
  `LoadProjectFolderC2S(path)`, other folders just expand/collapse (`LazyTree`'s own click-to-toggle
  behavior). Clicking a file row calls `ExplorerTreeState.select(path)` — highlight only, no packet
  sent — **except** a `.nbt` `FileNode` (`node.extension == "nbt"`), which selects **and** sends
  `PlaceStructureC2S(path)` to place the standalone structure centered in its auto-assigned region.
  The Refresh `IconButton` sends `ListProjectTreeC2S.INSTANCE` (send the `INSTANCE`, never a fresh
  unit payload — see `ProjectPackets`). `TreeRow` prefixes a row's label with `●` when the row's
  path equals `snapshot.currentSubpath` or (for a `.nbt` file) `node.hasUnsaved` is true, and shows
  a Jewel `AllIconsKeys` icon per node kind (`Nodes.Folder`, `FileTypes.Archive` for `.nbt`,
  `FileTypes.Text` otherwise).
- **`StructureActions()`**, rendered under `Header()`, provides "+ Structure" (a Jewel `TextField`
  backed by `rememberTextFieldState()` — the `TextField(value: String, onValueChange, ...)`
  overload does not exist in this Jewel version, only `TextFieldState`- and `TextFieldValue`-keyed
  overloads do — plus a `DefaultButton` that sends `NewStructureC2S(name)` to write an empty `.nbt`
  into the active folder and clears the field via the `TextFieldState.clearText()` extension) and
  "Save"/"Discard" `OutlinedButton`s (send `SaveStructureC2S`/`DiscardStructureC2S(selectedPath)`
  when `ExplorerTreeState.selectedPath` ends with `.nbt`; Discard is additionally gated on
  `ExplorerTreeState.selectedHasUnsaved()` and dims via Jewel's own disabled-button styling).
  `StructureResultS2C` for all four structure packets (place/save/new/discard) surfaces through
  `ProjectTreeState.onStructureResult` into the same status line as folder load/save results. See
  [architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files)
  for the region-placement model these actions drive.
- **The panel has a header bar** (`Header` in `ProjectExplorerPanel.kt`): a Jewel `Dropdown`
  labeled with the current root's folder name, and an `IconButton` (`AllIconsKeys.Actions.Refresh`)
  for the refresh action. The dropdown's `menuContent` offers "Open Folder" (runs
  `RootPickerController.openFolder()`, a native folder picker that swaps the single server root via
  `SetProjectRootC2S` → `handleSetRoot`) and a disabled "Attach Folder (soon)" placeholder pending
  multi-root (Plan B). This replaced a hand-rolled `RootMenu` overlay (`RootPickerController.
  menuOpen`/`toggleMenu`/`closeMenu`, since deleted) that existed only because Compose `Popup`s were
  believed unable to render inside the embedded scene — see [dock-dialogs.md](dock-dialogs.md) for
  why that premise turned out to be wrong and how the native picker is threaded.

## `ImageComposeScene` input API (verified against 1.11.0, formerly 1.12.0-beta02)

`sendPointerEvent(eventType, position, scrollDelta = Offset(...))` — the scroll delta parameter is
named `scrollDelta`. `sendKeyEvent(KeyEvent): Boolean`. Both confirmed against the `ui-desktop` jar.
