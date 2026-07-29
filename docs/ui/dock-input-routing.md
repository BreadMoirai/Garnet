---
title: Dock input routing — GLFW mixins into Compose, active-only
tags: [compose, dock, input, mixin, glfw, keybind]
summary: How raw GLFW pointer/key/char callbacks are routed into the dock ComposeScene only while a region is focused (off by default), the MC 26.1.2/26.2 MouseHandler/KeyboardHandler mixin targets that diverged from older signatures, the Alt+1/Shift+1 keybinds, and ESC-drops-focus.
---

# Dock input routing

Raw GLFW input reaches the full-window dock `ComposeScene` through `DockInputRouter`
(`src/client/kotlin/.../ui/compose/input/DockInputRouter.kt`), fed by two HEAD-injecting mixins on
MC's input handlers. The whole path is **active-only and OFF by default**: it does nothing until a
region is focused, so uncaptured input stays byte-for-byte vanilla.

This article covers routing into the *Compose* dock. Vanilla `Screen`s (pause menu, inventory, …)
opened while the viewport is shrunk are a separate concern: their cursor coordinates are re-mapped
through the content-rect offset by `MouseHandlerViewportMixin` — see
[architecture/shrink-viewport-compose-model.md#cursor-input-maps-through-the-shrink-offset](../architecture/shrink-viewport-compose-model.md#cursor-input-maps-through-the-shrink-offset).

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
    code is read via `KeyEvent#key()`, modifiers via `KeyEvent#modifiers()`. The middle `int` is the
    action.
  - `charTyped(JLnet/minecraft/client/input/CharacterEvent;)V` — the printable-character callback.
    `CharacterEvent` is a record wrapping a single `int codepoint` (accessor `codepoint()`, all
    lowercase — not `codePoint()`). Added in Task 3 so a focused text field can receive typed text;
    the key callback above only ever carries key codes, never characters.

A wrong `@Inject` target here fails silently (mixin doesn't apply) or crashes at class-load, so these
descriptors are load-bearing. All are HEAD, `cancellable = true`, and cancel vanilla **only when
`captured`** — otherwise they return without touching `ci`.

## What is and isn't forwarded

- Pointer move/press/release/scroll are forwarded into `ComposeSurface.sendPointer*/sendScroll`
  (guarded — a `disabled` Compose surface no-ops). This is the load-bearing dispatch the Explorer
  relies on (pointer-driven interactions). **The GLFW button index is threaded all the way
  through as of the context-menu work**: `onGlfwPress(button: Int)`/`onGlfwRelease(button: Int)`
  map the raw index via `glfwMouseButtonToPointerButton` (file-scope function in
  `DockInputRouter.kt`; `LEFT→Primary`, `RIGHT→Secondary`, `MIDDLE→Tertiary`, anything else →
  `null`, dropped rather than mislabelled) and pass the resulting `PointerButton?` down through
  `ComposeSurface.sendPointerPress/Release(pos, button)` → `ComposeSceneHost.pointerPress/Release(pos,
  button)` → `ImageComposeScene.sendPointerEvent(.., button = button)`. Previously the button index
  was discarded and every dock click reached Compose as `PointerButton.Primary`, which made
  right-click context menus impossible to distinguish from a left click.
- **Key→Compose translation and typed characters are wired (Task 3).** `DockInputRouter.onGlfwKey(key,
  action, mods)` forwards every non-ESC key while captured into `ComposeSurface.sendKey`, building a
  Compose `KeyEvent` via the synthetic desktop factory
  `androidx.compose.ui.input.key.KeyEvent(key, type, codePoint, isCtrlPressed, isMetaPressed,
  isAltPressed, isShiftPressed, nativeEvent)` (opt-in: `@OptIn(InternalComposeUiApi::class)` — despite
  looking like a candidate for `@ExperimentalComposeUiApi`, the factory's actual marker annotation in
  the 1.11.0 jar is `InternalComposeUiApi`). Keys with no `glfwKeyToComposeKey` mapping
  (`GlfwKeyMap.kt`) are dropped rather than guessed at. Both `onGlfwKey`/ESC and arrow-key/navigation
  traffic leave `nativeEvent` as its default `null` — nothing downstream needs it for those.
  `DockInputRouter.onGlfwChar(codePoint)`, fed by the new `garnet$charTyped` injection, reuses the same
  factory but *does* populate `nativeEvent`, with a real (never-shown) `java.awt.event.KeyEvent(..,
  KEY_TYPED, .., keyChar)`: Compose desktop's typed-text recognition
  (`TextFieldKeyInput_desktopKt.isTypedEvent` → `AwtEvents_desktopKt.getAwtEventOrNull`) only fires when
  it can unwrap a real AWT `KeyEvent` off that field and read `getKeyChar()` — a `KeyEvent` with
  `codePoint` set but `nativeEvent = null` reaches `Modifier.onKeyEvent` handlers fine but a
  `BasicTextField` silently ignores it. Deliberately does **not** use the "documented fallback"
  `KeyEvent_desktopKt.toComposeEvent(awtEvent)` conversion function to build that `nativeEvent`: that
  function's modifier computation calls `Toolkit.getDefaultToolkit()`, which on Windows lazily starts a
  real, non-daemon native message-pump thread that does not reliably dispose itself — this hung the
  whole client process after test completion during verification (confirmed via `Get-Process` reporting
  `Responding: False` for many minutes; the process had to be force-killed). The synthetic factory
  computes modifiers from plain booleans and never touches `Toolkit`, so building the AWT event only to
  carry as `nativeEvent` (never handing it to `toComposeEvent`) avoids that hazard while still
  satisfying `isTypedEvent`'s `instanceof java.awt.event.KeyEvent` check. Both paths are additive: they
  still return/report nothing that changes cancellation, so a focused text field or list can now consume
  arrow keys and typed text.
  The throwaway `java.awt.Canvas` that serves as that event's source is **`by lazy`, and must stay
  that way**. `DockInputRouter` is an `object` and `KeyboardHandlerMixin` reads `captured` on every
  keystroke of *ordinary, uncaptured* play, so an eager initializer would class-initialize
  `java.awt.Component` (`Toolkit.loadLibraries()`, `AppContext.getAppContext()`) during plain
  gameplay — the same AWT-init surface as the hang above, and the one thing that could break the
  dock's OFF-by-default invariant. `by lazy` defers it to the first typed character while a panel
  actually has input captured.
- **ESC drops dock focus — but the scene gets first refusal.** `DockInputRouter.onGlfwKey(key,
  action, mods = 0)` is called from `KeyboardHandlerMixin` for every key while captured. While
  captured, a key-**press** of ESC is first *sent into the Compose scene*; only if the scene does
  **not** consume it does `clearFocus()` run. Either way the function returns `true` ("consumed")
  and the mixin cancels the callback, so vanilla ESC (pause menu) never runs on top of the dock —
  the mixin-facing half of the contract is unchanged, and with nothing in the scene interested in
  ESC the old behavior runs verbatim.
  The reason for the first-refusal step: an open Jewel `Dropdown` menu *does* consume ESC, and
  before this it could never see the key, so the only way to close a menu was to click elsewhere.
  Combined with the panel-lifecycle defect (see [dock-framework.md](dock-framework.md)) that made a
  leaked menu completely undismissable. `GlfwKeyMap` maps `GLFW_KEY_ESCAPE` for the release/repeat
  cases; the press branch builds its own `Key.Escape` event because it needs the *return* value.
  ESC release/repeat and all other keys (including ones now forwarded to Compose) return
  `false` but are still cancelled by the mixin (same as before) since the game must not see keystrokes
  while a panel is focused. When not captured, `onGlfwKey` always returns `false` and the mixin
  returns before touching `ci`, so ESC is byte-for-byte vanilla (opens the pause menu as normal). This
  closes the previous total-input-lockout bug where ESC opened the pause menu while `captured` stayed
  true, and the mouse mixin then swallowed clicks on that menu too.

## Keybinds (`DockKeybinds.kt`)

One `KeyMapping` on `1`; the Alt/Shift distinction is read from live GLFW modifier state on
`consumeClick()` (via `GLFW.glfwGetKey(mc.window.handle(), ...)`):

- **Alt+1** — toggle focus of LEFT (Explorer): focus + cursor-release, or `clearFocus()` if already
  focused.
- **Shift+1** — toggle LEFT visibility; if hiding a focused region, also `clearFocus()`, then
  `garnet$updateScaledFramebuffer(true)` so the world inset resizes immediately.
- Bare `1` falls through to the vanilla hotbar slot.
- Both branches are no-ops while `mc.level == null` (no world loaded) — see
  [dock-framework.md](dock-framework.md#world-session-lifecycle) for the disconnect-time teardown
  this guard pairs with.

Both branches also call `syncDockViewport()` (defined in `DockKeybinds.kt`) right after mutating
`DockState` and before the framebuffer-resize call — see "Render enablement is derived from
DockState" below for what it does and why it makes the dock reachable on its own.

Registered from `GarnetClient.onInitializeClient()` next to `registerViewportToggle()`.

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
`garnet$updateScaledFramebuffer(true)` separately, right after, to apply the shrink using
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
set (no visible region). Two more cases (Task 3) close the key-delivery gap: one mounts a
`focusable().onKeyEvent { }` Box, focuses it via `FocusRequester`, drives a real
`DockInputRouter.onGlfwKey(GLFW_KEY_DOWN, GLFW_PRESS, 0)` through the router→`ComposeSurface`→scene
path, and asserts the widget observed `Key.DirectionDown`; the other re-asserts the ESC-only-consumed
contract now that `onGlfwKey` takes a third `mods` param, confirming a non-ESC key while captured is
delivered but still reported `false` (not consumed) and ESC is still the only key returning `true`.
`DockInputSpec` is registered in `ClientTestSentinel` (autoscan is off).

Two more cases cover the button-threading fix above: a pure-function test asserts
`glfwMouseButtonToPointerButton` maps `LEFT`/`RIGHT`/`MIDDLE` to `Primary`/`Secondary`/`Tertiary`
and an unmapped index (`7`) to `null`; a router-level test mounts a panel with a raw
`pointerInput { awaitPointerEventScope { ... } }` probe, drives `onGlfwMove` + `onGlfwPress(GLFW_MOUSE_BUTTON_RIGHT)`
through the real router→`ComposeSurface`→scene path, and asserts the probe observed exactly
`PointerButton.Secondary`. That test must call `DockState.reset()` before mounting its panel —
`DockState.leftPanels` already holds the production Explorer panel at tab index 0, so a panel
appended without a reset lands on a non-active tab and its `content()` is never composed
(`RegionColumn` only invokes `panels[active].content(panels[active])`).
