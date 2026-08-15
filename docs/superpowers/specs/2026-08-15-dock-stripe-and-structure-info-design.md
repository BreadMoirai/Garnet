# The dock stripe and the Structure Info panel

**Date:** 2026-08-15
**Status:** design approved, not yet implemented

## Context

The dock (`ui/dock/`) today models visibility per **region**: `DockState.leftVisible` and friends
say whether an edge is open, and every panel registered to that region shares it through
`DockTabStrip`. Two panels exist, both in LEFT — the Project Explorer and Local History — and the
only way to open or close the dock is the `Alt+1` / `Shift+1` keybinds in `viewport/DockKeybinds.kt`.

Structure metadata has no home. `ProjectTreeState.status` is a single string that the Explorer
renders as a bottom line, and five different network receivers write to it: three write transient
feedback (`"error: …"`, `"saved 3 spec(s)"`, `"loaded x (2 specs)"`) and two write facts about the
open structure (`"placed a/box.nbt"`, `"auto-saved x.nbt (5×3×7, 42 blocks)"`). Conflating them
means the structure's size and block count are destroyed by the next unrelated error.

This spec does two things at once because they are the same change seen from two sides: splitting
structure info into its own panel is what makes a third LEFT panel exist, and three panels sharing
one region is what makes a JetBrains-style tool-window stripe the right control instead of a tab
strip.

Related: `docs/superpowers/specs/2026-07-31-structure-autosave-local-history-design.md` listed a
"structure info panel" as sub-project 3 of three. This spec is that sub-project, plus the stripe.

## Scope

Client UI only. No packet is added, changed, or removed; no server code is touched. The dock's
geometry model (splitters, the framebuffer shrink, the Compose scene) is unchanged apart from the
stripe's contribution to `DockInsets.left`.

## 1. `DockState`: panel visibility replaces region visibility

`Panel` gains the region it belongs to and the icon that represents it in the stripe:

```kotlin
class Panel(
    val id: String,
    val title: String,
    val region: DockRegion,
    val icon: IconKey,
    val content: @Composable (Panel) -> Unit,
)
```

The four per-region panel lists collapse into one flat registry:

```kotlin
val panels: SnapshotStateList<Panel>
fun panelsFor(region: DockRegion): List<Panel> = panels.filter { it.region == region }
```

That filter is not a convenience — it *is* the stripe's icon list for a region, so there is exactly
one definition of "which panels does this region have".

Region visibility becomes derived rather than stored:

```kotlin
private val openPanel = mutableStateMapOf<DockRegion, String>()   // region -> open panel id

fun openPanelId(region: DockRegion): String? = openPanel[region]
fun isVisible(region: DockRegion): Boolean = openPanel[region] != null
```

with one mutator, which is the whole interaction model:

```kotlin
/**
 * Show [id]'s panel, evicting whatever its region had open; if it is already the open one,
 * close the region instead. Unknown ids are ignored.
 */
fun togglePanel(id: String)
```

**Deleted:** `leftVisible`, `rightVisible`, `bottomVisible`, `setVisible`, `toggleVisible`,
`leftActiveTab`, `rightActiveTab`, `bottomActiveTab`, `centerActiveTab`, `activeTab`,
`setActiveTab`, and the four separate panel lists. `DockTabStrip.kt` is deleted outright — with one
panel open per region it has nothing to render.

`anyActive()` keeps its meaning (`openPanel.isNotEmpty() || focusedRegion != null`) and keeps its
job of driving `syncDockViewport`.

### CENTER is unified, not special-cased

CENTER joins the same model. It gets no stripe icons (nothing registers a CENTER panel today; only
`DockHitTestTest` and `DockLifecycleTest` add one), and `openPanel[CENTER]` follows the most
recently added center panel — adding a center panel opens it, removing the open one falls back to
whatever remains or closes the region.

The alternative — leaving `centerPanels` and `centerActiveTab` on the old model — was rejected: it
keeps half the pre-stripe API alive for the one region that has no users, and leaves two answers to
"is this region visible".

`isVisible(CENTER)` therefore stops being `centerPanels.isNotEmpty()` and becomes the same
`openPanel[CENTER] != null` as everywhere else. `DockHitTest.regionAt` and
`DockState.closeAll()` both read through `isVisible`/the registry rather than `centerPanels`
directly.

### Mount epochs move from region to panel

`DockState.mountEpoch` exists so a panel body cannot outlive its mount — the ghost-popup failure
mode documented on that field. Today the epoch is bumped when a **region is hidden**. It must now be
bumped whenever a **region's open panel changes**, including a switch from one panel to another.

