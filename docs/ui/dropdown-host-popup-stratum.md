---
title: DropdownHost popup-stratum pattern
tags: [widgets, dropdown, rendering, scissor, scrollable-layout]
summary: Why DropdownButton routes its popup through a separate render stratum on the host screen — the workaround for ScrollableLayout scissor clipping.
---

# DropdownHost popup-stratum pattern

`DropdownButton` does not draw its expanded option list itself. Instead, the
host screen (anything implementing `DropdownHost`) renders the popup on a
**separate render stratum**, after the rest of the UI has been recorded.

## The problem this solves

In MC 26.1's deferred render pipeline, `GuiGraphicsExtractor.fill()` /
`text()` / `blitSprite()` bake `scissorStack.peek()` into the render command
**at the moment they are called**, not at draw time. `ScrollableLayout`
enables a scissor before recording its children. Any widget that draws
floating content (a popup, tooltip, expanded menu) inside that subtree gets
permanently clipped to the scroll viewport — even if the popup extends below
or beside the scroll area.

The "obvious" implementation — have `DropdownButton.extractContents` draw the
button face and, if open, also draw the option list — fails: the option list
is recorded under the scroll-area scissor and disappears the moment it
overflows.

## The pattern

Three pieces collaborate:

1. **`DropdownHost`** (interface in `DropdownButton.kt`) holds at most one
   open dropdown reference: `getOpenDropdown()` / `setOpenDropdown()`.
2. **`DropdownButton.extractContents`** only renders the button face. It
   never draws the option list, regardless of `isOpen`.
3. **The host's `extractRenderState`** (see `SpecEditorScreen`) checks for
   an open dropdown and, if present, calls `graphics.nextStratum()` and then
   `open.extractPopup(...)` on a fresh stratum where no scroll-area scissor
   is active.

```kotlin
override fun extractRenderState(graphics, mouseX, mouseY, partialTick) {
    val open = openDropdown
    if (open != null) {
        super.extractRenderState(graphics, Int.MIN_VALUE, Int.MIN_VALUE, partialTick)
        graphics.nextStratum()
        open.extractPopup(graphics, mouseX, mouseY, partialTick)
    } else {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
    }
}
```

The `Int.MIN_VALUE` mouse coords passed to `super` while a dropdown is open
suppress hover effects on widgets behind the popup — the user is interacting
with the popup, not the widgets it covers.

## Click routing

`Screen.mouseClicked` is overridden to give the popup first refusal:

```kotlin
val open = openDropdown
if (open != null) {
    if (open.popupMouseClicked(event)) return true
    // fall through: popup closed; let other widgets see the click
}
return super.mouseClicked(event, doubleClick)
```

`popupMouseClicked` returns `true` when the click hit either an option row or
the button face itself (which means "close, don't re-toggle"). A click
outside both areas closes the popup but also returns `false` so the click
still reaches the widget underneath.

## When to use `displayOverride`

`DropdownButton` accepts an optional `displayOverride: Component`. If set,
the button face shows that text instead of the currently selected option's
label. This is for **menu-mode** dropdowns — buttons that act as "pick an
action" rather than "show current value". The "+ Add Row" button in
`SpecEditorScreen.buildLayout` uses this: the dropdown's purpose is to pick
which property to add, but the face should always read "+ Add Row" rather
than the most-recently-picked property name.

## Alternative considered: CycleButton

Vanilla's `CycleButton` avoids popups entirely (click cycles through values
in place). It works fine for short option lists but fails for the
`SpecEditorScreen` use cases:

- Adding a row needs a discoverable list of available property names, not a
  cycle.
- The phase dropdown lists 4–5 enum values; cycling forces the user to
  click through every option to read them.

The popup-stratum pattern was chosen as the lesser evil over either cycling
or manually save/restoring the scissor stack inside every dropdown.

## Files

- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/DropdownButton.kt`
- `/mnt/h/Repo/RedstoneSpecs/src/client/kotlin/com/breadmoirai/redstonespecs/client/screen/SpecEditorScreen.kt`
