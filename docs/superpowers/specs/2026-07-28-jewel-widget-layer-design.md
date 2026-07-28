# Jewel as the dock's widget layer — design

**Date:** 2026-07-28
**Status:** approved, ready for planning
**Predecessor:** `docs/ui/jewel-spike-findings.md` (on the throwaway branch `spike/jewel`; verdict GO)

## Problem

The dock's Project Explorer is hand-rolled Compose: a recursive `TreeNode` composable built from
`BasicText`/`Box(clickable)`, a hand-rolled `RootMenu` overlay, and a `BasicTextField` that can never
receive input because the dock never forwards keys into the Compose scene. This is the template every
future IDE panel (debugger, timeline, spec editor) would copy, so the widget layer needs to be real
before more panels exist.

The `spike/jewel` feasibility spike established that JetBrains **Jewel** (IntelliJ-look Compose
widgets) renders inside the embedded `ImageComposeScene`, takes routed pointer input, and — the
decider — renders `Popup`-based menus **in-scene** over the Blaze3D FBO. This document specifies the
real migration on `main`.

## Goals

1. Adopt Jewel as the dock's widget layer, on a Compose/skiko pin that Jewel resolves against.
2. Replace the hand-rolled Explorer tree with a Jewel `LazyTree`, preserving current behavior.
3. Close the two gaps the spike flagged: component icons and keyboard delivery.
4. Retire the hand-rolled `RootMenu` overlay, whose only reason to exist is now obsolete.
5. Keep the dock's OFF-by-default input guard intact — nothing renders or captures during normal play.

## Non-goals

- Converting panels other than the Explorer.
- Lazy/per-folder tree loading (tracked separately; the whole tree still arrives in one packet).
- Multi-root / "Attach Folder" (tracked separately; the menu item stays disabled).
- Solving how Compose/skiko/icons get bundled into a *production* mod jar. All are
  `clientImplementation` (dev classpath) today; that question predates this work and is unchanged by it.

## Decisions

### D1 — Version triple

Adopting Jewel moves a shared, ABI-load-bearing pin. Compose's transitive skiko must exactly match
the bundled `skiko-awt-runtime-windows-x64` native, so the three move together:

| Coordinate | From | To |
|---|---|---|
| `org.jetbrains.skiko:skiko-awt-runtime-windows-x64` | 0.150.1 | **0.144.6** |
| `org.jetbrains.compose.{runtime,ui,foundation}:*-desktop` | 1.12.0-beta02 | **1.11.0** (stable) |
| `org.jetbrains.jewel:jewel-int-ui-standalone` | — | **0.39.1-262.9437.29** |
| `com.jetbrains.intellij.platform:icons` | — | **262.8665.369** |

This moves Compose *down* one minor, from a beta to stable. The spike verified zero API drift:
`ComposeSceneHost` and `ComposeSurface` compile and run unchanged, and the Kotlin 2.3.20
compose-compiler plugin works against the 1.11.0 runtime.

All four are `clientImplementation`, never plain `implementation` — Compose stays out of the server
jar, and the existing compiler-plugin strip on `main`/`test`/`gametest` is untouched.

`ComposeSurface.ensureNativeLoaded` logs a hardcoded `"skiko 0.150.1"` string that becomes stale;
it is corrected to 0.144.6.

### D2 — Icons come from the IntelliJ platform artifact

**Verified against the jars, not assumed:**

- `jewel-ui` ships the complete `AllIconsKeys` catalog (`Actions`, `FileTypes`, `Nodes`, `Debugger`,
  `General`, `Gutter`, `RunConfigurations`, …) but **zero SVGs**.
- `jewel-int-ui-standalone` ships **45 SVGs** — the checkbox/radio/theme set only.
- `LazyTreeIcons` takes four `IconKey`s. `IconKey` is `{ getIconClass(): Class<?>; path(Boolean): String }`
  — a classloader resource path resolved via `ResourcePainterProvider`. **There is no
  `Painter`/`ImageVector` seam**, so a Compose `ImageVector` cannot be supplied to `LazyTree`.
- `com.jetbrains.intellij.platform:icons:262.8665.369` is **4.4 MB / 3660 SVGs** and contains exactly
  the paths Jewel's keys request (`expui/general/chevronRight.svg`, `chevronDown.svg`,
  `expui/nodes/folder.svg`, `expui/general/refresh.svg`, …). Its 262 branch matches our Jewel build.

Jewel's design is deliberately *keys in the library, artwork in the IntelliJ icons artifact*. Adding
the artifact is therefore the native path and resolves icons for the entire future IDE UI, not just
the tree chevrons.

