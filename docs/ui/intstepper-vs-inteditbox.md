---
title: IntEditBox — when to use it and the extractWidgetRenderState extension point
tags: [widgets, input, editbox, modifiers]
summary: IntEditBox extends MC's EditBox with min/max clamping and a START sentinel; AbstractWidget subclasses that are not buttons must override extractWidgetRenderState, not extractContents.
---

# IntEditBox — when to use it and the extractWidgetRenderState extension point

`IntEditBox` extends MC's `EditBox` with min/max clamping, scroll-wheel
nudging, and a `START` sentinel for `value == -1 && min == -1`. It is the
right choice when:

- The user might want to type a specific value (e.g. property values
  `0..15` for redstone power).
- The full range is small enough that scrolling or typing is fine.

It lives in `src/client/kotlin/.../screen/IntEditBox.kt`. Its logic is
unit-tested in `src/test/.../IntEditBoxLogicTest`.

## extractWidgetRenderState vs extractContents

MC 26.1 splits widget rendering into two extension points:

- **`extractWidgetRenderState`** — the outer method. Called unconditionally.
  Overriding this on a `Button` (or `AbstractButton`) subclass **bypasses
  focus/hover/sound infrastructure** handled by `AbstractButton.extractWidgetRenderState`.
- **`extractContents`** — the inner method on `AbstractButton` only. Called
  by `AbstractButton.extractWidgetRenderState` after it sets up frame +
  focus + sound. Overriding this is the **correct extension point** for
  custom button content.

Rule of thumb:
- Custom button face (sprite + text on a `Button`): override `extractContents`.
- Custom widget that is not a button (`IntEditBox`, `ColorSwatchWidget`, etc.):
  override `extractWidgetRenderState` directly — there is no inner method to hook.

## Files

- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/IntEditBox.kt`
