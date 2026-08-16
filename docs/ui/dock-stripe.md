---
title: The dock stripe — per-panel visibility
tags: [compose, dock, stripe, panels, layout, input, jewel]
summary: The JetBrains-style icon stripe that replaced the dock's tab strip, the per-panel visibility model behind it, why it is gated on anyActive() and vanishes with the dock, and why it is drawn last and hit-tested first.
---

# The dock stripe — per-panel visibility

`DockStripe.kt` (`src/client/kotlin/.../ui/dock/DockStripe.kt`) is a JetBrains-IDE-style icon column,
one icon per LEFT panel, that replaced `DockTabStrip.kt` (deleted). It renders at `STRIPE_WIDTH = 32`
real framebuffer px, full window height, at `x = 0`. This article covers the visibility model behind
it and the two draw/hit-test decisions that keep it usable; see [dock-framework.md](dock-framework.md)
for the rest of the dock and [structure-info-panel.md](structure-info-panel.md) for the panel that
gave the stripe its third icon.

## Per-panel visibility replaced per-region visibility

`DockState` used to hold four independent visibility flags (`leftVisible`/`rightVisible`/
`bottomVisible`, CENTER derived from `centerPanels.isNotEmpty()`) plus a per-region `activeTab: Int`
into a per-region `SnapshotStateList<Panel>` (`leftPanels`/`rightPanels`/`bottomPanels`/
`centerPanels`). That whole shape is gone. `DockState` now holds:

- **`panels: SnapshotStateList<Panel>`** — every registered panel, flat, in registration order. A
  `Panel` carries its own `region` and `icon` (see `Panel.kt`); `DockState.panelsFor(region)` derives
  the per-region list by filtering on that field, so there is exactly one definition of "which panels
  does this region have" — the one `DockStripe` renders. There is no second per-region list to drift
  out of sync with it.
- **`openPanel: Map<DockRegion, String>`** (private) — which panel id is open in each region.
  Absence means the region is closed, so this map is the *only* source of visibility. There is no
  separate `leftVisible` flag it could disagree with. Read through `openPanelId(region)`,
  `openPanelOf(region)` (the panel itself, or null), and `isVisible(region)` (`openPanelOf(region) !=
  null`).
- **`showPanel(id)`** — opens `id`'s panel, evicting whatever its region had open. A no-op if `id` is
  already open (so auto-open on join and a held keybind cost no churn), and a no-op for an unknown id
  (a panel can be removed between versions while its id survives in `garnet-dock.json`).
- **`closeRegion(region)`** — clears a region's open entry. Idempotent.
- **`togglePanel(id)`** — the stripe's click and the keybinds' shared entry point: show `id`, or close
  its region when `id` is already the open panel there. "Click the lit icon to close" is what makes a
  stripe a stripe rather than a row of radio buttons.
- **`openMap()`/`applyOpenMap(map)`** — the persistable layout (see "Persistence" below).

One semantic shift worth recording for whoever adds the first CENTER-document feature:
`isVisible(CENTER)` used to be `centerPanels.isNotEmpty()` — true the instant a document was
*registered*. It is now `openPanelOf(CENTER) != null` — true only after an explicit `showPanel` call.
Nothing registers a CENTER panel today, so this is not a regression, but a future CENTER feature has
to remember to call `showPanel`, not just append to `panels`.

## Rendering: one icon per LEFT panel, open one highlighted

`DockStripe(region, modifier, onVisibilityChanged)` reads `DockState.panelsFor(region)` and returns
early (renders nothing) if it's empty. Each panel gets a 28dp tappable `Box` with its `icon` (a Jewel
`IconKey`), highlighted with `ICON_SELECTED_BG` when it is the region's open panel.

