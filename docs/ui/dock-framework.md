---
title: GarnetDock — full-window Compose dock over the world composite
tags: [compose, dock, layout, panels, input, rendering, lifecycle]
summary: How GarnetDock lays out LEFT/RIGHT/BOTTOM/CENTER regions at real framebuffer pixels via ComposeSceneHost, why the center is transparent by omission, how joining a Garnet-capable world auto-opens LEFT via applyDockAutoOpen() and garnet-dock.json, and two Compose API gotchas (verified against 1.11.0, formerly 1.12.0-beta02).
---

# GarnetDock — full-window Compose dock

The dock is a single `@Composable` (`GarnetDock(realW, realH)`,
`src/client/kotlin/.../ui/dock/GarnetDock.kt`) hosted full-window by
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

## Regions, panels, and the stripe

`DockState` holds one flat `panels: SnapshotStateList<Panel>` registry rather than four independent
per-region lists. `Panel(id, title, region, icon, content)` carries its own `region` and `icon` (a
Jewel `IconKey`); `DockState.panelsFor(region)` derives the per-region view by filtering on that
field, so there is exactly one definition of "which panels does this region have" — the one
`DockStripe` renders.

**Each region renders its open panel's body.** Visibility is per-*panel*, not per-region: a private
`openPanel: Map<DockRegion, String>` names which panel id is open in each region (absence = closed),
read through `DockState.isVisible(region)`/`openPanelOf(region)` and written through
`DockState.showPanel(id)`/`closeRegion(region)`/`togglePanel(id)`. `RegionColumn` in `GarnetDock.kt`
renders `openPanelOf(region)`'s `content`, filling the region — there is no tab strip inside a region
any more. Instead, a JetBrains-IDE-style icon **stripe** (`DockStripe.kt`, replacing the deleted
`DockTabStrip.kt`) sits at `x = 0`, one icon per LEFT panel, the open one highlighted; clicking an icon
calls `togglePanel`, which opens it (evicting whatever LEFT had open) or, if it's already open, closes
LEFT. See [dock-stripe.md](dock-stripe.md) for the full model — the visibility API, why the stripe is
gated on `anyActive()` and vanishes with a fully-closed dock, and why it is drawn last and hit-tested
first. LEFT now has three registered panels: the Project Explorer, [Local History](local-history-panel.md),
and the [Structure Info panel](structure-info-panel.md) — reached via the stripe's three icons, not
tabs.

Every region starts with nothing open at client init (`DockState.panels` gets populated, but no
`showPanel` call follows) except by explicit action: the Alt+1/Shift+1 keybinds (see
`dock-input-routing.md`) or, on joining a world, auto-open below. Seeding a panel into the registry
does not make its region visible on its own.

### LEFT auto-opens on joining a Garnet-capable world

