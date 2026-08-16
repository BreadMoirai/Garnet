---
title: Jewel — the dock's widget layer
tags: [compose, jewel, dock, icons, popup, skiko, versions, layout]
summary: The dock's IntelliJ-look widget layer. Covers the load-bearing Jewel/Compose/skiko version triple, why component icons need a separate artwork artifact, the Jewel-authoritative tree-state model, and how IntUi's default tree metrics inset every row.
---

# Jewel — the dock's widget layer

The Compose dock (`GarnetDock`, see [dock-framework.md](dock-framework.md)) is built from
[JetBrains Jewel](https://github.com/JetBrains/jewel) — the same Compose widget library IntelliJ
Platform plugins use for their tool-window UI — rather than hand-rolled `Box`/`BasicText`
composables or Compose Material. This article is the durable reference for the three things that
cost real debugging time adopting it: the version triple, the icon-artwork split, and the
Jewel-authoritative tree-state model.

## The version triple, and why it moves together

Three coordinates in `build.gradle.kts` are load-bearing as a set, not independently:

- `org.jetbrains.jewel:jewel-int-ui-standalone:0.39.1-262.9437.29`
- `org.jetbrains.compose.{runtime,ui,foundation}-desktop:1.11.0` (stable)
- `org.jetbrains.skiko:skiko-awt-runtime-<host platform>:0.144.6` (classifier chosen per host JVM by
  `skikoNativePlatform`; only the **version** is load-bearing here)

Jewel `0.39.1-262.9437.29` is built against Compose `1.11.0`, whose transitive `skiko-awt` is
`0.144.6`. The project also declares the desktop-GL skiko native directly (for the raster upload
path described in [compose-in-mc-feasibility.md](compose-in-mc-feasibility.md)), and **that native
must exactly match Compose's transitive skiko version** — a mismatch risks a skiko native
version-guard failure or an outright ABI break at class-load. This is why bumping any one of the
three means checking all three: Jewel forces a Compose version, Compose forces a skiko version, and
the bundled native must follow. The project was originally pinned to the `1.12.0-beta02` Compose
pre-release with skiko `0.150.1` (from the earlier feasibility spike, before Jewel entered the
picture); adopting Jewel meant moving the whole triple down to the `1.11.0` stable line together —
see [compose-in-mc-feasibility.md](compose-in-mc-feasibility.md) for the spike's original pins and
the re-pin note.

`build.gradle.kts` marks Jewel, its icon artifact (next section), and the skiko native all
`clientImplementation` — the same client-only scoping as the rest of the Compose runtime, so none
of it ships in the dedicated-server jar. See
[compose-runtime-scoping.md](../tooling/compose-runtime-scoping.md) for the mechanism that keeps
the Compose compiler plugin off the non-client source sets so this scoping is possible at all.

## Icons need two artifacts, and one of them isn't on Central

Jewel components (the file tree, buttons, dropdown chevrons) reference icons by `IconKey`, and
resolving those keys to actual artwork is split across artifacts in a way that isn't obvious from
a dependency graph:

- **`jewel-ui`** ships the full `AllIconsKeys` catalog — the `object` tree of icon key constants
  (`AllIconsKeys.Nodes.Folder`, `AllIconsKeys.Actions.Refresh`, etc.) — but **zero SVGs**.
- **`jewel-int-ui-standalone`** (the artifact this project depends on directly, which pulls in
  `jewel-ui`) ships only 45 SVGs: checkbox, radio, and theme-chrome icons. Nowhere near enough for
  a file tree.
- The transitive `icons-api` / `icons-api-rendering` / `icons-impl` artifacts are rendering
  *machinery* (how an `IconKey` becomes a drawable `Painter`) — they too ship zero SVGs.

Actual artwork — the folder/file/refresh SVGs the catalog keys point at — comes only from a
separate artifact: `com.jetbrains.intellij.platform:icons:262.8665.369`. That artifact is **not
published to Maven Central**; it lives only in the JetBrains IntelliJ Repository
(`https://www.jetbrains.com/intellij-repository/releases`), which is why that repo is declared
alongside Google's and the Compose-dev Maven in `build.gradle.kts`'s `repositories {}` block —
**not** `settings.gradle.kts`, whose `pluginManagement { repositories { } }` block only resolves
Gradle *plugins*, not project dependencies, and does not affect this artifact's resolution at all.
Without the icons artifact on the classpath, every `IconKey` still resolves to a real classloader
resource lookup — it just fails, and Jewel's fallback renders a **magenta placeholder square**
instead of throwing. That silent-degrade failure mode is the practical symptom to recognize: a
tree full of magenta squares means the artwork artifact is missing or its repository isn't wired,
not that the code referencing `AllIconsKeys` is wrong.

**The icon version skew is deliberate, not a typo.** Jewel `0.39.1-262.9437.29` was built against
IntelliJ Platform icon build `262.9437.29`, but only the `262.8665.x` line of the `icons` artifact
is actually published — `icons:262.9437.29` 404s. Icon resource paths are stable within the `262`
branch, so pinning `262.8665.369` (the latest published `262.8665.x` release) resolves cleanly and
supplies the same artwork Jewel's catalog expects.

**Compose Material Icons are not an alternative — don't re-propose them.** Compose's own Material
Icons Extended artifact was discontinued at Compose `1.7.3` with no `1.11`-line build, so it can't
sit alongside this Compose pin even if the API were a fit for `IconKey` (`{ iconClass, path(Boolean)
}`, a classloader-resource lookup — no `Painter`/`ImageVector` seam a Material `ImageVector` could
plug into anyway).

## Popups render in-scene

Jewel's `Dropdown` and `PopupMenu` (the latter now used for the Explorer toolbar's kebab overflow
menu; a root-name `Dropdown` filled this role before the toolbar rework) are built on Compose's own
`Popup`. The dock's `ImageComposeScene` is internally a `CanvasLayersComposeScene`, so popup layers
draw into the *same* raster canvas as the rest of the scene rather than opening a separate desktop
window — Jewel menus render in-scene with no extra plumbing. See
[dock-dialogs.md](dock-dialogs.md) for the full writeup (including the hand-rolled `RootMenu`
overlay this replaced) and why that used to be assumed impossible.