**Alternatives rejected:**

- *Compose Material Icons* — `org.jetbrains.compose.material:material-icons-core` was discontinued at
  **1.7.3**; there is no 1.11.0 release (HTTP 404). Pinning 1.7.3 would drag an old transitive skiko
  onto the ABI-load-bearing pin, requiring `isTransitive = false` on a dead artifact.
- *`compose-resources` (`Res.drawable.*`)* — requires the `org.jetbrains.compose` Gradle plugin, which
  this project deliberately avoids to keep from fighting Loom/Stonecutter source-set and run wiring.
- *Hand-authored SVGs + `PathIconKey` overrides* — works, but solves only the chevrons and leaves the
  same problem unsolved for every subsequent icon.
- *`BasicLazyTree` + hand-declared `ImageVector` chevrons* — the only route that accepts an
  `ImageVector`, but forfeits `LazyTreeStyle` theming and requires passing ~20 explicit
  colour/metric/padding parameters.

`settings.gradle.kts` gains one repository: `https://www.jetbrains.com/intellij-repository/releases`.

### D3 — Jewel's `TreeState` is the single source of truth

`TreeState` is publicly constructible (`TreeState(SelectableLazyListState)`) with settable
`openNodes: Set<Any>` and `selectedKeys: Set<Any>`; `TreeGeneratorScope.addNode`/`addLeaf` take an
explicit `id: Any`. Using the node's `/`-joined path as that id makes **selection keys literally path
strings**, so no translation layer is needed.

Therefore:

- `ProjectTreeState` retains **only** `snapshot`, `status`, and the S2C packet handlers. Its
  `expanded` list and `selectedPath` field are **deleted** — no mirrored state.
- A hoisted `ExplorerTreeState` holder owns the single `TreeState` instance, so non-composable callers
  (networking-driven actions, clientTests) can read and write it outside composition.
- `selectedHasUnsaved()` becomes a derived query over `selectedKeys.firstOrNull()` + `snapshot`
  rather than stored state.
- clientTests assert against `treeState.selectedKeys` / `treeState.openNodes`.

Rejected: keeping `ProjectTreeState` authoritative with a bidirectional sync shim (two models to keep
consistent), and splitting ownership (expand in Jewel, selection in `ProjectTreeState`).

### D4 — The whole Explorer panel converts

Mixing raw `BasicText`/`BasicTextField` with Jewel widgets in one panel reads as half-migrated, and
this panel is the template future panels copy. All four parts convert under one
`IntUiTheme(isDark = true)`:

| Part | Before | After |
|---|---|---|
| Header root button | `Box(clickable)` + `RootMenu` overlay | Jewel `Dropdown` |
| Header refresh | `BasicText("↻")` | `IconButton(AllIconsKeys.Actions.Refresh)` |
| Structure actions | `BasicTextField` + pseudo-buttons | `TextField` + `DefaultButton`/`OutlinedButton` |
| Tree | recursive `TreeNode` composable | `LazyTree` (default `LazyTreeStyle`) |

`RootPickerController.menuOpen` / `toggleMenu()` / `closeMenu()` are deleted — `Dropdown` owns its own
open state. `openFolder()` and its injectable seams (`picker`, `runner`, `executor`, `sender`,
`persist`) are unchanged, so `RootPickerSpec`'s coverage of the picker flow survives.

Behavior preserved exactly: clicking a `.nbt` file selects it and sends `PlaceStructureC2S`; clicking
a spec-folder sends `LoadProjectFolderC2S`; the current-subpath marker, the dirty (`hasUnsaved`)
marker, expand/collapse, and the Save/Discard enablement rule all carry over.

### D5 — Keyboard forwarding

Compose 1.11 desktop exposes a synthetic constructor —
`KeyEvent(key, type, codePoint, isCtrl/isMeta/isAlt/isShift, nativeEvent)` — so forwarding does **not**
require fabricating a `java.awt.event.KeyEvent`. A GLFW→`Key` table suffices.

- New `GlfwKeyMap`: GLFW key code → `androidx.compose.ui.input.key.Key`.
- `DockInputRouter.onGlfwKey(key, action, mods)` keeps the ESC special-case **first** (unchanged
  contract: returns `true` so the mixin cancels vanilla handling), then builds a `KeyEvent` of type
  `KeyDown`/`KeyUp` and calls `ComposeSurface.sendKey`.
- New `DockInputRouter.onGlfwChar(codePoint)` plus a `charTyped` injection on `KeyboardHandlerMixin`,
  so the new `TextField` receives text.

Note this fixes pre-existing broken behavior: the current `BasicTextField` can never receive input,
because keys never reach the scene at all.

