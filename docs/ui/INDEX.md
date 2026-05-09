# UI

Client-side screens, custom widgets, layout, input handling, and MC GUI integration. Anything under `src/client/kotlin/.../screen/` belongs here.

The two live screens are `RecorderScreen` (opened by `RedstoneSpecRecorderBlock`) and `RunnerScreen` (opened by `RedstoneSpecRunnerBlock`). There is no editor screen.

**Tags:** screens, widgets, rendering, layout, input, dropdown, recorder, runner

## Articles

- [DropdownHost popup-stratum pattern](dropdown-host-popup-stratum.md) — Why DropdownButton routes its popup through a separate stratum on the host screen to dodge ScrollableLayout scissor clipping. _[widgets, dropdown, rendering, scissor, scrollable-layout]_
- [ARGB color pitfalls in MC 26.1 widgets](argb-color-pitfalls.md) — Why `0xFFFFFF` text is invisible, why `-1` is the white sentinel, and how to compose alpha onto a 24-bit RGB. _[rendering, color, argb, widgets, gotcha]_
- [IntEditBox — when to use it and the extractWidgetRenderState extension point](intstepper-vs-inteditbox.md) — IntEditBox (min/max clamping, START sentinel) and the `extractContents` vs `extractWidgetRenderState` extension point rule for custom widgets. _[widgets, input, editbox, modifiers]_
