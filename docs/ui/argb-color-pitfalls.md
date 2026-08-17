---
title: ARGB color pitfalls — MC GuiGraphicsExtractor and Compose Color
tags: [rendering, color, argb, widgets, compose, gotcha]
summary: Why 0xFFFFFF renders invisible in both GuiGraphicsExtractor.text()/fill() and Compose's Color(Long) constructor, why -1 / 0xFFFFFFFF is the white sentinel, and how to compose alpha onto a 24-bit RGB in each API.
---

# ARGB color pitfalls — MC GuiGraphicsExtractor and Compose Color

Two color APIs in this codebase pack alpha into the top byte and silently render nothing if you
forget it: MC's deferred-render `GuiGraphicsExtractor` (still used by the one surviving legacy widget,
`GarnetIconButton`) and Compose's `androidx.compose.ui.graphics.Color(Long)` constructor (used
throughout `GarnetDock` and every dock panel). The failure mode is identical in both — write a bare
24-bit hex literal and you have written a **fully transparent** color — only the fix looks slightly
different per API.

## Compose: `Color(0x1B2433)` is invisible, `Color(0xFF1B2433)` is opaque

`androidx.compose.ui.graphics.Color(color: Long)` treats the `Long` as packed **ARGB** — same
convention as MC's — and the top byte is alpha. `GarnetDock.kt`'s palette constants show the correct
pattern:

```kotlin
private val PANEL_BG = Color(0xF01B2433)     // ~94% opaque slate; center stays transparent by omission
private val SPLITTER_COLOR = Color(0xFF10161F) // fully opaque
```

Omitting the leading `FF` (or `F0`, or any non-zero byte) — writing `Color(0x1B2433)` — produces alpha
`0x00`: the shape composes into the scene as fully invisible, same silent failure as MC's `0xFFFFFF`
text. This matters more in the dock than it did in the old GUI screens, because the dock's root `Box`
relies on deliberate, *partial* transparency (the CENTER region paints nothing so the world shows
through — see [compose-blended-overlay.md](compose-blended-overlay.md)) — an accidentally-transparent
panel background is easy to mistake for "the CENTER trick is spreading" when it is actually just a
missing alpha byte on an edge region that was supposed to be opaque.

## MC `GuiGraphicsExtractor`: `0xFFFFFF` text is invisible, `-1` is the white sentinel

`GuiGraphicsExtractor.text()` and `centeredText()` call `ARGB.alpha(color)` and skip the glyph
entirely if alpha is zero. The literal `0xFFFFFF` is `0x00FFFFFF` in ARGB — pure white but **fully
transparent**. Use `-1` (= `0xFFFFFFFF`) for opaque white:

```kotlin
graphics.centeredText(font, label, x + width / 2, y + (height - 8) / 2, -1)
```

The same applies to `graphics.fill(...)`: a 24-bit RGB literal needs alpha added back —
`0xFF000000.toInt() or (rgb and 0xFFFFFF)` — before it will draw. `0xFF000000` is a `Long` in Kotlin
(it overflows `Int`); `.toInt()` truncates it to the signed `Int` bit pattern the ARGB packing wants.
This extension point (`extractWidgetRenderState`/`extractContents` on `GuiGraphicsExtractor`) is now
only exercised by `GarnetIconButton` — see
[render-state-extraction-26.1.md](../minecraft/render-state-extraction-26.1.md) for the wider
deferred-render-state rules this pitfall lives inside.

## Files

- `src/client/kotlin/com/breadmoirai/garnet/ui/dock/GarnetDock.kt`
- `src/client/kotlin/com/breadmoirai/garnet/editor/workspace/ui/GarnetIconButton.kt`
