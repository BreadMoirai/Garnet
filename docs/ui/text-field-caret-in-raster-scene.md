---
title: The missing caret — focus interactions in the dock's raster scene
tags: [compose, jewel, focus, text-field, caret, interaction-source, gotcha]
summary: Why a focused text field in the dock's ImageComposeScene shows no caret and no focused border, the exact gate that fails (FocusableNode never emits FocusInteraction.Focus, so TextFieldCoreModifierNode.showCursor stays false), and the GarnetTextField wrapper (over a focusInteractionBridge modifier) that restores both.
---

# The missing caret — focus interactions in the dock's raster scene

A text field inside the dock accepts typing, moves its caret position with the arrow keys, and
selects with Shift+arrows — but **paints no caret**, and Jewel's focused border never appears. The
field looks inert while behaving perfectly.

## The chain, from symptom to gate

Focus itself is fine. Measured inside a live `ComposeSceneHost` (the same `ImageComposeScene` the
dock runs):

| Signal | State |
|---|---|
| The field's `FocusTargetNode` | `Active` |
| `Modifier.onFocusChanged` | fires, `isFocused = true` |
| `LocalWindowInfo.isWindowFocused` | `true` |
| `LocalCursorBlinkEnabled` | `true` (Compose's default) |
| Key events | route to the field |
| `TextFieldCoreModifierNode.isFocused` | **`false`** |
| …and therefore `showCursor` | **`false`**, `cursorAnimation == null` |

`TextFieldCoreModifierNode.showCursor` is `writeable && (isFocused || isDragHovered) &&
cursorBrush.isSpecified`, and `drawDefaultCursor` returns immediately when it is false (or when the
blink alpha is 0). With it false, the cursor animation is never even started — this is not a
blink-timing or a colour problem, and passing an explicit `cursorBrush = SolidColor(Color.Red)` to a
raw `BasicTextField` changes nothing.

That node's `isFocused` comes from `BasicTextField`'s own
`interactionSource.collectIsFocusedAsState()`. The interaction is supposed to be emitted by
`FocusableNode` — the node `BasicTextField` delegates focus tracking to — from
`onFocusStateChange`, which is invoked through the focus-target *callback* path. **That callback
path is what does not run here.** `FocusEventModifierNode` dispatch (`Modifier.onFocusChanged`) does
run, which is why focus looks healthy from the outside while no interaction is ever emitted.

Everything else the caret depends on was ruled out by measurement, not by reasoning:
node-scope coroutines run (including `delay`), `ObserverModifierNode.onObservedReadsChanged` fires,
animations advance, and the field redraws every frame.

## The fix: bridge focus into the interaction source

`Modifier.focusInteractionBridge(interactionSource)`
(`client/.../dock/compose/FocusInteractionBridge.kt`) emits `FocusInteraction.Focus` / `Unfocus` from
the path that *does* work, `onFocusChanged`. It only helps when the **same** interaction source
reaches both the bridge and the widget.

**Use `GarnetTextField`, not Jewel's `TextField`.** That wrapper
(`client/.../dock/compose/GarnetTextField.kt`) owns the interaction source and applies the bridge
itself, so there is no correct way to use it that leaves the caret invisible — the two-part wiring
is exactly what a call site forgets, and the failure mode is a field that types perfectly and merely
*looks* dead. `GarnetTextFieldUsageTest` scans `src/client` and fails if anything but the wrapper
imports Jewel's `TextField`; a wrapper cannot make the wrapped API unreachable, so the import is the
only thing there is to check. Reach for the bridge directly only for some *other* focusable widget
whose focus visuals come from an interaction source.

With the interaction delivered, `BasicTextField` recomposes as focused, `updateNode` sets
`isFocused = true`, `showCursor` flips, the blink animation starts — and Jewel's focused border
comes back too, since it reads the same source.

**The emit must not happen inside the `onFocusChanged` callback.** `MutableInteractionSource` is a
replay-less `SharedFlow`, and `onFocusChanged` fires during `onEndApplyChanges`, which can precede
the moment `collectIsFocusedAsState`'s own `LaunchedEffect` subscribes; an emission made there is
dropped with no subscriber and the caret stays missing. The bridge keys a `LaunchedEffect` on the
focus flag instead, which defers the emit to the next dispatch — by then the collector is attached.
This is exactly the difference between the failed and the successful experiment while tracking this
down.

## Testing a caret in a raster scene

`FocusInteractionBridgeTest` (`src/test`, no Minecraft) renders a `GarnetTextField` and asserts the
caret the only way an offscreen raster scene allows: **a blinking caret is the one thing that makes
consecutive frames of an otherwise static field differ.** Two details are load-bearing:

- **Space the frames in wall-clock time.** Compose's cursor blink is a plain `delay(500)` loop, not
  a frame-clock animation, so rendering frames back to back never crosses a blink boundary no matter
  how the frame nanos are advanced. A tight render loop reports "no caret" even when there is one.
- **Keep the negative guard.** The spec also pins that a raw Jewel `TextField` in the same scene
  produces byte-identical frames forever. If that guard ever fails, Compose has started emitting the
  focus interaction on its own and the wrapper has stopped being load-bearing.

## Scope

Any Jewel/Compose widget in the dock that derives visuals from focus through an interaction source
is affected the same way — the bridge is not text-field-specific, which is why it stays public
alongside the wrapper. Hover and press interactions come from pointer input and are unaffected.
