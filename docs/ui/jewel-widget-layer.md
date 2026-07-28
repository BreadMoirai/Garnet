---
title: Jewel — the dock's widget layer
tags: [compose, jewel, dock, icons, popup, skiko, versions]
summary: The dock's IntelliJ-look widget layer. Covers the load-bearing Jewel/Compose/skiko version triple, why component icons need a separate artwork artifact, and the Jewel-authoritative tree-state model.
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
- `org.jetbrains.skiko:skiko-awt-runtime-windows-x64:0.144.6`

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

Jewel's `Dropdown` (used for the Explorer's root-folder menu) is built on Compose's own `Popup`.
The dock's `ImageComposeScene` is internally a `CanvasLayersComposeScene`, so popup layers draw
into the *same* raster canvas as the rest of the scene rather than opening a separate desktop
window — Jewel menus render in-scene with no extra plumbing. See
[dock-dialogs.md](dock-dialogs.md) for the full writeup (including the hand-rolled `RootMenu`
overlay this replaced) and why that used to be assumed impossible.

**The lifecycle is the sharp edge, not the rendering.** A popup layer belongs to the composition
that opened it, and the dock composes into a long-lived singleton scene that only advances during a
rendered frame. So an open `Dropdown` menu will happily outlive the panel that created it — repainting
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
  `TextFieldState`- and `TextFieldValue`-keyed overloads exist. The Explorer's "+ Structure" name
  field uses `rememberTextFieldState()` and the `TextFieldState.clearText()` extension rather than
  a plain `String` state hoist.

## Keyboard delivery into Jewel widgets

Jewel's `LazyTree`/`TextField` consume real Compose key events (arrow-key navigation, typed text),
which only work because GLFW key and character callbacks are now wired end-to-end into the scene
(`DockInputRouter.onGlfwKey`/`onGlfwChar`, fed by a `charTyped` mixin injection) — see
[dock-input-routing.md](dock-input-routing.md) for the mixin targets and the AWT-`KeyEvent`
`nativeEvent` trick typed text requires.
