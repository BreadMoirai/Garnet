---
title: Dock input routing — GLFW mixins into Compose, active-only
tags: [compose, dock, input, mixin, glfw, keybind]
summary: How raw GLFW pointer/key callbacks are routed into the dock ComposeScene only while a region is focused (off by default), the MC 26.1.2 MouseHandler/KeyboardHandler mixin targets that diverged from older signatures, and the Alt+1/Shift+1 keybinds.
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
  `DockInputRouter` has no `onGlfwKey/Char` yet. **ESC is deliberately never swallowed** so focus can
  always be dropped by higher-level handling.

## Keybinds (`DockKeybinds.kt`)

One `KeyMapping` on `1`; the Alt/Shift distinction is read from live GLFW modifier state on
`consumeClick()` (via `GLFW.glfwGetKey(mc.window.handle(), ...)`):

- **Alt+1** — toggle focus of LEFT (Explorer): focus + cursor-release, or `clearFocus()` if already
  focused.
- **Shift+1** — toggle LEFT visibility; if hiding a focused region, also `clearFocus()`, then
  `redstonespecs$updateScaledFramebuffer(true)` so the world inset resizes immediately.
- Bare `1` falls through to the vanilla hotbar slot.

Registered from `RedstonespecsClient.onInitializeClient()` next to `registerViewportToggle()`.

## Test coverage

`DockInputSpec` (clientTest) closes the Task-3 coverage gap: it mounts a LEFT panel containing a
`clickable` Box wired to an `AtomicInteger`, focuses via `DockInputRouter.focus(LEFT)`, drives a
`onGlfwMove` + `onGlfwPress`/`onGlfwRelease` through the **real** router→`ComposeSurface`→scene path
at the element's window coords, and asserts the counter incremented (skipped only if
`ComposeSurface.disabled`). It is registered in `ClientTestSentinel` (autoscan is off).
