# UI

Client-side screens, custom widgets, layout, input handling, and MC GUI integration. Anything under `src/client/kotlin/.../screen/` belongs here.

**Tags:** screens, widgets, rendering, layout, input, dropdown, editor

## Articles

- [DropdownHost popup-stratum pattern](dropdown-host-popup-stratum.md) — Why DropdownButton routes its popup through a separate stratum on the host screen to dodge ScrollableLayout scissor clipping. _[widgets, dropdown, rendering, scissor, scrollable-layout]_
- [ARGB color pitfalls in MC 26.1 widgets](argb-color-pitfalls.md) — Why `0xFFFFFF` text is invisible, why `-1` is the white sentinel, and how to compose alpha onto a 24-bit RGB. _[rendering, color, argb, widgets, gotcha]_
- [IntStepper vs IntEditBox — when to use which](intstepper-vs-inteditbox.md) — Stepper modifier semantics (Shift=100, Ctrl=10), why tick columns chose steppers, and the `extractContents` vs `extractWidgetRenderState` extension points. _[widgets, input, stepper, editbox, modifiers]_
