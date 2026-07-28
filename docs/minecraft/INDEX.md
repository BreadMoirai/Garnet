# Minecraft

MC-specific patterns and gotchas: source paths, mixin targeting rules, render-state extraction, GUI quirks, and version-specific behavior. Things that come from MC, not our code.

**Tags:** mc-api, mixins, versions, quirks, render-state, redstone

## Articles

- [Mixin must target the declaring class for inherited methods](mixin-target-declaring-class.md) — Inject into the class that declares the method (e.g. Level), not a subclass (ServerLevel); use instanceof inside the inject body. _Tags: mixins, mc-api, gotchas_
- [DiodeBlock.FACING points to the BACK (input) side](diode-facing-direction.md) — For repeaters and comparators, FACING is the input direction; output emits to FACING.getOpposite(). Inverting this silently breaks redstone analysis. _Tags: redstone, mc-api, quirks, blocks_
- [MC 26.1 deferred render-state extraction for widgets](render-state-extraction-26.1.md) — Custom AbstractWidget subclasses must override extractContents/extractWidgetRenderState with GuiGraphicsExtractor; the old renderWidget(GuiGraphics) signature does not exist in 26.1. _Tags: render-state, ui, mc-api, versions, widgets_
- [Locating and extracting decompiled MC source jars](mc-source-jars.md) — Decompiled vanilla sources live in .gradle/loom-cache; extract them from WSL via cmd.exe's jar tool against a Windows-pathable temp directory. _Tags: mc-api, tooling, reference_
- [Custom blit RenderPipeline on the 26.2 Blaze3D GPU API](blaze3d-custom-blit-pipeline-26.md) — Build a RenderPipeline + record a RenderPass to blit a GpuTextureView into a sub-rect; POSITION_TEX quad vs vanilla's vertex-ID triangle, nullable target getters, shared quad index buffer, lazy shader compile, the mid-frame-screenshot trap, wrapping the present blit (GpuSurface.blitFromTexture) to composite into an offset sub-rect, the render-target bottom-up V-flip, and MixinExtras compile setup. _Tags: mc-api, gpu, blaze3d, render-state, versions_
- [Overriding Window framebuffer size on MC 26.2 (viewport-shrink lever)](window-framebuffer-override-26.md) — WindowMixin cancels six Window getters/setters to shrink the reported framebuffer size; corrects a WindowEventHandler.framebufferSizeChanged() assumption (real method is resizeGui()), notes calculateScale/setGuiScale read shadowed fields directly, and that the main-target resize is gated by Window.isResized() so the mixin must set it. _Tags: mc-api, mixins, versions, quirks, render-state_
