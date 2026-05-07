---
title: IntStepper vs IntEditBox — when to use which
tags: [widgets, input, stepper, editbox, modifiers]
summary: IntStepper exists alongside IntEditBox because tick values are usually small deltas; modifier semantics (Shift=100, Ctrl=10) make large jumps tractable without a keyboard focus dance.
---

# IntStepper vs IntEditBox — when to use which

The editor screen has two integer-input widgets. They look interchangeable
but solve different problems.

## IntEditBox: free-form entry with bounds

`IntEditBox` extends MC's `EditBox` with min/max clamping, scroll-wheel
nudging, and a `START` sentinel for `value == -1 && min == -1`. It is the
right choice when:

- The user might want to type a specific value (e.g. property values
  `0..15` for redstone power).
- The full range is small enough that scrolling or typing is fine.
- The widget can hold focus without being inside a busy table row.

It is used for `RowProp.ExactInt` and `RowProp.RangeInt` value cells in
`SpecEditorScreen.buildValueWidget`.

## IntStepper: bounded delta entry, no focus required

`intStepper(...)` returns a `LinearLayout` of `[− label +]`. There is no
text field, no focus, no caret. Each step button advances the value by a
magnitude derived from modifier keys:

```kotlin
val mag = when {
    input.hasShiftDown() -> 100
    input.hasControlDown() -> 10
    else -> 1
}
```

(Note: Shift > Ctrl in priority. Shift+Ctrl behaves as Shift.)

This is used for the **tick stepper** in `SpecEditorScreen` when editing
a `SpecEntry`'s `time.tick`.

### Why steppers were chosen over EditBoxes for tick values

1. **Most edits are small deltas.** A tick value typically moves by 1 or 2
   to align an entry with a neighbor. Click-click is faster than
   focus-select-type-tab.
2. **No focus contention in dense rows.** Table rows pack a tick widget,
   a phase dropdown, a property name, a value cell, and a delete button
   into 16px of vertical space. Adding a focus-trapping `EditBox` to that
   row makes Tab navigation fragile and double-click-to-edit ambiguous.
3. **Modifier-scaled stepping handles the long tail.** When a user *does*
   need tick 250, `Shift+(+)` three times gets to 300, then `Ctrl+(−)`
   five times to 250 — six clicks, no keyboard focus.
4. **Shared sentinel format.** `formatIntValue` reuses the `START` display
   from `IntEditBox`'s rules, so both widgets render `-1` identically when
   `min == -1`.

### The unbounded `max`

Tick steppers in `SpecEditorScreen` pass `max = Int.MAX_VALUE`. With
`Shift = 100`, that means the user can `coerceIn` to billions of ticks
without overflow concerns — the `(value + dir * mag)` happens in `Int`
before the clamp, but `Int.MAX_VALUE - 100` still fits, so no overflow.
**Do not** raise the magnitude past what `Int.MAX_VALUE - mag` can hold,
or change `max` to `Int.MAX_VALUE` while raising mag, without revisiting
this. Today's values are safe.

## Both widgets must override `extractContents` (Button case)

`IntStepper`'s `StepButton` extends `Button` and **must** override
`extractContents` rather than `extractWidgetRenderState`. `Button` (an
`AbstractButton`) splits rendering into `extractWidgetRenderState` (frame +
focus + sound) and `extractContents` (the sprite + label). Overriding the
outer method bypasses focus/hover infrastructure; overriding
`extractContents` is the supported extension point.

`DropdownButton` uses the same pattern (`extractContents` →
`extractDefaultSprite(graphics)` + custom centered text).

`AbstractWidget` subclasses that are not buttons (`ColorSwatchWidget`,
`IntEditBox`) override `extractWidgetRenderState` directly because there
is no inner content method to hook.

## Files

- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntStepper.kt`
- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt`
- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`