A tap calls `stripeIconClicked(panel.id, onVisibilityChanged)` — `togglePanel` **plus** the mandatory
follow-up. See
[dock-input-routing.md](dock-input-routing.md#every-visibility-change-ends-in-commitdockvisibilitychange)
for why the toggle alone leaves the world blitted stretched, and why the follow-up arrives as a
callback threaded down from `GarnetDock` rather than being called here (it keeps `ui/dock` free of
`Minecraft` imports, which is what makes `DockState` and the click behaviour unit-testable).

Only `DockRegion.LEFT` is wired into `GarnetDock` today — RIGHT/BOTTOM have no stripe yet, though the
composable takes a `region` parameter and nothing about it is LEFT-specific.

Hand-rolled over `Box`/`pointerInput` rather than built from a Jewel `IconButton`, for the same reason
`DockTabStrip` was before it: this sits underneath the scene's layer routing (see
[jewel-widget-layer.md](jewel-widget-layer.md)), and a focusable Jewel component here would pull
focus-and-popup behaviour into the subtlest part of this package.

### Icon keys verified to render

Three `IconKey`s are in use: `AllIconsKeys.Toolwindows.ToolWindowProject` (Explorer),
`AllIconsKeys.Vcs.History` (Local History), `AllIconsKeys.General.Information` (Structure Info). No
substitution was made for any of them — all three are exactly what the panels ask for. Verification
happened in two passes:

- **Static resource check** (Task 4, before the Structure Info panel existed): the icons jar
  (`com.jetbrains.intellij.platform:icons:262.8665.369`) was disassembled to read the `path` argument
  each `IntelliJIconKey` constructor call resolves to (`toolwindows/toolWindowProject.svg`,
  `vcs/history.svg`), then confirmed present in the jar's entry list. This proves the lookup resolves
  to a real jar entry, not that the SVG rasterizes correctly.
- **Live pixel-diff run** (`:26.2:runClientTest`): two separate probes in `JewelExplorerSpec`, both
  reading the real Skia raster of the composite, not a jar lookup.
  - *Panel bodies swap.* The "stripe panel probe" and "structure info probe" cases diff the LEFT
    panel **body** (`BODY_XS` starts at `STRIPE_WIDTH + 4`, i.e. it deliberately excludes the stripe
    column) across a panel switch, confirming the body genuinely repaints.
  - *The stripe's own pixels.* The stripe-click case samples the icon boxes inside `x ∈ [0, 32)` via
    `PanelPixelProbe.stripeIconDiffCount`, driving a **real pointer event** into box 1 through
    `DockInputRouter` rather than calling `togglePanel` programmatically. It asserts that the idle
    icon box is non-empty (so the `IconKey` rasterized to actual artwork rather than nothing) and
    that the `ICON_SELECTED_BG` highlight moves from box 0 to box 1 when the click lands. That probe
    was added in the final fix wave: before it, no test on this branch sampled a single pixel with
    `x < STRIPE_WIDTH` or routed a pointer event into the stripe, so the earlier wording here —
    which claimed icon rendering had been confirmed "after clicking a stripe icon" — described a
    verification that had not happened.

## Why the stripe is gated on `anyActive()`, and the dead end that creates

`DockStripe` is rendered by `GarnetDock` only while `DockState.anyActive()` (any region open, or a
region focused) — see `GarnetDock.kt`'s `if (stripe > 0) DockStripe(...)`. The consequence: closing
the last open panel makes the stripe itself vanish, leaving **`Shift+1` as the only way back** — there
is no icon left to click.

