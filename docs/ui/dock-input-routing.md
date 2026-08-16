---
title: Dock input routing — GLFW mixins into Compose, active-only
tags: [compose, dock, input, mixin, glfw, keybind, hit-test]
summary: How raw GLFW pointer/key/char callbacks are routed into the dock ComposeScene only while a region is focused (off by default), the MC 26.1.2/26.2 MouseHandler/KeyboardHandler mixin targets that diverged from older signatures, the Alt+1/Shift+1 keybinds (Shift+1 toggles the Explorer panel itself) and their garnet-dock.json persistence, why join-time auto-open changes visibility only and never focus, ESC-drops-focus, and the DockState.regionAt hit test — stripe first, then BOTTOM/LEFT/RIGHT/CENTER — behind click-to-focus in both directions.
---

# Dock input routing

Raw GLFW input reaches the full-window dock `ComposeScene` through `DockInputRouter`
(`src/client/kotlin/.../ui/input/DockInputRouter.kt`), fed by two HEAD-injecting mixins on
MC's input handlers. The whole path is **active-only and OFF by default**: it does nothing until a
region is focused, so uncaptured input stays byte-for-byte vanilla.

This article covers routing into the *Compose* dock. Vanilla `Screen`s (pause menu, inventory, …)
opened while the viewport is shrunk are a separate concern: their cursor coordinates are re-mapped
through the content-rect offset by `MouseHandlerViewportMixin` — see
[architecture/shrink-viewport-compose-model.md#cursor-input-maps-through-the-shrink-offset](../architecture/shrink-viewport-compose-model.md#cursor-input-maps-through-the-shrink-offset).

## Click-to-focus, both directions

Focus is not keyboard-only. `DockState.regionAt(x, y, realW, realH)` (`ui/dock/DockHitTest.kt`)
answers "who owns this window pixel", returning `null` for the **bare world viewport**. It is the
pointer-side mirror of `insets()` and reproduces `GarnetDock`'s draw order — the **stripe's own
column first** (it is drawn last, over everything, so it is tested first; see
[dock-stripe.md](dock-stripe.md#why-the-stripe-is-drawn-last-and-hit-tested-first--one-decision-not-two)
for why those are one decision, not two), then BOTTOM's full-width band (so it wins both corners,
exactly as it is drawn over where the LEFT/RIGHT columns stop), then LEFT, then RIGHT, then CENTER
*only when it has an open panel*. An empty CENTER is transparent by omission and **is** the world,
which is the whole point of the `null` case. Hidden/closed regions and splitters need no special
case: a closed region reserves nothing, and splitters are drawn inside the reserved strips. It takes
no `Minecraft`/GLFW dependency, so it is unit-tested in `DockHitTestTest`
(`src/test/.../ui/dock/`) the same way `syncDockViewport` was split out for testability.

Both gestures skip themselves when `ViewportState.realWidth/realHeight` are still `0` — before
`WindowMixin` has cached a real framebuffer size the layout is unknowable, and guessing it from a
zero-sized window would drop focus on the first click after a startup/resize race.

- **World → game.** In `onGlfwPress`, a press while captured whose cursor is over the bare world
  calls `clearFocus()` and delivers *nothing* to Compose. The press is still **consumed** (the mixin
  cancels it regardless): leaving a panel is click-to-focus, never click-through, so a stray world
  click cannot mine a block or swing at a mob. With a vanilla `Screen` open the same gesture drops
  dock focus and `clearFocus()` already skips `grabMouse()`, so the cursor stays free for the screen.
  Because that press drops capture, the matching **release** would arrive uncaptured and reach
  vanilla as an unmatched button-up — so the press records the button in a one-shot `swallowRelease`,
  which the mixin's release branch consumes.
- **Game → dock.** `onGlfwPressUncaptured(button)` is called from the mixin's *uncaptured* branch and
  returns `true` (mixin cancels) only when `DockState.anyActive()`, a vanilla `Screen` is open, and
  the cursor is over a region. It then calls `focus(region)` **and** forwards the press into Compose,
  so the click lands on the widget it was aimed at instead of costing a click. Without it that click
  reaches the `Screen` instead — and since `MouseHandlerViewportMixin` remaps `Screen` cursor
  coordinates through the content-rect offset, a dock-strip click landed on a bogus screen
  coordinate rather than being ignored. The "a `Screen` must be open" gate is deliberate: with the
  cursor grabbed for play GLFW reports an accumulating raw delta, not a pointer position, so that
  state stays keybind-only.

The asymmetry (leaving consumes, entering delivers) is intentional: acting on the world you clicked
*past* is destructive, acting on the widget you clicked *at* is what you wanted.

`anyActive()` is the guard that keeps the OFF-by-default invariant intact — with the dock closed the
uncaptured branch is a plain field read that allocates nothing and touches no GLFW/AWT.

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
descriptors are load-bearing. All are HEAD and `cancellable = true`. `onMove`/`onScroll` cancel
vanilla **only when `captured`**, otherwise returning without touching `ci`. `onButton` is the one
exception: its uncaptured branch consults `onGlfwPressUncaptured` / `consumeSwallowedRelease` (see
"Click-to-focus, both directions" above), which decline unless the dock is on screen with a `Screen`
open — so with the dock closed it is still byte-for-byte vanilla.

## What is and isn't forwarded

- Pointer move/press/release/scroll are forwarded into `ComposeInput.sendPointer*/sendScroll`
  (guarded — a `disabled` Compose surface no-ops) — except a press over the bare world viewport,
  which is the click-to-focus gesture described above and reaches neither Compose nor the game. This is the load-bearing dispatch the Explorer
  relies on (pointer-driven interactions). **The GLFW button index is threaded all the way
  through as of the context-menu work**: `onGlfwPress(button: Int)`/`onGlfwRelease(button: Int)`
  map the raw index via `glfwMouseButtonToPointerButton` (file-scope function in
  `DockInputRouter.kt`; `LEFT→Primary`, `RIGHT→Secondary`, `MIDDLE→Tertiary`, anything else →
  `null`, dropped rather than mislabelled) and pass the resulting `PointerButton?` down through
  `ComposeInput.sendPointerPress/Release(pos, button)` → `ComposeSceneHost.pointerPress/Release(pos,
  button)` → `ImageComposeScene.sendPointerEvent(.., button = button)`. Previously the button index
  was discarded and every dock click reached Compose as `PointerButton.Primary`, which made
  right-click context menus impossible to distinguish from a left click.
- **Key→Compose translation and typed characters are wired (Task 3).** `DockInputRouter.onGlfwKey(key,
  action, mods)` forwards every non-ESC key while captured into `ComposeInput.sendKey`, building a
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

- **Alt+1** — focus LEFT: `clearFocus()` if LEFT is already focused, otherwise open the Explorer
  *only if LEFT is currently showing nothing* (an already-open Local History or Structure Info is left
  alone — Alt+1 means "give the dock the keyboard", not "switch panels") and focus LEFT.
- **Shift+1** — `DockState.togglePanel("garnet.explorer")`: opens the Explorer (evicting whatever LEFT
  currently shows), or closes LEFT if the Explorer is already what LEFT shows, or switches LEFT to the
  Explorer when LEFT is showing something else.
- Bare `1` falls through to the vanilla hotbar slot.
- Both branches are no-ops while `mc.level == null` (no world loaded) — see
  [dock-framework.md](dock-framework.md#world-session-lifecycle) for the disconnect-time teardown
  this guard pairs with.

Both branches end in `commitDockVisibilityChange()` (next section).

Registered from `GarnetClient.onInitializeClient()` next to `registerViewportToggle()`.

## Every visibility change ends in `commitDockVisibilityChange()`

`commitDockVisibilityChange(persist: Boolean = true)` (`ui/viewport/DockVisibilityCommit.kt`) is the
one definition of "which panels are open just changed — now make the rest of the client agree". In
order:

1. **Persist** the new open-panel map via `DockLayoutStore.save(DockState.openMap())` (see
   [dock-stripe.md](dock-stripe.md#persistence-garnet-dockjson-moved-from-a-boolean-to-an-open-panel-map)
   for the record's shape). This is what joining a Garnet world later reads back — see
   [dock-framework.md#left-auto-opens-on-joining-a-garnet-capable-world](dock-framework.md#left-auto-opens-on-joining-a-garnet-capable-world).
   Skipped (`persist = false`) for changes the player did not choose: disconnect-time `closeAll()`,
   join-time auto-open, and Alt+1's focus-only half, which changes no panel.
2. **Drop focus if the focused region just closed** (`DockInputRouter.clearFocus()`). A closed region
   has no scene to route into, so leaving `focusedRegion` pointing at it keeps the cursor released and
   keeps feeding clicks and keys to nothing.
3. `syncDockViewport()` — see "Render enablement is derived from DockState" below. Must run *after*
   step 2, because `focusedRegion` is one of the two inputs to `anyActive()`.
4. `garnet$updateScaledFramebuffer(true)` — re-caches `WindowMixin`'s framebuffer override.

Step 4 is the one that makes this non-optional rather than tidy. `WindowMixin` **caches** the shrunk
size in `garnet$overrideFramebufferWidth/Height` and recomputes it only from that call or a real OS
window resize, while `MinecraftPresentMixin` recomputes its destination rect from `DockInsets` fresh
on every present. Skip the call and the two disagree: close the Explorer and a `realW - 312`-wide game
texture gets blitted into a `realW - 32`-wide rect, leaving the world visibly stretched until
something else resizes the framebuffer. That was a real shipped bug — `DockStripe` mutated `DockState`
and stopped there.

The call sites are the Shift+1 and Alt+1 keybind branches, `registerDockWorldLifecycle`'s JOIN and
DISCONNECT hooks, `ExplorerActions.openLocalHistory`, and the stripe's tap. The stripe reaches it
through a callback: `ComposeSurface` passes `commitDockVisibilityChange` into `GarnetDock`, which
threads it into `DockStripe`, whose `detectTapGestures` calls `stripeIconClicked(panelId, callback)`.
That indirection is deliberate — it keeps the whole `ui/dock` package free of `Minecraft` imports so
`DockState` and the stripe's click behaviour stay unit-testable with no render context
(`DockVisibilityCommitTest` in `src/test`), and the seams on `DockVisibilityCommit` let that test pin
all four steps without a config directory or a live window.

Auto-open itself changes visibility only: `DockState.focusedRegion` stays `null`, so the mixins above
keep routing to the game and the cursor stays grabbed — Alt+1 remains the only path to focusing the
panel.

## Render enablement is derived from `DockState`, not a separate toggle

The dock keybind is now self-sufficient: pressing Shift+1 (or Alt+1) is enough to see the dock
in-world. `DockState.anyActive()` reports whether the dock has anything to show (any region with an
open panel, or a non-null `focusedRegion`), and `syncDockViewport()` (`viewport/DockViewportSync.kt`) sets `ViewportState.active` and
`ComposeOverlay.enabled` to that value. When nothing is visible/focused, both flags go back to
`false` and the client is byte-for-byte vanilla (`WindowMixin.shouldModify()` is false, and
`ComposeOverlay.renderInto` early-returns).

`syncDockViewport()` is intentionally free of any `Window` dependency (it only flips the two
flags) so it can be exercised by a plain-JVM test without a live client or GLFW;
`commitDockVisibilityChange()` calls `garnet$updateScaledFramebuffer(true)` separately, right after,
to apply the shrink using the live window.

The `V`/`C` keybinds in `ViewportToggle.kt` remain independent debug toggles for the viewport
shrink and Compose overlay individually — they are no longer required to reach the dock, and are
unaffected by `syncDockViewport()`.

## Test coverage

`DockInputSpec` (clientTest) is a single merged story — one probe panel, mounted once via
`DockState.reset()` + a `panels +=` registration + `DockState.showPanel(...)` (necessary because
`DockState.panels` already holds the production Explorer/Local History/Structure Info panels, and a
panel registered without a reset would land in the registry unopened, since `RegionColumn` only
invokes `openPanelOf(region).content(...)` — a registered-but-unopened panel is never composed) —
exercised through eight numbered steps, all through the **real** router→`ComposeInput`→scene path:

1. A raw secondary press (`onGlfwMove` + `onGlfwPress(GLFW_MOUSE_BUTTON_RIGHT)`) is collected by a
   `pointerInput { awaitPointerEventScope { ... } }` probe and asserted to arrive as exactly
   `PointerButton.Secondary` — covers the button-threading fix above.
2. After `DockInputRouter.focus(LEFT)`, a routed primary click at the probe box's window coords
   increments an `AtomicInteger`, asserting the click reaches the focused panel (skipped only if
   `ComposeSurface.disabled`).
3. A non-ESC key (`onGlfwKey(GLFW_KEY_DOWN, GLFW_PRESS, 0)`) delivered to a
   `focusable().onKeyEvent { }` Box is observed as `Key.DirectionDown` — covers the key-delivery path.
4. `onGlfwChar` delivers typed characters ("hi") into a focused `BasicTextField`'s committed text.
5. ESC while captured (`onGlfwKey(GLFW_KEY_ESCAPE, GLFW_PRESS, 0)`) returns `true` and drops
   `DockState.focusedRegion` to `null`, while a non-ESC key while captured is delivered but reported
   `false` (not consumed) — the ESC-only-consumed contract.
6. An uncaptured ESC (focus already `null`) reports `false` and drops nothing.
7. Click-to-return-to-game: with LEFT focused, a press at `x=600` (well past the 300 px LEFT strip,
   no other region visible) drops `focusedRegion` to `null` **and** leaves the probe's click counter
   untouched — the consumed-not-passed-through contract — after which
   `consumeSwallowedRelease(LEFT_BUTTON)` reports `true` exactly once.
8. Click-to-focus-the-dock: uncaptured with no `Screen` open, `onGlfwPressUncaptured` declines and
   focus stays `null`; open a bare probe `Screen` and the same press at the panel's coordinates
   reports `true`, focuses LEFT, and increments the probe's click counter.

Steps 7–8 first assert `ViewportState.realWidth/realHeight` are non-zero. Both gestures hit-test
against that cached size and *skip themselves* when it is unknown, so without that assertion a client
that never populated it would make both steps vacuously pass.

`DockInputSpec` is registered in `ClientTestSentinel` (autoscan is off).

The pure-function half of that fix (`glfwMouseButtonToPointerButton` maps `LEFT`/`RIGHT`/`MIDDLE`
to `Primary`/`Secondary`/`Tertiary` and an unmapped index (`7`) to `null`) and the
`syncDockViewport()` state-derivation case (starting from `DockState.reset()` with both flags
`false`: stays `false` when nothing is visible, flips to `true` once `LEFT` becomes visible,
reverts to `false` once hidden again, and also flips to `true` when only `focusedRegion` is set)
don't need a client at all, so they live in `DockViewportSyncTest`
(`src/test/kotlin/com/breadmoirai/garnet/client/ui/dock/`) instead of `DockInputSpec`.

`DockHitTestTest`, in the same package, is client-free for the same reason: it pins
`DockState.regionAt`'s geometry against `GarnetDock`'s layout (bare world, each visible edge, hidden
edges claiming nothing, BOTTOM winning both bottom corners, CENTER only while it holds a panel,
out-of-window coordinates). Those two files must stay in lockstep — a `GarnetDock` layout change that
`DockHitTestTest` doesn't follow silently misroutes clicks.
