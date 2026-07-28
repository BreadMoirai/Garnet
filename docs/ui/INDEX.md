# UI

Client-side UI, rendering, layout, and input handling. The live UI is a full-window **Compose
dock** (`GarnetDock`) blended over the composited game world — see
[dock-framework.md](dock-framework.md) for the model. The legacy MC-`Screen`-based UI
(`RecorderScreen`, `RunnerScreen`, `ProjectScreen`, `ProjectRootListScreen`, and their widgets
`IntEditBox`, `DropdownButton`, `IntStepper`, `TimelineSliderWidget`) was hard-cut once the dock
replaced it; only `GarnetIconButton` (the title-screen "Redstone Projects…" button) survives
under `src/client/kotlin/.../screen/`.

**Tags:** compose, dock, screens, widgets, rendering, layout, input

## Articles

- [ARGB color pitfalls — MC GuiGraphicsExtractor and Compose Color](argb-color-pitfalls.md) — Why `0xFFFFFF` / `Color(0x1B2433)` render invisible in both the legacy `GuiGraphicsExtractor` path and Compose's `Color(Long)`, and how to compose alpha correctly in each. _[rendering, color, argb, widgets, compose, gotcha]_
- [Compose-in-Minecraft feasibility — real ComposeScene over Blaze3D GL](compose-in-mc-feasibility.md) — GO verdict: a real Compose Multiplatform `ComposeScene` renders into a Blaze3D TextureTarget FBO, coexists with MC's GL renderer across frames, and reacts to pointer input; the mechanism, the GL-state + pixel-store contract, and the build wiring. _[compose, skia, skiko, blaze3d, opengl, feasibility, spike]_
- [Full-window transparent Compose overlay via premultiplied-alpha blit](compose-blended-overlay.md) — BlitUvPipeline's blend pipeline, the real MC 26.2 `RenderPipeline`/`BlendFunction`/`ColorTargetState` API (no `withBlend`, top-level `SourceFactor`/`DestFactor`), and why the overlay now covers the full window. _[compose, skia, blaze3d, blend, rendering, overlay, gpu-api]_
- [GarnetDock — full-window Compose dock over the world composite](dock-framework.md) — How GarnetDock lays out LEFT/RIGHT/BOTTOM/CENTER regions (with tab strips and splitters) at real framebuffer pixels via ComposeSceneHost, why the center is transparent by omission, the OFF-by-default input guard, why panel composition must not outlive its mount, and two Compose API gotchas. Includes the Project Explorer as the template live-data panel, now rebuilt on Jewel's `LazyTree`/`Dropdown`/`TextField`/buttons — clicking a `.nbt` file places it centered in its auto-assigned region, and the header's New/Save/Discard Structure actions create/auto-fit/revert standalone structure files (see [architecture/redstone-project.md#standalone-structure-files](../architecture/redstone-project.md#standalone-structure-files)). _[compose, dock, layout, panels, input, rendering, jewel]_
- [Dock input routing — GLFW mixins into Compose, active-only](dock-input-routing.md) — How raw GLFW pointer/key/char callbacks are routed into the dock ComposeScene only while a region is focused (off by default), the MC 26.1.2/26.2 MouseHandler/KeyboardHandler mixin targets that diverged from older signatures, the Alt+1/Shift+1 keybinds, and ESC-drops-focus. _[compose, dock, input, mixin, glfw, keybind]_
- [Dialogs in the dock — Compose Popup renders in-scene, native pickers on a worker thread](dock-dialogs.md) — Compose `Popup`/`DropdownMenu` DO render inside the embedded `ImageComposeScene` (it's a `CanvasLayersComposeScene`) — Jewel's `Dropdown` is used directly, retiring the old hand-rolled-overlay advice — and how `TinyFileDialogs` pickers are still threaded off the render thread. _[compose, dock, dialogs, popup, jewel, tinyfd, threading, gotcha]_
- [Jewel — the dock's widget layer](jewel-widget-layer.md) — The dock's IntelliJ-look widget layer: the load-bearing Jewel/Compose/skiko triple, why icons need a separate artwork artifact, why in-scene popups need the dock's mount-lifecycle guards, and the Jewel-authoritative tree-state model. _[compose, jewel, dock, icons, popup, skiko, versions]_

## See also

- [architecture/shrink-viewport-compose-model.md](../architecture/shrink-viewport-compose-model.md) — how the framebuffer shrink, the present-time composite, and this dock stack into one frame.
- [minecraft/render-state-extraction-26.1.md](../minecraft/render-state-extraction-26.1.md) — the legacy `GuiGraphicsExtractor` deferred-render rules, still relevant to `GarnetIconButton` but not to anything under `ui/compose/`.
