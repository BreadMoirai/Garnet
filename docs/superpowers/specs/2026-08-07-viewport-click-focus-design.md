# Click-to-switch focus between the world viewport and the dock

**Date:** 2026-08-07
**Status:** approved

## Problem

Dock focus is currently keyboard-only. `Alt+1` focuses LEFT and `ESC` drops focus; there is no
pointer gesture either way. Two concrete gaps:

1. **World → game.** While a panel is focused the OS cursor is free and
   `MouseHandlerMixin.garnet$onButton` cancels *every* click, wherever it lands. Clicking back on the
   game world does nothing; the only way out is `ESC` or `Alt+1`.
2. **Dock → panel.** While the game has focus and a vanilla `Screen` is open (inventory, pause
   menu — the cursor is free), `DockInputRouter.captured` is `false`, so the mouse mixin falls
   through and the click reaches the vanilla `Screen` instead of the dock. Worse, `Screen` cursor
   coordinates are remapped through the content-rect offset by `MouseHandlerViewportMixin`, so a
   click on the dock strip lands on a bogus screen coordinate rather than being ignored.

## Design

### A shared hit test

New pure function, `src/client/kotlin/.../ui/dock/DockHitTest.kt`:

```kotlin
fun DockState.regionAt(x: Int, y: Int, realW: Int, realH: Int): DockRegion?
```

`null` means "the bare world viewport." The implementation mirrors `GarnetDock`'s layout arithmetic
in the same z-order:

1. BOTTOM band first — full width, `y >= realH - bottomHeight` — so it wins the corner overlap
   exactly as `GarnetDock` draws it.
2. LEFT — `x < leftWidth`.
3. RIGHT — `x >= realW - rightWidth`.
4. CENTER — only when `centerPanels` is non-empty (an empty CENTER is transparent by omission and
   *is* the world).
5. Otherwise `null`.

A hidden region claims nothing. Splitters are drawn inside the reserved strips, so they need no
special case. The function takes no `Minecraft`/GLFW dependency and gets a plain-JVM test, the same
way `syncDockViewport` was split out for testability.

Coordinates are raw GLFW window coordinates, consistent with the rest of `DockInputRouter` ("window
coords == scene coords: the scene is full-window at `Density(1f)`").

### Direction 1 — click the world while a panel is focused → back to the game

In `DockInputRouter.onGlfwPress`, before dispatching into Compose: if the cursor is over the bare
world (`regionAt(...) == null`), call `clearFocus()` and return without sending anything to Compose.

- **The press is consumed.** `MouseHandlerMixin` still runs `ci.cancel()`, so the game never sees a
  press. This is click-to-focus, not click-through: a stray click on the world must not mine a
  block or swing at a mob.
- **With a vanilla `Screen` open** the same gesture drops dock focus, and `clearFocus()` already
  skips `grabMouse()` in that case, so the cursor stays free for the `Screen`.
- **The matching release** would otherwise arrive uncaptured and reach vanilla. A one-shot
  `swallowRelease: Int?` on the router records the button, and the mixin's release branch consumes
  it so the gesture stays atomic.
- **Unknown geometry.** When `ViewportState.realWidth`/`realHeight` are not yet populated (`<= 0`)
  the check is skipped entirely and the press routes to Compose as before — never guess the layout
  from a zero-sized window.

### Direction 2 — click the dock while the game has focus and a Screen is open → focus the dock

New `DockInputRouter.onGlfwPressUncaptured(button: Int): Boolean`, called from the mixin's
not-captured path. Returns `true` (mixin cancels vanilla) only when **all** of:

- `DockState.anyActive()` — the dock has something on screen,
- `Minecraft.getInstance().gui.screen() != null` — a vanilla `Screen` is open, i.e. the cursor is
  free and the pointer position is a real gesture. With the cursor grabbed for play there is no
  meaningful cursor position, so that state is deliberately untouched,
- `regionAt(...)` is non-`null` — the cursor is over a dock region.

Then it calls `focus(region)` **and** forwards the press into Compose, so the click acts on the
widget it was aimed at rather than costing the user a click. (`focus()` only calls `releaseMouse()`
when no `Screen` is open, so nothing changes about the cursor here.) The subsequent release arrives
captured and routes normally.

The asymmetry with direction 1 is intentional: leaving a panel should never act on the world
(destructive), while entering a panel acting on a widget is what the user aimed at (harmless).

### Off-by-default is preserved

`onGlfwPressUncaptured` early-outs on `DockState.anyActive()`, and everything it touches is
`DockState` plus `mc.gui.screen()` — no allocation, no AWT, no GLFW calls. Uncaptured input with the
dock closed stays byte-for-byte vanilla.

### Mixin change

`MouseHandlerMixin.garnet$onButton`'s not-captured branch stops being an unconditional `return`:

```java
if (!captured) {
    if (action == GLFW_PRESS   && router.onGlfwPressUncaptured(info.button())) ci.cancel();
    if (action == GLFW_RELEASE && router.consumeSwallowedRelease(info.button())) ci.cancel();
    return;
}
```

## Testing

- **`DockHitTestTest`** (plain JVM, `src/test`, next to `DockViewportSyncTest`): bare world; each
  visible edge region; hidden edges claiming nothing; BOTTOM winning the bottom-left/bottom-right
  corner overlap; CENTER only when a panel is present; out-of-window coordinates.
- **`DockInputSpec`** (clientTest) gains two steps on the real router path:
  - focused LEFT + a press at world coordinates → `DockState.focusedRegion` drops to `null` and the
    probe panel records no click;
  - uncaptured with the probe LEFT panel visible and a `Screen` open → a press at panel coordinates
    focuses LEFT *and* increments the probe's click counter.

## Docs

`docs/ui/dock-input-routing.md` — extend "What is and isn't forwarded" with the two click-to-focus
gestures and the hit test they share; note the new mixin branch.
