# UI

Client-side screens, custom widgets, layout, input handling, and MC GUI integration. Anything under `src/client/kotlin/.../screen/` belongs here.

The two live screens are `RecorderScreen` (opened by `RedstoneSpecRecorderBlock`) and `RunnerScreen` (opened by `RedstoneSpecRunnerBlock`). There is no editor screen.

**Tags:** screens, widgets, rendering, layout, input, dropdown, recorder, runner

## Articles

- [DropdownHost popup-stratum pattern](dropdown-host-popup-stratum.md) — Why DropdownButton routes its popup through a separate stratum on the host screen to dodge ScrollableLayout scissor clipping. _[widgets, dropdown, rendering, scissor, scrollable-layout]_
- [ARGB color pitfalls in MC 26.1 widgets](argb-color-pitfalls.md) — Why `0xFFFFFF` text is invisible, why `-1` is the white sentinel, and how to compose alpha onto a 24-bit RGB. _[rendering, color, argb, widgets, gotcha]_
- [IntEditBox — when to use it and the extractWidgetRenderState extension point](intstepper-vs-inteditbox.md) — IntEditBox (min/max clamping, START sentinel) and the `extractContents` vs `extractWidgetRenderState` extension point rule for custom widgets. _[widgets, input, editbox, modifiers]_
- [Compose-in-Minecraft feasibility — real ComposeScene over Blaze3D GL](compose-in-mc-feasibility.md) — GO verdict: a real Compose Multiplatform `ComposeScene` renders into a Blaze3D TextureTarget FBO, coexists with MC's GL renderer across frames, and reacts to pointer input; the mechanism, the GL-state + pixel-store contract, and the build wiring. _[compose, skia, skiko, blaze3d, opengl, feasibility, spike]_
- [Full-window transparent Compose overlay via premultiplied-alpha blit](compose-blended-overlay.md) — BlitUvPipeline's blend pipeline, the real MC 26.2 `RenderPipeline`/`BlendFunction`/`ColorTargetState` API (no `withBlend`, top-level `SourceFactor`/`DestFactor`), and why the overlay now covers the full window. _[compose, skia, blaze3d, blend, rendering, overlay, gpu-api]_
- [RedstoneDock — full-window Compose dock over the world composite](dock-framework.md) — How RedstoneDock lays out LEFT/RIGHT/BOTTOM/CENTER regions at real framebuffer pixels via ComposeSceneHost, why the center is transparent by omission, and two Compose 1.12 API gotchas (`detectTapGestures` import, Splitter overload arity). _[compose, dock, layout, panels, input, rendering]_