This is deliberate, not an oversight. The alternative — an always-present stripe, regardless of
whether anything is open — permanently narrows the world viewport by `STRIPE_WIDTH` even for a player
who has closed every panel and wants the full screen back. Trading that permanent cost for a
one-keybind recovery path was judged the better trade. It is acceptable specifically because
`applyDockAutoOpen()` (see [dock-framework.md](dock-framework.md#left-auto-opens-on-joining-a-garnet-capable-world))
opens a panel on every Garnet world join — a fully-closed dock is a state the player has to reach
deliberately by closing everything, not one they can be stuck in from the moment they log in.

`DockInsets.left` mirrors this: it adds `STRIPE_WIDTH` whenever `anyActive()`, not only when LEFT is
open — the stripe reserves world space independently of which region(s) happen to be showing, since
its whole point is to stay reachable while, say, only BOTTOM is open.

## Why the stripe is drawn last and hit-tested first — one decision, not two

`GarnetDock` renders `DockStripe(DockRegion.LEFT, ...)` as the **last** child of the root `Box`, after
LEFT/RIGHT/BOTTOM/CENTER. Compose paints children in declaration order, so drawing it last means it
paints *over* whatever the LEFT column or the BOTTOM band would otherwise show at `x < STRIPE_WIDTH` —
including the bottom-left corner, where BOTTOM's full-width band would otherwise reach `x = 0`.

`DockState.regionAt` (`DockHitTest.kt`) tests the stripe **first**, before BOTTOM's full-width band
check, attributing any point in its column to `DockRegion.LEFT`:

```kotlin
if (stripe > 0 && x < stripe) return DockRegion.LEFT
if (bottom > 0 && y >= realH - bottom) return DockRegion.BOTTOM
...
```

These are not two independent choices that happen to agree — they are the same decision stated twice,
once in paint order and once in test order, and they are required to stay in lockstep. `DockHitTest`'s
z-order exists to answer "who owns this pixel", and the only correct answer is "whoever's paint is on
top of it". If the hit test tested BOTTOM before the stripe, a click in the bottom-left corner would
be attributed to BOTTOM (mis-routing input to whatever's rendered *underneath* the stripe there) even
though the pixel the player looked at and clicked shows a stripe icon. Any future change to
`GarnetDock`'s draw order — reordering a `Box` child, adding a new overlay — has to make the matching
change to `regionAt`'s test order, and `DockHitTestTest`/`DockStripeGeometryTest` exist specifically to
catch a drift between the two.

## Persistence: `garnet-dock.json` moved from a boolean to an open-panel map

`DockLayoutStore` (`config/garnet-dock.json`) used to store `{"leftVisible": true}`. It now stores
`{"open": {"LEFT": "garnet.explorer"}}` — a `Map<DockRegion, String>`, written by
`DockLayoutStore.save(DockState.openMap())` and applied on load via `DockState.applyOpenMap(...)`.
`load()` still reads the legacy `leftVisible` shape and migrates it in memory (`true` →
`{LEFT: "garnet.explorer"}`, `false` → empty), and the file is rewritten in the new shape on the next
`save()`. `applyOpenMap` ignores any entry whose id is unknown or whose panel belongs to a different
region than the entry claims, so a stale file (panel removed or moved between versions) cannot wedge
the dock into showing nothing or the wrong body.

## Mount epochs now bump on a panel switch, not only on hide — the ghost-popup bug this fixes

`DockState.mountEpoch(region)` is a per-region counter used as the `key()` of that region's panel body
in `RegionColumn`, forcing a fresh composition whenever it changes (see
[dock-framework.md#panel-composition-must-not-outlive-its-mount](dock-framework.md#panel-composition-must-not-outlive-its-mount)
for the full mechanism). Before the stripe, the trigger was "region hidden" — `setVisible(region,
false)` bumped it, nothing else did, because there was only ever one panel visible in a region at a
time via `activeTab`, and switching tabs reused the same slot without changing that.

Under per-panel visibility, `showPanel(id)` bumps the target region's epoch on **every actual
change** — including a same-region panel *swap* (Explorer → Local History), not just a hide.
`closeRegion` still bumps it too. This closes a real, previously latent bug: switching from Explorer
to Local History within LEFT reused the region's body slot (same `RegionColumn`, same composable
source key), so a Jewel `Popup` opened by a `Dropdown` in the Explorer — e.g. the kebab menu — was
added to the **scene**, not to the keyed subtree the panel switch tears down. The popup layer could
therefore survive the swap and paint over the Local History panel underneath it, with no state flag
anywhere showing the leak (a pixel-probe-only bug). Keying `RegionColumn`'s body on
`(mountEpoch(region), panel.id)` — the panel id too, not just the epoch — makes a same-epoch-but-
different-panel render at the wrong key impossible, and bumping the epoch on every `showPanel`/
`closeRegion` change makes every switch a genuinely new composition regardless of whether the region
ends up open or closed.

## See also

- [dock-framework.md](dock-framework.md) — the rest of `GarnetDock`'s layout and the mount-lifecycle
  guards this article's last section extends.
- [dock-input-routing.md](dock-input-routing.md) — `regionAt`'s full z-order and how a stripe click
  reaches `togglePanel` through the input pipeline.
- [structure-info-panel.md](structure-info-panel.md) — the panel behind the stripe's third icon.
- [local-history-panel.md](local-history-panel.md) — the panel behind the stripe's second icon.