This is strictly more correct than today's rule, and fixes a live bug: switching Explorer → Local
History currently reuses the region's body slot without a bump, so a popup layer opened in the
Explorer can survive the swap and paint over Local History. `GarnetDock` already includes the panel
id in its `key()`, which mitigates but does not fix this, because the leaked `Popup` was added to
the *scene*, not to the keyed subtree.

## 2. The stripe

New file `ui/dock/DockStripe.kt`.

A 32px-wide vertical column at `x = 0`, spanning the full region height, rendered by `GarnetDock`
**only when `DockState.anyActive()`** — so a closed dock costs the world zero pixels.

```
NOTHING OPEN                      EXPLORER OPEN
+-----------------------------+   +--+-----------+---------------+
|                             |   |[E]| Explorer  |               |
|      world (full width)     |   |[H]|   tree    | world (shrunk)|
|                             |   |[i]|           |               |
+-----------------------------+   +--+-----------+---------------+
 no stripe                          32     280
```

Icons are `panelsFor(LEFT)` in registration order, top-aligned, drawn with Jewel's `Icon` at the
panel's `icon` key. The open panel's icon carries a selected background, matching the tab-active
colour the deleted `DockTabStrip` used (`0xFF2B2D30`). Clicking an icon calls
`DockState.togglePanel(panel.id)`.

Hand-rolled over `Box`/`pointerInput`, for the same reason `DockTabStrip` was: this layer sits
underneath the scene's layer routing, which is the subtlest thing in the package, and a Jewel
component here would pull focus-and-popup behaviour into it.

**Only a LEFT stripe is built.** No panel is registered to RIGHT or BOTTOM, so their stripes would
render empty. `DockStripe` takes the region as a parameter and is region-general; the RIGHT and
BOTTOM stripes get added when a panel needs them.

### Geometry and draw order

The stripe spans the **full window height** and is drawn **last**, on top of everything else. The
LEFT column shifts right by `STRIPE`; the BOTTOM band keeps its full width and simply passes under
the stripe. LEFT and RIGHT keep stopping at `realH - bottom`, as today.

Drawing the stripe last is what lets `regionAt` test it first — `DockHitTest`'s z-order is required
to stay in lockstep with `GarnetDock`'s draw order, and the stripe is the one thing that must win
its column at every height, including inside the BOTTOM band.

### Insets and hit-testing

`DockInsets`:

```kotlin
left = (if (anyActive()) STRIPE else 0) + (if (isVisible(LEFT)) leftWidth else 0)
```

The stripe term is gated on `anyActive()`, not on `isVisible(LEFT)` — the stripe is visible whenever
*any* region is open, which is what lets a user with only BOTTOM open still see the LEFT icons.

`DockHitTest.regionAt` gains the stripe's x-range, mapped to `LEFT`, so clicking an icon also
focuses the panel it opens. The stripe is tested **before** the LEFT strip and before the
full-width BOTTOM band, so the stripe wins its own column at every height — otherwise BOTTOM's band
would swallow the bottom of the stripe and a click there would focus the wrong region.

### Accepted consequence: the stripe can vanish

Closing the last open panel makes `anyActive()` false, so the stripe disappears and the only way
back is `Shift+1`. This is inherent to "stripe only while the dock is active" and is accepted:
`applyDockAutoOpen()` opens the Explorer on every Garnet world join, so a fully-closed dock is a
deliberate act, and the keybind that closed it also reopens it.

## 3. The Structure Info panel

New files `editor/ui/StructureInfoState.kt` and `editor/ui/StructureInfoPanel.kt`. Third icon in
the LEFT stripe, id `garnet.structureInfo`.

`ProjectTreeState.status` is **deleted**. `ProjectTreeState` keeps only `snapshot` and
`onSnapshot`; its four status-writing receivers (`onFolderLoaded`, `onSaveReport`, `onError`,
`onStructureResult`) and `onAutoSaved` repoint at `StructureInfoState`. `EditorClientNetworking`'s
registrations move with them.

`StructureInfoState` holds fields, not a baked string:

```kotlin
object StructureInfoState {
    var subpath: String?         // null = no structure open
    var sizeX: Int; var sizeY: Int; var sizeZ: Int
    var blockCount: Int
    var lastSavedMillis: Long    // 0 = never saved this session
    var status: String           // the transient line, moved wholesale
    fun reset()
}
```

