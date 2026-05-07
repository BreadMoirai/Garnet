---
title: MC 26.1 deferred render-state extraction for widgets
tags: [render-state, ui, mc-api, versions, widgets]
summary: Custom AbstractWidget subclasses must override extractContents/extractWidgetRenderState with GuiGraphicsExtractor; the old renderWidget(GuiGraphics) signature does not exist in 26.1.
---

# MC 26.1 deferred render-state extraction for widgets

Minecraft 26.1 replaced the immediate-mode `renderWidget(GuiGraphics, …)` model
with a **deferred render-state extraction** pipeline. Widgets no longer draw
directly; they describe what should be drawn into a `GuiGraphicsExtractor`,
which records primitives that the renderer replays later.

## The new override points

For an `AbstractWidget` subclass, override one of:

- `extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX, mouseY, partialTick)`
- `extractContents(graphics: GuiGraphicsExtractor, mouseX, mouseY, partialTick)` — used inside Button-like widgets in this repo (see `DropdownButton`, `IntStepper`).
- For screens, `extractRenderState(graphics: GuiGraphicsExtractor, …)`.

Do **not** import `GuiGraphics` for widget rendering, and do **not** override
`renderBackground(GuiGraphics, …)` — neither exists for the widget render path
in 26.1.

## Examples in this repo

```kotlin
// IntEditBox.kt
override fun extractWidgetRenderState(
    graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float
) { … }

// DropdownButton.kt  (Button subclass — uses extractContents)
override fun extractContents(
    graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float
) {
    extractDefaultSprite(graphics)
    graphics.centeredText(font, label, x + width/2, y + (height-8)/2, -1)
}

// SpecOverviewScreen.kt  (Screen — uses extractRenderState)
override fun extractRenderState(graphics: GuiGraphicsExtractor, …) { … }
```

Note `centeredText(..., -1)`: text color is ARGB; `-1` (=0xFFFFFFFF) is white.
`0xFFFFFF` has alpha=0 and renders invisible — see the wider note in the UI
docs.

## Why the change matters: scissor and stratum baking

Because the extractor records primitives at call time and replays them later,
some state is **baked in when the call is recorded**, not when it is replayed.
The most painful case is scissor: `graphics.fill(...)` and `graphics.text(...)`
capture the current scissor at record time. A custom dropdown that is recorded
inside a scrollable list's scissor stratum will be clipped to that scissor
even when its popup logically belongs above it.

The pragmatic workarounds:

- Prefer vanilla `CycleButton` for in-list option pickers — it lives inside the
  same stratum as its row and avoids cross-stratum clipping.
- For genuinely floating UI (popups, tooltips), draw them from the screen's
  outer extract pass, not from inside the scrolled child widget.

`DropdownButton` exposes a separate `extractPopup(graphics, …)` for this
reason: the dropdown's open list is recorded by the screen at top-level, after
the scroll-area scissor has been popped, so it is not clipped.

## Heuristic when porting widget code from older MC

1. If you see `override fun renderWidget(GuiGraphics, …)`, it will not compile
   — replace with `extractWidgetRenderState(GuiGraphicsExtractor, …)`.
2. Replace `guiGraphics.fill / drawString / blit` with the equivalent
   `graphics.fill / text / blitSprite` on the extractor.
3. If a popup or tooltip looks clipped, the cause is almost certainly that it
   was recorded inside an enclosing scissor stratum — promote it to a
   top-level extract call.
