---
title: MC 26.1 deferred render-state extraction for widgets
tags: [render-state, ui, mc-api, versions, widgets]
summary: Custom AbstractWidget subclasses must override extractContents/extractWidgetRenderState with GuiGraphicsExtractor; the old renderWidget(GuiGraphics) signature does not exist in 26.1. Only GarnetIconButton still uses this path — the Compose dock bypasses it entirely.
---

# MC 26.1 deferred render-state extraction for widgets

Minecraft 26.1 replaced the immediate-mode `renderWidget(GuiGraphics, …)` model
with a **deferred render-state extraction** pipeline. Widgets no longer draw
directly; they describe what should be drawn into a `GuiGraphicsExtractor`,
which records primitives that the renderer replays later.

## The new override points

For an `AbstractWidget` subclass, override one of:

- `extractWidgetRenderState(graphics: GuiGraphicsExtractor, mouseX, mouseY, partialTick)` — the outer
  method, for a widget that is not a `Button`/`AbstractButton`.
- `extractContents(graphics: GuiGraphicsExtractor, mouseX, mouseY, partialTick)` — the inner method,
  called by `AbstractButton.extractWidgetRenderState` after it sets up frame/focus/sound. Used inside
  `Button`-like widgets only.
- For screens, `extractRenderState(graphics: GuiGraphicsExtractor, …)`.

Do **not** import `GuiGraphics` for widget rendering, and do **not** override
`renderBackground(GuiGraphics, …)` — neither exists for the widget render path
in 26.1.

## Example in this repo

`GarnetIconButton.kt` (`AbstractButton` subclass, the title-screen "Redstone Projects…" button — see
[architecture/redstone-project.md](../architecture/redstone-project.md)) is the sole surviving user of
this extension point; everything else that used to live under `client/screen/` (`RecorderScreen`,
`RunnerScreen`, `ProjectScreen`, `ProjectRootListScreen`, `IntEditBox`, `DropdownButton`, `IntStepper`)
was hard-cut when the Compose dock replaced the legacy screens:

```kotlin
// GarnetIconButton.kt (Button subclass — uses extractContents)
override fun extractContents(
    graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float
) {
    extractDefaultSprite(graphics)
    graphics.centeredText(font, label, x + width/2, y + (height-8)/2, -1)
}
```

Note `centeredText(..., -1)`: text color is ARGB; `-1` (=0xFFFFFFFF) is white.
`0xFFFFFF` has alpha=0 and renders invisible — see
[ui/argb-color-pitfalls.md](../ui/argb-color-pitfalls.md) (which also covers the analogous Compose
`Color(Long)` pitfall the dock panels are subject to).

**The Compose dock does not use this system at all.** `GarnetDock` and every dock panel are ordinary
`@Composable` functions rendered by a real Skia `ComposeScene` (see
[ui/compose-in-mc-feasibility.md](../ui/compose-in-mc-feasibility.md) and
[ui/dock-framework.md](../ui/dock-framework.md)) — there is no `GuiGraphicsExtractor`, no deferred
record/replay step, and no scissor-stratum baking to worry about inside the dock. The scissor/stratum
caveats below are historical, from when a dropdown popup (`DropdownButton`, deleted) needed to escape a
scrollable list's scissor; they remain accurate for any *future* `GuiGraphicsExtractor`-based widget
(i.e. `GarnetIconButton`-style code), but do not apply to anything under `dock/compose/`.

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
