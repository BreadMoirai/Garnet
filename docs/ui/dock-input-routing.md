---
title: Dock input routing — GLFW mixins into Compose, active-only
tags: [compose, dock, input, mixin, glfw, keybind]
summary: How raw GLFW pointer/key callbacks are routed into the dock ComposeScene only while a region is focused (off by default), the MC 26.1.2 MouseHandler/KeyboardHandler mixin targets that diverged from older signatures, the Alt+1/Shift+1 keybinds, and ESC-drops-focus.
---

# Dock input routing

Raw GLFW input reaches the full-window dock `ComposeScene` through `DockInputRouter`
(`src/client/kotlin/.../ui/compose/input/DockInputRouter.kt`), fed by two HEAD-injecting mixins on
MC's input handlers. The whole path is **active-only and OFF by default**: it does nothing until a
region is focused, so uncaptured input stays byte-for-byte vanilla.

## The capture gate

`DockInputRouter.captured` is simply `DockState.focusedRegion != null`. `focus(region)` sets the
focused region and, when no vanilla `Screen` is open, calls `mouseHandler.releaseMouse()` so the OS
cursor is free over the panel. `clearFocus()` clears it and re-grabs with
`setIgnoreFirstMove()` **before** `grabMouse()` to swallow the accumulated raw-mouse delta (otherwise
the camera snaps on re-grab — same idiom as `CursorFocusToggle`). Window coords == scene coords: the
scene is full-window at `Density(1f)`, so a GLFW `(x, y)` maps straight to `Offset(x, y)`.

## Mixin targets — verified against **decompiled MC 26.1.2**, not assumed

The historical GLFW-callback signatures changed in 26.1.2; the injections target the current ones
(confirmed in the loom-cache `minecraft-clientOnly-*-sources.jar` — see
`../minecraft/mc-source-jars.md`):

- `MouseHandlerMixin` → `net.minecraft.client.MouseHandler`:
  - `onMove(JDD)V` — cursor move (unchanged name/descriptor).
  - `onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V` — the button callback. **This
    replaces the older `onPress(JIII)V`**: 26.1.2 folds button+mods into a `MouseButtonInfo` record,
    and the trailing `int` is the action (`GLFW_PRESS`/`GLFW_RELEASE`). The button index is read via
    `MouseButtonInfo#button()`.
  - `onScroll(JDD)V` — scroll (unchanged).
- `KeyboardHandlerMixin` → `net.minecraft.client.KeyboardHandler`:
  - `keyPress(JILnet/minecraft/client/input/KeyEvent;)V` — the key callback. **This replaces the
    older `keyPress(JIIII)V`**: 26.1.2 folds key/scancode/mods into a `KeyEvent` record. The GLFW key
    code is read via `KeyEvent#key()`. The middle `int` is the action.

