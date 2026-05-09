---
title: ARGB color pitfalls in MC 26.1 widgets
tags: [rendering, color, argb, widgets, gotcha]
summary: Why text colored 0xFFFFFF renders invisible, why -1 is the white sentinel, and how ColorSwatchWidget composes alpha onto a 24-bit RGB.
---

# ARGB color pitfalls in MC 26.1 widgets

MC 26.1's render pipeline treats every color int as **ARGB** and inspects
the alpha byte before submitting draws. Two failure modes recur in this
codebase.

## Pitfall 1: `0xFFFFFF` text is invisible

`GuiGraphicsExtractor.text()` and `centeredText()` call `ARGB.alpha(color)`
on the color argument and skip the glyph entirely if alpha is zero. The
literal `0xFFFFFF` is `0x00FFFFFF` in ARGB — pure white but **fully
transparent**. The text records, but no pixels reach the screen.

Use `-1` (= `0xFFFFFFFF`) for opaque white. Both `DropdownButton.extractContents`
and `IntStepper`'s step buttons rely on this:

```kotlin
graphics.centeredText(font, label, x + width / 2, y + (height - 8) / 2, -1)
```

The same applies to any `graphics.text(...)` / `graphics.fill(...)` call:
**if you write a color literal without an explicit alpha byte, you have
written a transparent color.** When in doubt, `-1` for opaque white,
`0xFF000000.toInt() or rgb` to put alpha onto a 24-bit RGB.

## Pitfall 2: Colors stored as 24-bit RGB

Any widget or screen that stores a color as 24-bit RGB (`Int and 0xFFFFFF`)
must add alpha back before passing to `fill()` / `text()`:

```kotlin
override fun extractWidgetRenderState(graphics, mouseX, mouseY, partialTick) {
    graphics.fill(x, y, x + width, y + height, (0xFF000000.toInt() or (rgb and 0xFFFFFF)))
}
```

Without the `0xFF000000.toInt() or` step the fill is invisible — same root
cause as pitfall 1 but for `fill()`.

24-bit storage is appropriate when the alpha channel is meaningless for the
stored value (e.g. a tint color where transparency is always opaque). The
alpha is a **rendering concern only**, applied at draw time.

## Why `0xFF000000.toInt()`?

`0xFF000000` is a `Long` in Kotlin (it overflows `Int`). Calling `.toInt()`
truncates to `0xFF000000` as a signed `Int`, which is exactly what the ARGB
packing wants. Writing `0xFF000000 or rgb` without `.toInt()` is a type
error.

## Files

- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RecorderScreen.kt`
- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/RunnerScreen.kt`