- `StructureAutoSavedS2C` fills `subpath`, the sizes, `blockCount`, and stamps `lastSavedMillis`.
- `StructureResultS2C` fills `subpath` and `status` (its `message`), and clears the size/block
  fields — a newly placed structure has no auto-save report yet, and showing the previous
  structure's dimensions under the new one's name would be a lie.
- The three feedback receivers write only `status`.
- `reset()` is called from the same disconnect path that resets `OpenStructureState`.

`lastSavedMillis` is stamped **client-side at packet receipt**, not carried in the payload. This
keeps the change to zero protocol edits; the cost is that it is receipt time rather than server
write time, which for a local integrated server is a sub-frame difference and for a remote one is
one network hop. If that ever matters, `StructureAutoSavedS2C` grows a timestamp field then.

Panel body, `IntUiTheme(isDark = true)` and the shared `PANEL_BG` like the other two:

```
+-------------------------+
| redstone/clock.nbt      |
|                         |
| Size    5 x 3 x 7       |
| Blocks  42              |
| Saved   12:04:31        |
|                         |
| error: bad name         |   <- status, when non-empty
+-------------------------+
```

Empty state when `subpath == null`: a plain `"no structure open"` line, and no field rows.

Two conventions inherited from `LocalHistoryPanel`, both load-bearing:

- **No glyphs anywhere.** Jewel's default family is Inter, which has no emoji coverage, and a marker
  glyph falls through to whatever Skia finds on the host — tofu wherever no system emoji font is
  installed. `Size` uses ASCII `x`, not `U+00D7`, for the same reason (`local-history-panel.md`).
- **No panel-local state parked in a top-level object.** Anything panel-local is `remember`-ed
  inside the composable, or it survives a re-mount and paints over the next one.

Time formatting reuses the `SimpleDateFormat` pattern `LocalHistoryPanel` already uses, extracted to
a shared helper rather than duplicated.

### `editError` stays in the Explorer

The one exception to "move the whole status line". `ExplorerEdit`'s validation failure is currently
rendered in the same shared bottom line; it moves **inline**, directly under the name field in the
tree row, rather than into Structure Info.

Reason: Structure Info may be closed. A rename that fails with the panel closed would leave the user
looking at a red `Outline.Error` border with no reason given anywhere on screen. Rendering it at the
field is also better than today, where the message already sits a panel-height away from the field
it describes.

The Explorer's bottom `Text` is removed; `ProjectExplorerPanel` no longer reads any status.

## 4. Persistence

`config/garnet-dock.json` currently stores `{"leftVisible": true}`. It becomes a per-region record
of which panel is open:

```json
{"open": {"LEFT": "garnet.explorer"}}
```

`DockLayoutStore.load()` returns `Map<DockRegion, String>` and `save(open: Map<DockRegion, String>)`
writes it. Three rules, all following the existing "a bad record falls back to the wanted default"
policy on that object:

- **Legacy migration.** A file with `leftVisible` and no `open` key reads as
  `{LEFT: "garnet.explorer"}` when true and `{}` when false. One release of migration code; the file
  is rewritten in the new shape on the next save.
- **Unknown panel id** reads as that region closed, so removing a panel in a future version cannot
  wedge the file.
- **Unknown region name** is dropped.
- Absent, unreadable, or malformed file falls back to `{LEFT: "garnet.explorer"}` — the same
  "open the dock for a fresh install" default `load()` has today.

`applyDockAutoOpen()` applies the loaded map instead of setting `leftVisible`, and keeps both its
existing decisions unchanged: gated on `DockAutoOpenGate.isGarnetServer()`, and visibility-only —
it never takes focus.

## 5. Keybinds

`viewport/DockKeybinds.kt` keeps both bindings and their feel; only the call changes.

- **Shift+1** — `DockState.togglePanel("garnet.explorer")`. Opens the Explorer, or closes LEFT if
  the Explorer is already the open panel there. Note the behaviour change when Local History is the
  open panel: Shift+1 now *switches to* the Explorer rather than closing the region. This is the
  correct reading of a per-panel toggle and matches what the stripe icon does.
- **Alt+1** — focus LEFT, opening the Explorer first if LEFT has nothing open; a second press drops
  focus, as today.

Both still persist through `DockLayoutStore.save` on the keypress rather than at disconnect, for the
reason documented in that file: `closeAll()` hides regions on the same event and a disconnect-time
save would race Fabric's handler ordering.

`closeAll()` keeps its narrower-than-`reset()` contract, restated for the unified registry: it
clears `openPanel` entirely, drops focus, and **removes CENTER panels from the registry** — those
are per-world documents that mean nothing without the session that opened them. LEFT/RIGHT/BOTTOM
panels stay registered and splitter sizes stay put, because those are user layout rather than world
state, and the Explorer is only ever registered at `onInitializeClient` — a full `reset()` here
would leave LEFT permanently empty for the rest of the process.