The OFF-by-default guard is unchanged — everything stays behind `captured`
(`DockState.focusedRegion != null`), so uncaptured input remains byte-for-byte vanilla.

### D6 — Retire the hand-rolled popup overlay

The spike proved `ImageComposeScene` is internally a `CanvasLayersComposeScene` on Compose 1.11, so
`Popup` layers draw into the same canvas rather than a separate OS window. Since we are *moving to*
1.11, the result applies directly. `RootMenu` is deleted only **after** a screenshot in the new spec
re-confirms an in-scene `Dropdown` on this branch.

## Verification

`JewelExplorerSpec` (clientTest, registered in `ClientTestSentinel` — autoscan is off, unregistered
specs silently do not run). It drives the real router→`ComposeSurface`→scene path and screenshots the
composite via `ViewportState.compositeCaptureRequest`:

1. Tree renders, and chevrons **paint rather than showing magenta placeholders**.
2. A routed pointer click selects a row (`treeState.selectedKeys` updates).
3. An arrow key changes selection — end-to-end proof of the new key path.
4. The root `Dropdown` opens **in-scene** (the popup re-confirmation gating D6).

Hard gate throughout: `ComposeSurface.disabled` stays `false`. Any font/icon resource-load failure
during composition throws inside `host.render()`, which `renderFrame` converts into a permanent
disable — so a green run is itself the evidence that resources loaded under Fabric's Knot classloader.

Full compile gate across all five source sets:

```
cmd.exe /c "gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"
```

Gradle `--tests` filtering does not work with Kotest here; scope by editing the sentinel's spec list
and read results from the log or `build/reports/garnet/clientTest`.

## Increments

Each lands as its own commit, green before the next starts.

1. **Build re-pin.** Repo + four coordinates + the stale skiko log string. Gate: five source sets
   compile *and* the existing `DockRenderSpec`/`ProjectExplorerSpec` still pass, before any UI change.
2. **Keyboard forwarding.** `GlfwKeyMap`, `onGlfwKey`/`onGlfwChar`, the `charTyped` mixin. Gate:
   existing `DockInputSpec` ESC-policy cases still pass.
3. **Explorer conversion.** D3 + D4 + D6, and `JewelExplorerSpec`. Gate: the four checks above.
4. **Docs.**

## Documentation impact

Mandatory per `CLAUDE.md` after source changes.

- `docs/ui/dock-dialogs.md` — its central premise ("the embedded `ImageComposeScene` can't host a
  Compose `Popup`") is **false** on 1.11. Rewrite: popups render in-scene, `RootMenu` retired, and the
  hand-rolled-overlay guidance withdrawn. The native-dialog-threading half stays valid.
- `docs/ui/dock-input-routing.md` — the "key→Compose delivery is deferred" section is superseded.
- `docs/ui/dock-framework.md` — the Explorer-as-template section now describes Jewel widgets.
- `docs/tooling/compose-runtime-scoping.md` — the Compose pin narrative changed (beta → stable, and
  why the triple moves together).
- `docs/architecture/redstone-project.md`, `docs/use-cases/redstone-project.md`,
  `docs/use-cases/structure-lifecycle.md`, `docs/use-cases/command.md` — all cite `TreeNode` /
  `ProjectTreeState` internals that change.
- `docs/ui/compose-in-mc-feasibility.md` — version references.
- **New** `docs/ui/jewel-widget-layer.md` — carries the durable spike findings onto `main` (the triple,
  the icon-packaging rule, the in-scene popup verdict), since `jewel-spike-findings.md` lives only on a
  throwaway branch.
- `docs/ui/INDEX.md` — register the new article, adjust changed summaries.

`grep -rn "TreeNode\|RootMenu" docs/` must return no hits describing their old roles.
`docs/superpowers/` specs and plans are commit-time snapshots and are left as-is.

## Risks

- **The Compose pin is shared and ABI-load-bearing.** A skiko mismatch fails at native load or a
  version guard. Mitigated by increment 1 being its own gated commit, and by the spike having already
  run this exact triple end-to-end.
- **The icons artifact adds a non-Central repository.** Availability is now a build dependency on
  `jetbrains.com`. Verified reachable (HTTP 200 after redirect).
- **Icon-artifact classpath collisions** with MC/Fabric (~108 non-SVG entries in the jar). Surfaces at
  increment 1's compile gate.
- **`ImageComposeScene` remaining canvas-layers.** If a future Compose bump reverted to
  platform layers, the retired `RootMenu` would need re-hand-rolling. The `Dropdown` screenshot check
  in `JewelExplorerSpec` is the regression guard.