A wrong `@Inject` target here fails silently (mixin doesn't apply) or crashes at class-load, so these
descriptors are load-bearing. All are HEAD, `cancellable = true`, and cancel vanilla **only when
`captured`** — otherwise they return without touching `ci`.

## What is and isn't forwarded

- Pointer move/press/release/scroll are forwarded into `ComposeSurface.sendPointer*/sendScroll`
  (guarded — a `disabled` Compose surface no-ops). This is the load-bearing dispatch the Explorer
  relies on (pointer-driven interactions).
- **Key→Compose translation is deferred.** `KeyboardHandlerMixin` currently only *cancels* game keys
  while focused (so movement/hotbar don't leak); it does not yet build a Compose `KeyEvent`.
  `DockInputRouter` has no `onGlfwChar` yet.
- **ESC drops dock focus.** `DockInputRouter.onGlfwKey(key, action)` is the ESC policy, called from
  `KeyboardHandlerMixin` for every key while captured. While captured, a plain key-**press** of ESC
  calls `clearFocus()` and returns `true` ("consumed"); the mixin then cancels the callback, so
  vanilla ESC (pause menu) never runs on top of a dropped dock focus. ESC release/repeat and all
  other keys return `false` but are still cancelled by the mixin (same as before) since the game must
  not see keystrokes while a panel is focused. When not captured, `onGlfwKey` always returns `false`
  and the mixin returns before touching `ci`, so ESC is byte-for-byte vanilla (opens the pause menu
  as normal). This closes the previous total-input-lockout bug where ESC opened the pause menu while
  `captured` stayed true, and the mouse mixin then swallowed clicks on that menu too.

## Keybinds (`DockKeybinds.kt`)

One `KeyMapping` on `1`; the Alt/Shift distinction is read from live GLFW modifier state on
`consumeClick()` (via `GLFW.glfwGetKey(mc.window.handle(), ...)`):

- **Alt+1** — toggle focus of LEFT (Explorer): focus + cursor-release, or `clearFocus()` if already
  focused.
- **Shift+1** — toggle LEFT visibility; if hiding a focused region, also `clearFocus()`, then
  `redstonespecs$updateScaledFramebuffer(true)` so the world inset resizes immediately.
- Bare `1` falls through to the vanilla hotbar slot.

Both branches also call `syncDockViewport()` (defined in `DockKeybinds.kt`) right after mutating
`DockState` and before the framebuffer-resize call — see "Render enablement is derived from
DockState" below for what it does and why it makes the dock reachable on its own.

Registered from `RedstonespecsClient.onInitializeClient()` next to `registerViewportToggle()`.

## Render enablement is derived from `DockState`, not a separate toggle

The dock keybind is now self-sufficient: pressing Shift+1 (or Alt+1) is enough to see the dock
in-world. `DockState.anyActive()` reports whether the dock has anything to show (any of
`leftVisible`/`rightVisible`/`bottomVisible`, a non-empty `centerPanels`, or a non-null
`focusedRegion`), and `syncDockViewport()` (`DockKeybinds.kt`) sets `ViewportState.active` and
`ComposeOverlay.enabled` to that value. When nothing is visible/focused, both flags go back to
`false` and the client is byte-for-byte vanilla (`WindowMixin.shouldModify()` is false, and
`ComposeOverlay.renderInto` early-returns).

`syncDockViewport()` is intentionally free of any `Window` dependency (it only flips the two
flags) so it can be exercised by a clientTest without GLFW; the keybind handler calls
`redstonespecs$updateScaledFramebuffer(true)` separately, right after, to apply the shrink using
the live window.

The `V`/`C` keybinds in `ViewportToggle.kt` remain independent debug toggles for the viewport
shrink and Compose overlay individually — they are no longer required to reach the dock, and are
unaffected by `syncDockViewport()`.

## Test coverage

`DockInputSpec` (clientTest) closes the Task-3 coverage gap: it mounts a LEFT panel containing a
`clickable` Box wired to an `AtomicInteger`, focuses via `DockInputRouter.focus(LEFT)`, drives a
`onGlfwMove` + `onGlfwPress`/`onGlfwRelease` through the **real** router→`ComposeSurface`→scene path
at the element's window coords, and asserts the counter incremented (skipped only if
`ComposeSurface.disabled`). A second `DockInputSpec` case is a pure router-level test (no
mixin/GLFW window needed) covering the ESC policy: focuses LEFT, calls
`DockInputRouter.onGlfwKey(GLFW_KEY_ESCAPE, GLFW_PRESS)` and asserts it returns `true` and
`DockState.focusedRegion` becomes `null`; asserts a non-ESC key returns `false` and leaves focus
intact; and asserts ESC returns `false` when not captured. A third case exercises
`syncDockViewport()` directly (no GLFW): starting from `DockState.reset()` with both flags `false`,
it asserts the flags stay `false` when nothing is visible, flip to `true` once `LEFT` becomes
visible, revert to `false` once hidden again, and also flip to `true` when only `focusedRegion` is
set (no visible region). `DockInputSpec` is registered in `ClientTestSentinel` (autoscan is off).