Clearing `openPanel` is now what bumps the mount epochs, so the separate CENTER epoch bump that
`closeAll()` does today (CENTER never went through `setVisible`) disappears with `setVisible`
itself.

## 6. Testing

**Unit (`src/test`), no render context:**

- `DockPanelVisibilityTest` — `togglePanel` opens, switches within a region, closes on re-click of
  the open panel, ignores unknown ids, and leaves other regions untouched.
- `DockMountEpochTest` — the epoch bumps on a panel *switch*, not just on a close.
- `DockHitTestTest` — extended: the stripe's x-range maps to LEFT at every height including inside
  the BOTTOM band; no stripe range exists when `!anyActive()`.
- `DockInsetsTest` — the stripe width is included exactly when the stripe renders: absent with
  nothing open, present with only BOTTOM open, present and additive with LEFT open.
- `DockLayoutStoreTest` — new-shape round trip, legacy `leftVisible` migration in both directions,
  unknown panel id, unknown region, malformed file.
- `StructureInfoStateTest` — each of the five receivers lands the right fields; `StructureResultS2C`
  clears the stale size/block fields. This supersedes `StructureExplorerStatusTest`, which asserts
  on the deleted `ProjectTreeState.status`.

**Migrated off the deleted API.** These already exist and touch members this spec removes; each is
updated in place rather than rewritten:

- `DockTabStateTest` — asserts on `activeTab`/`setActiveTab`. Replaced by `DockPanelVisibilityTest`.
- `DockLifecycleTest` and `DockHitTestTest` — both add a panel via `centerPanels` and assert on it;
  they move to the flat registry with `region = CENTER`.
- `JewelExplorerSpec` — seeds LEFT panels via `leftPanels.add`, likewise.

**Scene (`src/clientTest`), real `ImageComposeScene`:**

- A stripe-icon click swaps the rendered panel body. `JewelExplorerSpec.kt:259` currently tests a
  `DockTabStrip` click for this and is rewritten to the stripe equivalent; its comment about "two
  panels is what makes DockTabStrip render at all" goes with it.
- The Structure Info panel renders its fields for a seeded `StructureInfoState`, and its empty state
  for a cleared one.

**Icon verification.** Each stripe `IconKey` must be confirmed to actually render. Per
`docs/ui/jewel-widget-layer.md`, `jewel-ui` ships the whole `AllIconsKeys` catalog but no SVGs, and
artwork comes only from the separate `com.jetbrains.intellij.platform:icons` artifact — so a key
that compiles can still draw nothing. Candidates to verify at implementation time:
`AllIconsKeys.Toolwindows.ToolWindowProject` (Explorer),
`AllIconsKeys.Vcs.History` (Local History), `AllIconsKeys.General.Information` (Structure Info).

## 7. Documentation

Per `CLAUDE.md`'s keep-docs-in-sync rule:

**New articles**, both registered in `docs/ui/INDEX.md`:

- `docs/ui/dock-stripe.md` — the stripe, the per-panel visibility model that replaced per-region
  visibility, why the stripe is gated on `anyActive()` and the dead end that creates, and its
  contributions to `DockInsets` and `regionAt`.
- `docs/ui/structure-info-panel.md` — the panel, the `ProjectTreeState.status` split, why
  `lastSavedMillis` is a client stamp, and why `editError` stayed behind in the Explorer.

**Updated:**

- `docs/ui/dock-framework.md` — the tab-strip and region-visibility passages are now wrong; the
  mount-epoch section's trigger changes from "region hidden" to "open panel changed".
- `docs/ui/dock-input-routing.md` — the hit test gains the stripe; the Shift+1 semantics change.
- `docs/ui/local-history-panel.md` — "tabbed beside the Project Explorer" is no longer true.
- `docs/ui/explorer-toolbar-and-context-menu.md` — the status line it references is gone and
  `editError` renders inline.
- `docs/ui/INDEX.md` — the `dock-framework.md` and `local-history-panel.md` summaries both describe
  the tab strip.

Superpowers `specs/` and `plans/` are commit-time snapshots and are left alone.

## Out of scope

- RIGHT and BOTTOM stripes (no panel is registered there).
- Drag-and-drop of a panel between regions.
- Persisting splitter sizes across restarts — still process-lifetime state, as today.
- Entity count in the Structure Info panel: no packet carries it, and adding one is a protocol
  change this spec deliberately avoids.