A JOIN handler in `registerDockWorldLifecycle()` (`ui/viewport/DockKeybinds.kt`) calls
`applyDockAutoOpen()` (`ui/dock/DockAutoOpen.kt`), which applies the open-panel map remembered in
`config/garnet-dock.json` (`DockLayoutStore`, see
[persistence/explorer-session-state.md](../persistence/explorer-session-state.md#sibling-store-configgarnet-dockjson))
when the peer speaks Garnet (`DockAutoOpenGate.isGarnetServer()`, defaulting to
`ClientPlayNetworking.canSend(ListEditorTreeC2S.TYPE)` — a swappable `var` that is the seam unit
tests use to drive both branches without a server) and that map isn't already exactly what's open. The
record is an **open-panel map keyed by region** (`{"open": {"LEFT": "garnet.explorer"}}`), not a
boolean — see [dock-stripe.md](dock-stripe.md#persistence-garnet-dockjson-moved-from-a-boolean-to-an-open-panel-map)
for the legacy `{"leftVisible": true}` shape it migrates from. A vanilla server never gets a dock: an
empty Explorer that shrinks the viewport is strictly worse than no dock at all. `applyDockAutoOpen()`
only changes visibility — it never calls `DockInputRouter.focus(...)`, so `DockState.focusedRegion`
stays `null` and the game keeps input (see [dock-input-routing.md](dock-input-routing.md)).

Default region sizes live on `DockState` (`DEFAULT_LEFT` = **280**, `DEFAULT_RIGHT`, `DEFAULT_BOTTOM`).
`DEFAULT_LEFT` is 280 rather than a rounder 260 for historical reasons only: it was originally sized
to fit the Explorer's old action row (name field, `+ New`, `Save`, `Discard`) without clipping. That
row is gone — the current toolbar (`ExplorerToolbar`: kebab menu + refresh + collapse-all, all compact
icon buttons) needs nowhere near that width — and the value is kept at 280 purely to avoid changing a
user-visible default with no functional reason to.

## Panel composition must not outlive its mount

**Two guards, and both are load-bearing.** The dock composes into a *long-lived singleton* scene
(`ComposeSurface.host`), and **composition only advances during a rendered frame**. Two independent
consequences follow, each of which was observed as a real defect (a Jewel `Dropdown` menu that stayed
painted over the panel after the dock was hidden and shown again, with ESC unable to reach it):

1. **Remounting the same panel reuses its composition.** `RegionColumn` invokes
   `openPanelOf(region).content(...)` at a fixed slot, and a panel rebuilt by the same factory yields a
   composable lambda with the *same source key* — so Compose reuses the group and every `remember`
   inside the panel survives, including a `Dropdown`'s open flag and the `Popup`/`ComposeSceneLayer`
   it attached. Guard: `DockState.mountEpoch(region)`, a per-region counter bumped whenever a region's
   **open panel changes** — a hide (`closeRegion`), a same-region panel switch (`showPanel` moving
   LEFT from Explorer to Local History, say), and `reset()` — used together with the panel id as the
   `key(...)` of the panel body. A remount, including a same-region panel *swap*, is then a genuinely
   new composition. See [dock-stripe.md](dock-stripe.md#mount-epochs-now-bump-on-a-panel-switch-not-only-on-hide--the-ghost-popup-bug-this-fixes)
   for the ghost-popup bug that widening this trigger from "hide only" fixed.
2. **Hiding the dock stops rendering before the removal can be disposed.** `syncDockViewport` drives
   `ComposeOverlay.enabled` off `DockState.anyActive()`, so the frame after a hide never happens: the
   scene freezes with the panel still mounted, still focused, popup layers still attached. Guard:
   `ComposeOverlay.enabled`'s **setter** calls `ComposeSurface.markSceneStale()`, which makes the next
   `renderFrame` discard and rebuild the whole scene, and makes `ComposeInput`'s input forwarders
   refuse events in between (a stale focused widget would otherwise keep consuming keys — enough to
   swallow the ESC that is supposed to drop dock focus). Putting it in the setter rather than at each
   hide site is deliberate: that flag is the choke point every hide path already goes through.

Neither guard subsumes the other: (1) covers a panel swap or `DockState.reset()` while the dock stays
visible and rendering; (2) covers everything frozen by rendering stopping. Both are regression-tested
in `JewelExplorerSpec`, and necessarily by **pixel probe** — every state flag reads clean while the
stale menu is still painting.

## World-session lifecycle

`DockState` is a client-lifetime singleton — the Project Explorer is seeded once in
`GarnetClient.onInitializeClient` and never re-added — but its *visibility* is world-scoped.
`registerDockWorldLifecycle()` (`ui/viewport/DockKeybinds.kt`) hooks
`ClientPlayConnectionEvents.DISCONNECT` and calls `DockState.closeAll()`, then `syncDockViewport()`
and `garnet$updateScaledFramebuffer(true)`. Without it the dock keeps painting over the title screen,
the viewport stays shrunk, and a focused region keeps eating GLFW input through the mixins. The same
function also hooks `ClientPlayConnectionEvents.JOIN`, calling `applyDockAutoOpen()` and, only when it
returns `true`, the same `syncDockViewport()` / `garnet$updateScaledFramebuffer(true)` pair — see
"LEFT auto-opens on joining a Garnet-capable world" above.

The whole callback runs inside `mc.execute { ... }`. `fabric-networking-api-v1` fires `DISCONNECT`
from two sites in `ClientConnectionMixin` — `handleDisconnection` on the main thread, or
`channelInactive` on a **Netty event-loop thread** — whichever wins the CAS, so the handler cannot
assume it is already on the client thread; `garnet$updateScaledFramebuffer` reaches
`eventHandler.resizeGui()`, which is unsafe to call concurrently with rendering.

The Project Explorer's per-world state is reset from its **own** `DISCONNECT` registration in
`editor/ui/ExplorerLifecycle.kt`, not from this one: `ProjectTreeState`, `ExplorerTreeState`,
`UndoState`, `OpenStructureState` and `LocalHistoryState` are all reset there (after the session
save — see
[persistence/explorer-session-state.md](../persistence/explorer-session-state.md#save-trigger-points)),
clearing the previous session's tree snapshot, its expansion/selection, the undo labels, and the
placed structure plus its revision list, so a join into a different world or server does not show a
stale tree or send packets built from the old root's paths. None of it lives in
`DockState.closeAll()` — `closeAll()` stays free of IDE-state and `Minecraft` dependencies, which is
what keeps it unit-testable.

`closeAll()` is deliberately narrower than `reset()`:

| Dropped on disconnect | Kept |
|---|---|
| Every region's open panel (LEFT/RIGHT/BOTTOM/CENTER closed via `closeRegion`) | Splitter sizes (`leftWidth`, …) |
| CENTER panels — per-world documents, removed from the registry entirely | LEFT/RIGHT/BOTTOM panel registrations |
| Input focus (`focusedRegion`) | |

A full `reset()` would clear `panels` entirely, and since the Explorer is only added at client init
that would leave LEFT permanently empty for the rest of the process.

Two non-obvious details. `closeAll()` bumps CENTER's mount epoch by hand after removing its panels —
`closeRegion` already bumps the epoch for a region whose open panel changes, but a CENTER panel can be
*registered* without ever being opened, so the explicit bump after the removal guarantees a popup
opened in a center panel cannot outlive the world even in that edge case. And it clears
`focusedRegion` directly rather than calling `DockInputRouter.clearFocus()`: that helper re-grabs the
mouse when no `Screen` is open, and at `DISCONNECT` time the title screen is not reliably installed
yet, so it would capture the cursor on the title screen. `DockInputRouter.captured` reads through to
the field, so clearing it is sufficient.

The `Alt+1` / `Shift+1` keybinds are likewise no-ops while `mc.level == null`, so the dock cannot be
re-opened from the title screen. The click is still consumed so presses do not fire on the next join.

## Input routing and the OFF-by-default guard

The dock never steals input on its own. `DockInputRouter.captured` (`= DockState.focusedRegion !=
null`) gates every GLFW mouse/keyboard mixin — see [dock-input-routing.md](dock-input-routing.md) for
the mixin targets, the Alt+1 (focus)/Shift+1 (visibility) keybinds, and ESC-drops-focus. Both pointer
input (move/press/release/scroll) and key/character input (arrow-key navigation, typed text) are
wired end-to-end into the Compose scene while a region is focused. Every entry point on
`ComposeSurface` is guarded — a native-load or Skia failure sets `ComposeSurface.disabled` and the
whole dock (rendering and input) silently no-ops back to vanilla, never crashing the client.

## A Compose API gotcha

- **Splitter has two overloads that differ only by lambda arity.** The full `Splitter(Modifier,
  (dx, dy) -> Unit)` and the horizontal convenience `(Modifier, (dx) -> Unit)` don't clash at the JVM
  level (`Function2` vs `Function1`), but to avoid call-site overload ambiguity the horizontal one is
  named `SplitterX`. LEFT/RIGHT use `SplitterX`; BOTTOM uses the two-arg `Splitter` and reads `dy`.

## First real panel: the Project Explorer (live-data pattern, now on Jewel)

`editor/ui/ProjectExplorerPanel.kt` + `editor/ui/ExplorerToolbar.kt` +
`editor/ui/ProjectTreeState.kt` + `editor/ui/ExplorerTreeState.kt` are the first non-demo panel
and the template future panels (debugger, timeline) should copy. As of the jewel-widget-layer
migration, the panel is built entirely from JetBrains Jewel components (`LazyTree`, `PopupMenu`,
`IconButton`) under one `IntUiTheme(isDark = true)`, not hand-rolled `BasicText`/`Box.clickable`
rows. The pattern:

- **State is split across two `mutableStateOf`-backed singletons**, neither of which is the
  panel. `ProjectTreeState` holds only the server-driven data: `snapshot:
  EditorTreeSnapshotS2C?` and `status: String`, mutated by its S2C packet handlers
  (`onSnapshot/onFolderLoaded/onSaveReport/onError`). `ExplorerTreeState` owns all UI-only tree
  state — selection and expansion — by wrapping a single Jewel `TreeState` (hoisted, not
  `rememberTreeState()`'d inside composition, so packet handlers and tests can drive it from
  outside a composable): `selectedPath`/`select(path)` and `expandedPaths`/`toggleExpanded(path)`
  read/write `treeState.selectedKeys`/`openNodes` directly rather than mirroring them in a second
  field, and `buildTreeFrom(root: FolderNode, edit: ExplorerEdit? = null): Tree<FileTreeNode>`
  converts a snapshot into a Jewel `Tree` (node `id`s are the same `/`-joined paths used everywhere
  else). The `edit` parameter is the in-tree rename/create field state — see
  [jewel-widget-layer.md](jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model) for the
  NUL-suffixed placeholder id it injects for a pending create. Keep both state objects separate
  from the `Panel` so packet handlers never touch Compose internals.
- **`explorerPanel(): Panel`** returns the panel (`Panel("garnet.explorer", "Explorer", DockRegion.LEFT,
  AllIconsKeys.Toolwindows.ToolWindowProject) { … }`);
  it is registered once into `DockState.panels` at client init (`GarnetClient`). LEFT starts with
  nothing open at that point; Shift+1/Alt+1 open it manually, and joining a Garnet-capable world
  auto-opens it (see "LEFT auto-opens on joining a Garnet-capable world" above).
- **The tree renders via Jewel's `LazyTree`**, not a hand-written recursive composable.
  `val tree = remember(snap.root, edit) { ExplorerTreeState.buildTreeFrom(snap.root, edit) }` builds
  the `Tree<FileTreeNode>` — **`remember` it**: `buildTreeFrom` walks the whole project tree
  recursively and allocates a fresh `Tree`, which `LazyTree` then has to re-flatten, so an
  un-remembered call would pay that cost on every recomposition; keying on `edit` too means the
  pending-create placeholder row appears and disappears without an unrelated rebuild being needed.
  `buildTreeFrom` emits the project
  root itself as the tree's single top-level element (id `ExplorerTreeState.ROOT_PATH`, `""`), with
  its children nested beneath — the root's name is what makes the panel show the project name at
  all, and it is the right-click target that will mean "create at the project root". That same
  `remember` block also opens the root synchronously
  (`ExplorerTreeState.treeState.openNodes += ExplorerTreeState.ROOT_PATH`, *before* calling
  `buildTreeFrom`) rather than in a `LaunchedEffect` — see
  [jewel-widget-layer.md](jewel-widget-layer.md#tree-state-is-jewels-not-a-custom-model) for why a
  `LaunchedEffect` here is one frame too late and silently discards any other node's expand state too;
  `LazyTree(tree, treeState = ExplorerTreeState.treeState, onElementClick = { ... }) { element ->
  TreeRow(...) }` handles expand/collapse and row layout — `LazyTree` has no `selectionMode`
  parameter of its own in Jewel 0.39.1-262.9437.29 despite some Jewel docs implying otherwise;
  selection mode is a constructor argument of the `SelectableLazyListState` that backs
  `TreeState` (`ExplorerTreeState` constructs it with `SelectionMode.Single` explicitly — the
  single-arg `SelectableLazyListState(LazyListState())` convenience constructor defaults to
  `SelectionMode.Multiple`, which is *not* what a single-selection file tree wants).
  `Tree.Element.Node.children` is lazy (only materializes once `open()`/expand is called) while
  node `id`s are eager — this doesn't affect `LazyTree` itself, only direct `Tree` traversal.
- **Clicks dispatch by node kind** (`onElementClick` in `ProjectExplorerPanel.kt`): **any** folder
  toggles open/closed from anywhere on its row, and a "spec-folder" (directly contains a `FileNode`
  named `*.spec.kts`, i.e. `node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }`)
  *additionally* sends `LoadEditorFolderC2S(path)` on that same click. The row toggle is ours, not
  Jewel's: `LazyTree` opens a node only from its chevron or a double-click, which leaves the folder
  name — the largest target on the row — inert. Measured at `Density(1f)`, the chevron occupies
  x≈18..26 and consumes its own press, so it never reaches `onElementClick` and a chevron click
  toggles exactly once (pinned by `ExplorerRowClickSceneTest`). Sends go through
  `ExplorerActions.sender`, not `ClientPlayNetworking` directly, so the click policy is testable off
  a live connection. Clicking a file row is highlight-only, no packet sent — and `onElementClick` deliberately
  does **not** call `ExplorerTreeState.select(path)`: `LazyTree` has already written the clicked
  element's id into `TreeState.selectedKeys` before invoking the callback, and Jewel's `TreeState` is
  the declared single source of truth for selection, so a second writer here is at best redundant.
  The exception is a `.nbt` `FileNode` (`node.extension == "nbt"`), which additionally sends
  `PlaceStructureC2S(path)` to place the standalone structure centered in its auto-assigned region.
  The Refresh `IconButton` sends `ListEditorTreeC2S.INSTANCE` (send the `INSTANCE`, never a fresh
  unit payload — see `EditorPackets`). `TreeRow` prefixes a row's label with `●` when the row's
  path equals `snapshot.currentSubpath`, and shows
  a Jewel `AllIconsKeys` icon per node kind (`Nodes.Folder`, `FileTypes.Archive` for `.nbt`,
  `FileTypes.Text` otherwise). There is no per-node dirty flag any more — a `.nbt`'s auto-save
  state lives server-side (`StructureAutoSave`), not in the tree.
- **`ExplorerToolbar()`** is the panel's single top row (replacing the earlier root-name `Dropdown`
  header plus a separate `StructureActions()` "+ New"/"Save"/"Discard" row): a kebab `IconButton`
  (`AllIconsKeys.Actions.More` — the *vertical* three-dot kebab; `Actions.MoreHorizontal` is a
  different icon) opens a Jewel `PopupMenu` with a single "Open Folder…" item
  (`RootPickerController.openFolder()`), and a right-aligned group of icon buttons: Undo
  (`UndoC2S.INSTANCE`) and Redo (`RedoC2S.INSTANCE`), each enabled only while the server's last
  `UndoStateS2C` carried a label for it, then Refresh
  (`ListEditorTreeC2S.INSTANCE` — send the `INSTANCE`, never a fresh unit payload, see
  `EditorPackets`; the same rule applies to the two undo singletons) and Collapse All
  (`ExplorerTreeState.collapseAll()`, which clears
  `treeState.openNodes` and leaves selection untouched). `PopupMenu`'s `onDismissRequest` takes an
  `(InputMode) -> Boolean` in this Jewel version, not a no-arg lambda.
  **`New`/`Rename` now have a client UI trigger: the right-click context menu**, not a toolbar
  button — see [explorer-toolbar-and-context-menu.md](explorer-toolbar-and-context-menu.md) for the
  full `ExplorerContextMenu`/inline-field write-up. `NewStructureC2S` was reshaped to
  `NewStructureC2S(parentSubpath, name)` (folder-targeted, not session-active-folder-targeted) and
  `CreateFolderC2S`/`RenamePathC2S` are new alongside it; all three are handled in
  `EditorFileOpsHandlers`/`EditorStructureHandlers` and covered by client and gametest specs. **`Save` still has no
  client UI trigger** — `SaveStructureC2S` (a force-commit through `StructureCommit`) remains fully
  wired server-side and covered by `EditorStructureNetworkSpec`, with no tree-row action that sends
  it yet. There is no `Discard` any more: a placed structure auto-saves continuously, so there is
  nothing to discard back to — see `docs/persistence/local-history.md` for the rollback path.
  `StructureResultS2C` still surfaces through `ProjectTreeState.onStructureResult` into the same
  status line as folder load/save results whenever those packets fire (e.g. from gametest
  coverage). See
  [architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files)
  for the region-placement model those packets drive.
- **"Open Folder…" runs `RootPickerController.openFolder()`**, a native folder picker that swaps
  the single server root via `SetEditorRootC2S` → `handleSetRoot`. Multi-root / "Attach Folder" is
  still pending (Plan B). The kebab `PopupMenu` (and, before it, a root-name `Dropdown`) replaced a
  hand-rolled `RootMenu` overlay (`RootPickerController.menuOpen`/`toggleMenu`/`closeMenu`, since
  deleted) that existed only because Compose `Popup`s were believed unable to render inside the
  embedded scene — see [dock-dialogs.md](dock-dialogs.md) for why that premise turned out to be
  wrong and how the native picker is threaded.

## `ImageComposeScene` input API (verified against 1.11.0, formerly 1.12.0-beta02)

`sendPointerEvent(eventType, position, scrollDelta = Offset(...))` — the scroll delta parameter is
named `scrollDelta`. `sendKeyEvent(KeyEvent): Boolean`. Both confirmed against the `ui-desktop` jar.
