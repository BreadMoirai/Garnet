# Dock world-session lifecycle — auto-close panels when the world closes

**Date:** 2026-07-28
**Status:** approved, ready for planning

## Problem

`DockState` is a client-lifetime singleton. The Project Explorer panel is seeded once in
`GarnetClient.onInitializeClient`, and region visibility, splitter sizes, and input focus persist
for the whole process. Nothing listens for the world going away.

So quitting to the title screen leaves the dock up: the LEFT region keeps rendering over the title
screen, the viewport shrink stays applied (`ViewportState.active` is still true), and a focused
region keeps eating GLFW input through the mixins. Worse, the panels hold per-world data — the
Explorer's tree is rooted in a project directory tied to the session that just ended.

## Goal

When the client disconnects from a world, the dock closes: nothing renders, the framebuffer returns
to full size, input goes back to vanilla. Layout preferences survive so the next world opens the way
the user left it.

## Non-goals

- Persisting dock layout to disk across client restarts.
- Re-opening the dock automatically on world join. The user opens it with Shift+1 / Alt+1 as today.
- Any change to how panels are registered or how the Explorer sources its data.

## Design

### 1. `DockState.closeAll()`

A new method on `DockState`, sibling to `reset()`:

```kotlin
/** Ends the dock's world session: hides every edge region, clears CENTER, drops focus. */
fun closeAll() {
    setVisible(DockRegion.LEFT, false)
    setVisible(DockRegion.RIGHT, false)
    setVisible(DockRegion.BOTTOM, false)
    if (centerPanels.isNotEmpty()) { centerPanels.clear(); bumpMountEpoch(DockRegion.CENTER) }
    centerActiveTab = 0
    focusedRegion = null
}
```

**Why not `reset()`.** `reset()` also clears `leftPanels`/`rightPanels`/`bottomPanels` and restores
default widths. The Explorer panel is only ever added at `onInitializeClient`, so a `reset()` on
disconnect would leave the LEFT region permanently empty for the rest of the process. `closeAll()`
keeps the panel lists and the splitter sizes; it changes only what is world-scoped: *visibility*,
*focus*, and the CENTER documents.

**Why CENTER is cleared but the edges are not.** CENTER panels are per-world documents (a structure
file opened from the Explorer). They have no meaning without the session that produced them.
Edge panels are tools, and the tool set is the same in every world.

**Epoch bumps.** `setVisible(region, false)` already bumps that region's mount epoch when the region
was visible — the existing ghost-popup guard (see `mountEpochs` KDoc). CENTER never goes through
`setVisible`, so `closeAll` bumps it explicitly when it clears the list. Net effect: no popup layer
or `remember`ed widget state from the closed world can bleed into the next mount.

**Why `focusedRegion = null` directly, not `DockInputRouter.clearFocus()`.** `clearFocus` calls
`mc.mouseHandler.grabMouse()` when `mc.gui.screen() == null`. At `DISCONNECT` time the title /
disconnect screen is not reliably installed yet, so routing through `clearFocus` risks re-grabbing
the cursor onto the title screen. `DockInputRouter.captured` is a derived getter over
`DockState.focusedRegion`, so clearing the field alone is enough to stop the input mixins from
consuming events. Keeping `closeAll()` free of `Minecraft` calls also makes it directly testable.

### 2. Wiring — `registerDockWorldLifecycle()`

New function in `client/viewport/DockKeybinds.kt`, called from `GarnetClient.onInitializeClient`:

```kotlin
fun registerDockWorldLifecycle() {
    ClientPlayConnectionEvents.DISCONNECT.register { _, mc ->
        DockState.closeAll()
        syncDockViewport()
        (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
    }
}
```

`ClientPlayConnectionEvents.DISCONNECT` covers every exit path that matters: quit-to-title from
singleplayer, a multiplayer disconnect, and a server kick. A per-tick `mc.level == null` poll was
rejected — it runs every tick to detect an event the API already delivers.

`syncDockViewport()` derives `ViewportState.active` and `ComposeOverlay.enabled` from
`DockState.anyActive()`, which is false once `closeAll()` has run. The
`garnet$updateScaledFramebuffer(true)` follow-up mirrors what both keybind branches already do;
without it the shrink stays applied until something else resizes the framebuffer.

### 3. Keybind guard

In the `END_CLIENT_TICK` handler in `DockKeybinds.kt`, the shift and alt branches become no-ops when
`mc.level == null`. `consumeClick()` still drains the queue so presses do not stack up. This keeps
the dock from being re-opened on the title screen, where its panels have no world to describe.

## Data flow

```
quit to title
  └─ ClientPlayConnectionEvents.DISCONNECT
       ├─ DockState.closeAll()            regions hidden, CENTER cleared, focus dropped, epochs bumped
       ├─ syncDockViewport()              ViewportState.active = false, ComposeOverlay.enabled = false
       └─ garnet$updateScaledFramebuffer  framebuffer back to full window
```

## Error handling

`closeAll()` is idempotent and touches only snapshot state — a second disconnect with the dock
already closed is a no-op (`setVisible(false)` on a hidden region skips the epoch bump, the empty
`centerPanels` check short-circuits). No failure path needs handling beyond that.

## Testing

New clientTest spec `DockLifecycleSpec`. It touches no render context, so it is a plain Kotest
`StringSpec` like `DockInsetsSpec`, not a `ClientSpec`. Autoscan is off, so it must be added to
the explicit list in `src/clientTest/.../ClientTestSentinel.kt` alongside `DockInputSpec` or it
silently does not run. It asserts on `DockState` directly:

- After showing LEFT/RIGHT/BOTTOM, adding a CENTER panel, and focusing a region, `closeAll()` leaves
  all three edges hidden, `centerPanels` empty, `focusedRegion` null, and `anyActive()` false.
- `closeAll()` preserves `leftWidth` (set to a non-default value first) and leaves `leftPanels`
  non-empty.
- Each region visible before the call has a strictly greater `mountEpoch` after it.
- `closeAll()` twice in a row is a no-op the second time.

The `DISCONNECT` registration and the `mc.level == null` keybind guard need a live client
disconnect, which the harness cannot drive; they are covered by the existing dock render/input specs
plus manual verification.

## Docs

`docs/ui/dock-framework.md` gains a short "world-session lifecycle" section: the dock is
client-lifetime but its visibility is world-scoped, what `closeAll()` keeps versus drops and why,
and the `clearFocus`-avoidance reason. `docs/ui/INDEX.md`'s dock-framework summary is extended to
mention it.