**The lifecycle is the sharp edge, not the rendering.** A popup layer belongs to the composition
that opened it, and the dock composes into a long-lived singleton scene that only advances during a
rendered frame. So an open `Dropdown`/`PopupMenu` will happily outlive the panel that created it — repainting
over the *next* mount — unless the dock explicitly ends the composition. Two guards in the dock make
that impossible (`DockState.mountEpoch` + `ComposeSurface.markSceneStale()`); see
[dock-framework.md](dock-framework.md#panel-composition-must-not-outlive-its-mount). If you add
another popup-bearing Jewel widget, you inherit those guards for free — but do not reintroduce a
panel-content call site that bypasses the `key(...)`.

Also worth knowing: Jewel's `Dropdown` **does** consume `Key.Escape` while its menu is open. That is
why `DockInputRouter` offers ESC to the scene before dropping dock focus (see
[dock-input-routing.md](dock-input-routing.md)) — without that step the menu is unclosable by keyboard.

## Tree state is Jewel's, not a custom model

The Project Explorer's file tree is a Jewel `LazyTree` (see
[dock-framework.md](dock-framework.md#first-real-panel-the-project-explorer-live-data-pattern-now-on-jewel)
for the full panel walkthrough). Jewel's own `TreeState` is the single source of truth for
expansion and selection — there is no separate hand-rolled expand/selected model to keep in sync:

- `addNode`/`addLeaf` (used when building the `Tree<FileTreeNode>` from a `FolderNode` snapshot)
  take explicit `id` parameters, and this project uses the `/`-joined relative path string as the
  id everywhere — the same string the server uses for `currentSubpath` and the same string
  `FolderNode.walk()`/`resolve()` produce. That single key space is what lets selection/expansion
  state, server "current folder" state, and click dispatch all refer to the same node without a
  translation layer.
- `ExplorerTreeState.buildTreeFrom` emits the project root itself as the tree's single top-level
  element, under id `ExplorerTreeState.ROOT_PATH` (`""`) — the root's children nest beneath it,
  rather than becoming top-level rows themselves. This needs no translation on the resolve side:
  `FolderNode.resolve("")` and `EditorRoot.resolveSubpath("")` already both mean "the root". Making
  the root a real node (rather than omitting it) restores a place to show the project name and gives
  a right-click target that means "create at the project root".
- **Opening a node before first composition must happen synchronously, not via `LaunchedEffect`.**
  `BasicLazyTree`'s internal `remember(tree, treeState) { ... }` block runs once per (tree, treeState)
  identity and, as part of computing its flattened row list, does
  `treeState.openNodes = treeState.openNodes.intersect(idsReachableFromOpenRoots)` — i.e. it prunes
  away any `openNodes` entry that isn't reachable by recursively descending through nodes already
  flagged open, starting from the roots. A `LaunchedEffect(key) { treeState.openNodes += id }` used to
  auto-expand the root runs its coroutine body strictly *after* that first composition commits, so
  the root is still closed at prune time — with the observed effect that this prune throws away
  *every other* pre-existing `openNodes` entry too (e.g. a caller/test that expanded a child folder
  before the panel ever mounted), because none of them were reachable from a closed root either.
  `ProjectExplorerPanel`'s `ProjectExplorer()` opens `ROOT_PATH` synchronously inside the same
  `remember(snap.root) { ... }` block that builds the `Tree`, before `LazyTree` is even called, so the
  root is already open by the time `BasicLazyTree`'s prune runs.
- `openNodes` and `selectedKeys` on the underlying `SelectableLazyListState` *are* the
  expanded/selected path sets — `ExplorerTreeState.expandedPaths`/`selectedPath` read and write
  them directly rather than mirroring them into a second field.
- `TreeState` is **hoisted** in `ExplorerTreeState` (constructed once, held outside composition)
  rather than obtained via `rememberTreeState()` inside a composable — so S2C packet handlers and
  tests can drive selection/expansion from outside a composable context.
- **`LazyTree` has no `selectionMode` parameter of its own.** Single-selection is configured on the
  `SelectableLazyListState` that backs `TreeState`: its single-arg convenience constructor
  (`SelectableLazyListState(LazyListState())`) defaults to `SelectionMode.Multiple`, which is not
  what a single-selection file tree wants, so `ExplorerTreeState` constructs it with
  `SelectionMode.Single` explicitly.
- **`Tree.Element.Node.children` is lazy** — it is not materialized until `open()`/expand is
  called on that node — while node **`id`s are eager**, assigned immediately at `addNode`/
  `addLeaf` time regardless of whether the node is ever opened. `LazyTree` itself is unaffected
  (it drives the same open/expand gate Jewel already expects), but code that walks a `Tree`
  structure directly — e.g. a test asserting on node contents rather than going through
  `LazyTree` — will find `children` empty on an unopened node even though its `id` is already
  assigned.
- Jewel also has no `TextField(value: String, onValueChange: (String) -> Unit)` overload — only
  `TextFieldState`- and `TextFieldValue`-keyed overloads exist. The Explorer's inline rename/create
  field (below) uses `rememberTextFieldState()` and the `TextFieldState.clearText()` extension
  rather than a plain `String` state hoist.
- **Use `GarnetTextField`, never Jewel's `TextField` directly.** A Jewel input needs its focus
  bridged into its interaction source to look focused at all in this scene — no caret, no focused
  border otherwise — and the wrapper is what carries that wiring. See
  [text-field-caret-in-raster-scene.md](text-field-caret-in-raster-scene.md).
- **Inline rename/create uses a NUL-suffixed synthetic id, never a real path.** `ExplorerEdit` (the
  in-tree name-field state — `Creating(parentPath, kind)` or `Renaming(path, original)`) needs
  `Renaming` to swap the label of an *existing* row for a field, which is a pure render-time switch
  on `path`. `Creating` has no existing row to swap, though — the item doesn't exist yet — so
  `ExplorerTreeState.buildTreeFrom(root, edit)` appends a placeholder leaf as the last child of the
  target folder (or as the last root-level child, when `parentPath == ROOT_PATH`), and `TreeRow`
  detects that placeholder by id and renders the field in its place. The placeholder's id is
  `ExplorerEdit.pendingIdFor(parentPath)`, literally `"$parentPath/\0new"` — NUL is illegal in a
  filename on every filesystem this mod supports, so this id can never collide with a real
  `/`-joined path. That matters because ids are Jewel's selection/expansion key space (see above);
  a colliding id would let the placeholder silently inherit or corrupt a real node's tree state.
  `ExplorerEdit.isPendingId(id)` is the inverse check. The placeholder's `FileTreeNode` payload is a
  throwaway empty-named `FileNode` — `TreeRow` never reads its name, only its id — so `buildTreeFrom`
  takes `edit: ExplorerEdit? = null` and only synthesizes the placeholder when `edit` is a pending
  `Creating` targeting that folder.

## IntUi's default tree metrics inset every row by 16 px

Out of the box a `LazyTree` row does **not** start at its panel's left edge. `LazyTreeMetrics`
splits a row's horizontal inset in two, and IntUi's defaults set both:

| Metric | IntUi default | What it does |
|---|---|---|
| `elementPadding` (→ `SimpleListItemMetrics.outerPadding`) | `PaddingValues(horizontal = 12.dp)` | Sits **outside** the selection background, so it insets the highlight too |
| `elementContentPadding` (→ `innerPadding`) | `PaddingValues(4.dp)` | Sits **inside** the highlight — the icon's inset from the row edge |
| `indentSize` | `23.dp` | Multiplied by `Tree.Element.depth`; zero for the root row |

`BasicLazyTree` applies them in that order (`padding(outerPadding)` → `padding(innerPadding)` →
`padding(start = depth * indentSize)`). Because the dock's Compose scene runs at `Density(1f)`, the
two fixed paddings are a flat **16 physical pixels** of gutter on the left of every row before the
folder icon — dead space in a tool window only a couple hundred pixels wide — and, because the outer
12 dp is outside the background shape, a selected row's highlight never reaches the panel edge the
way IntelliJ's own Project view does.

`ProjectExplorerPanel.flushTreeStyle()` fixes both by rebuilding the ambient
`LocalLazyTreeStyle.current` with `elementPadding = PaddingValues(horizontal = 0.dp)` and passing it
as `LazyTree(style = …)`. Zeroing the **outer** padding (not the content padding) is the whole trick:
the row background goes edge-to-edge while the 4 dp content padding survives as the icon's inset.

Two things that constrain how this is written:

- **`LazyTreeMetrics`/`SimpleListItemMetrics` have private constructors.** The only way to build one
  is the IntUi extension `LazyTreeMetrics.Companion.defaults(...)` from
  `org.jetbrains.jewel.intui.standalone.styling`. Its parameter order is `indentSize`,
  `elementBackgroundCornerSize`, `elementPadding`, then content padding / min height / gaps — so the
  first three are passed positionally (read back off the base style) and everything after
  `elementPadding` is left at IntUi's defaults.
- **Passing an explicit `style` selects `LazyTree`'s experimental overload**, so the file needs
  `@file:OptIn(ExperimentalJewelApi::class)`. The default-`style` call site does not.

The panel's own `Column` therefore carries `padding(vertical = 4.dp)` only — a uniform inset there
would put the margin straight back. The toolbar and the status/empty-state `Text`s carry their own
horizontal padding instead. Keeping the toolbar's start inset at 4 dp is also what holds the kebab
button at the (14, 12) hit point `JewelExplorerSpec` clicks.

## Right-click context menu drives the inline field

`ExplorerContextMenu` (in `ExplorerContextMenu.kt`) is a Jewel `PopupMenu` with `New Folder`, `New
Structure`, `Rename`, `Duplicate`, `Move to…` and `Delete` items, opened by a right-click on a
`TreeRow`. `New`/`Rename` set `edit` on `ProjectExplorerPanel`'s hoisted `ExplorerEdit?` state — the
same state the previous section describes — so the menu and the inline field are two faces of one
mechanism: the menu picks *what* to edit, the field does the actual typing.

`Duplicate` sends immediately. `Delete` and `Move to…` instead open a dialog through
`ExplorerDialogState` (in `ExplorerDialogs.kt`), which is `remember`-ed in the panel for the same
mount-epoch reason as `ExplorerMenuState`. Each menu item calls `state.close()` *before* its
callback, so the menu layer is gone before any dialog opens — which is what keeps the dialogs a
single popup layer and clear of the nested-popup defect described next. Both dialogs reuse the
menu's `FixedOffsetPositionProvider` and its recorded anchor, so a dialog appears exactly where the
item that triggered it was.

- **Nested popups do not work in this scene: keep every menu one level deep.** The menu's two `New`
  actions are flat rather than a `New ▸ (Folder | Structure)` submenu, and that is forced, not a
  style choice. Jewel's `MenuScope.submenu { }` opens its flyout as a second popup layer with
  `PopupProperties(focusable = true)` — hardcoded inside Jewel's `internal fun Submenu`. The dock
  composes into an `ImageComposeScene`, i.e. a `CanvasLayersComposeScene`, and that scene's
  `processMove`/`processPress` gate every layer through `isInteractive(owner)`, which returns
  **false for every layer below the focused one**. So the moment the flyout opens, the parent menu
  card stops receiving pointer input entirely. Jewel clears a submenu row's selection from the
  *sibling* row's hover (`LaunchedEffect(isHovered) { deselectSubmenu() }`), and that hover never
  arrives: `New` kept its highlight indefinitely, `Rename` never highlighted, and a click on
  `Rename` only dismissed the flyout. Nothing at the call site can change this — both the
  `focusable` flag and the layer gating are internal. The same trap applies to any nested popup,
  dropdown-inside-a-menu, or tooltip-over-a-popup in this dock. `ExplorerUiSpec`'s "right-click a
  nested folder..." story pins the behaviour with a pixel probe on the hover bar (the hover step).
- **`ExplorerMenuState` (`target`, `anchor`) is `remember`-ed inside `ProjectExplorer()`, never a
  top-level `object`.** Same reasoning as the mount-lifecycle guards above: a popup layer belongs
  to the composition that opened it, and a global menu-state object would survive a panel
  re-mount and repaint over the next one.
- **Row-local pointer coordinates must be converted to window coordinates before anchoring the
  popup.** `PopupMenu`'s `popupPositionProvider` receives coordinates in the scene's (window)
  space, but the pointer event `TreeRow` observes only carries a position local to that row. Each
  `TreeRow` records its own origin via `Modifier.onGloballyPositioned { rowOrigin =
  it.positionInWindow() }` and adds it to the event's local position before calling
  `onSecondaryClick`. The scene renders full-window at `Density(1f)`, so window coordinates equal
  scene coordinates with no scale factor to apply. Skipping this step anchors every menu near the
  panel's left edge regardless of where the row actually sits.
- **Detecting the right-click needs `PointerButton`, not just click position** — see
  [dock-input-routing.md](dock-input-routing.md) for how `DockInputRouter.onGlfwPress` now carries
  real GLFW mouse-button values into the scene as `PointerButton.Secondary` and friends.
  `TreeRow`'s `Modifier.pointerInput(path) { awaitPointerEventScope { ... } }` filters for
  `PointerEventType.Press` with `event.button == PointerButton.Secondary`.  Reading
  `PointerEvent.button` is `@ExperimentalComposeUiApi` in this Compose version, hence the
  file-level `@file:OptIn(ExperimentalComposeUiApi::class)` on `ProjectExplorerPanel.kt`.
- **`ExplorerActions` is the validate-then-send seam** for `commitCreate`/`commitRename`, called
  from the inline field's `onCommit`. It re-runs `EditorNames.validate` against the client's own
  tree snapshot before sending a C2S packet — a pre-check, not a replacement for the server's own
  validation, since the client's snapshot can be stale. `ExplorerActions.sender` is swappable
  (mirrors `RootPickerController`'s pattern) so `ExplorerActionsTest` (`src/test/`) can assert on
  payloads without a live client or connection; `resetForTest()` restores the real
  `ClientPlayNetworking.send`.
- **`New` targets the clicked folder itself, or a clicked file's parent** — the IDE convention.
  `ExplorerContextMenu` resolves this by reading the live snapshot (`root.resolve(target)`) rather
  than guessing from the path string, since a folder name may legitimately contain a dot. `Rename`
  targets the clicked node directly and is disabled on `ExplorerTreeState.ROOT_PATH`, which has no
  parent to rename within.
- Opening the target folder for a `New` action (`ExplorerTreeState.treeState.openNodes += parent`)
  happens synchronously in the menu-item's `onClick`, for the same reason root-opening happens
  synchronously elsewhere in this file: the placeholder row only renders if the folder is already
  open by the time `LazyTree`'s prune runs.

## Keyboard delivery into Jewel widgets

Jewel's `LazyTree`/`TextField` consume real Compose key events (arrow-key navigation, typed text),
which only work because GLFW key and character callbacks are now wired end-to-end into the scene
(`DockInputRouter.onGlfwKey`/`onGlfwChar`, fed by a `charTyped` mixin injection) — see
[dock-input-routing.md](dock-input-routing.md) for the mixin targets and the AWT-`KeyEvent`
`nativeEvent` trick typed text requires.

Delivery into the scene is only half of it, though: a `TextField` **nested inside a `LazyTree` row**
loses most of its keys to the tree, because `SelectableLazyColumn` registers its keybindings with
`Modifier.onPreviewKeyEvent` on the tree container and preview events dispatch root → leaf. See
[explorer-toolbar-and-context-menu.md#the-tree-steals-the-name-fields-keys](explorer-toolbar-and-context-menu.md#the-tree-steals-the-name-fields-keys).
