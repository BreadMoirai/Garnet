---
title: Custom blit RenderPipeline on the 26.2 Blaze3D GPU API
tags: [mc-api, render-state, gpu, blaze3d, versions, quirks]
summary: How to build a RenderPipeline and record a RenderPass to blit a GpuTextureView into a sub-rect on MC 26.2, the non-obvious traps (nullable getters, per-frame vertex buffer, shared quad index buffer, lazy shader compile), the render-target Y-flip, and how to intercept the present blit to composite into an offset sub-rect.
---

# Custom blit RenderPipeline on the 26.2 Blaze3D GPU API

MC 26.2 replaced the old immediate-mode/`GlStateManager` blit path with an explicit
GPU API (`RenderPipeline` + `CommandEncoder` + `RenderPass`). Our clean-room blit lives in
`src/client/kotlin/.../client/viewport/BlitUvPipeline.kt` and `CompositeTarget.kt`, with
shaders under `src/client/resources/assets/garnet/shaders/core/blit_uv.{vsh,fsh}`.
This article records what the code alone doesn't make obvious.

## Two ways to blit — and why we chose the harder one

Vanilla's own render-target blit (`RenderTarget.blitAndBlendToTexture`) uses the
`core/screenquad` vertex shader with `DefaultVertexFormat.EMPTY` and
`PrimitiveTopology.TRIANGLES`, then calls `renderPass.draw(...)` — a full-screen triangle
generated from `gl_VertexID` with **no vertex buffer at all**. That is the simplest path but
it always covers the whole target.

We need an arbitrary destination sub-rect and source UV region, so we use a real
`DefaultVertexFormat.POSITION_TEX` quad. That means:

- Build a per-call vertex buffer: 4 vertices × (3 floats position + 2 floats UV) = 20-byte
  stride, in a `ByteBuffer.allocateDirect(...).order(ByteOrder.nativeOrder())`, uploaded via
  `RenderSystem.getDevice().createBuffer(label, GpuBuffer.USAGE_VERTEX, byteBuffer)`. Bind it
  with `pass.setVertexBuffer(0, vertexBuffer.slice())` — 26.2's `setVertexBuffer` takes a
  `GpuBufferSlice`, not the raw `GpuBuffer`, so call `.slice()` (whole-buffer slice).
- Reuse the shared quad index buffer instead of building one:
  `RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)` → `.getBuffer(6)` + `.type()`
  (maps 4 verts → 6 indices, `0,1,2,2,3,0`). Then `pass.setIndexBuffer(buf, type)` and
  `pass.drawIndexed(indexCount=6, instanceCount=1, firstIndex=0, vertexOffset=0, firstInstance=0)`.
  **The 26.2 `drawIndexed` arg order is `(indexCount, instanceCount, firstIndex, vertexOffset,
  firstInstance)`** — different from the older `(baseVertex, firstIndex, indexCount, instanceCount)`.

## Pipeline builder: bind groups, vertex bindings, primitive topology

26.2's `RenderPipeline.Builder` reworked how samplers and vertex formats are declared:

- **Samplers** are no longer added with `withSampler("Name")`. Declare them through a bind-group
  layout: `.withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").build())`.
  The `pass.bindTexture("InSampler", view, sampler)` call at draw time is unchanged.
- **Vertex format** is split from the primitive mode. `withVertexFormat(fmt, VertexFormat.Mode.QUADS)`
  becomes `.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX).withPrimitiveTopology(
  PrimitiveTopology.QUADS)`. `VertexFormat.Mode` no longer exists; use `com.mojang.blaze3d.PrimitiveTopology`.

## Vertex-attribute names must match the format

The GLSL `in` names are bound by name to the `VertexFormat` elements. For
`POSITION_TEX` they must be exactly `Position` (vec3) and `UV0` (vec2) — same as vanilla's
`position_tex.vsh`. Our vertex shader deliberately omits the `ProjMat`/`ModelViewMat`
uniforms vanilla uses: we feed positions already in NDC, so `gl_Position = vec4(Position, 1.0)`.

## Shader id → asset path, and lazy compilation

`withVertexShader(Identifier.fromNamespaceAndPath("garnet", "core/blit_uv"))` resolves
to `assets/garnet/shaders/core/blit_uv.vsh` (the `FileToIdConverter("shaders", ".vsh")`
in `ShaderType`). The custom pipeline needs **no explicit registration** — it compiles lazily
the first time a `RenderPass` uses it. If the `.vsh`/`.fsh` are missing or malformed, the
failure surfaces at that first use, not at `RenderPipeline.build()`.

## Nullable platform getters — must null-check in Kotlin

`RenderTarget.getColorTextureView()`, `getColorTexture()`, and `getDepthTexture()` are
`@Nullable` (they return null before buffers are created). Kotlin sees them as nullable, so
`to.colorTextureView` etc. need `requireNotNull(...)` / `!!` before passing to
`createRenderPass` / `bindTexture` / `clearColorTexture`.

## RenderPass creation: clear vs. load

`commandEncoder.createRenderPass(label, colorView, Optional.empty())` **loads** the existing
target contents (draws on top) — pass `Optional.of(clearColor)` to clear first. On 26.2 the clear
color is an `Optional<org.joml.Vector4fc>` (normalized RGBA), **not** the older `OptionalInt`
packed-ARGB. For a sub-rect blit that must leave the edges untouched, use `Optional.empty()`.
`CompositeTarget` clears via `CommandEncoder.clearColorAndDepthTextures(color, Vector4f(r,g,b,a),
depth, 1.0)` (or `clearColorTexture` when the target has no depth) — it unpacks its `0xAARRGGBB`
int into a `Vector4f` first, since 26.2's clear API also switched from packed-int to `Vector4fc`.

## Gotcha: blitting into the main target mid-frame is not screenshot-visible

A blit into `mc.mainRenderTarget` issued outside the present path (e.g. from a client-tick or
test hook) is overwritten by the next full frame render before it is presented, so a
screenshot taken afterward won't show it. Visual confirmation of a composited sub-rect
requires intercepting the present blit itself (a mixin on the surface blit) — you cannot prove
it by drawing into the main target from an ad-hoc hook.

## Intercepting the present blit: `MinecraftPresentMixin`

On 26.2 the present is **`Minecraft.renderFrame` → `this.windowSurface.blitFromTexture(encoder,
mainColorTextureView)`**, where `windowSurface` is a `com.mojang.blaze3d.systems.GpuSurface` and
the presented texture is `gameRenderer.mainRenderTarget().getColorTextureView()`. (The older
`RenderTarget.blitToScreen()` → `presentTexture(...)` path was **removed** in 26.2;
`GpuSurface.blitFromTexture(CommandEncoder, GpuTextureView)` is now the real present call — the
reverse of what earlier snapshots had.) `blitFromTexture` *stretches* whatever texture it is
handed to fill the whole window surface.

`MinecraftPresentMixin` (`src/client/java/.../mixin/client/MinecraftPresentMixin.java`) puts a
MixinExtras `@WrapOperation` on the `blitFromTexture` call:

```java
@WrapOperation(method = "renderFrame", at = @At(value = "INVOKE",
    target = "Lcom/mojang/blaze3d/systems/GpuSurface;blitFromTexture(Lcom/mojang/blaze3d/systems/CommandEncoder;Lcom/mojang/blaze3d/textures/GpuTextureView;)V"))
private void garnet$compositePresent(GpuSurface surface, CommandEncoder encoder, GpuTextureView gameTexture, Operation<Void> original)
```

The wrapped call is an instance method, so the callback receives the receiver (`GpuSurface`) plus
both args (`CommandEncoder`, `GpuTextureView`). When the viewport effect is off it just forwards
`original.call(surface, encoder, gameTexture)` — byte-for-byte vanilla. When on it: sizes a
full-*real*-size `CompositeTarget`, clears it to an opaque edge color, blits the (shrunk) game
texture — the `gameTexture` arg is already the main target's color view — into the content sub-rect
(at the inset-derived origin `(frameX, frameY)` — left/top-aligned, not centered), then presents
the **composite** via `original.call(surface, encoder, composite.getColorTextureView())`. Because
the composite is the real window size, `blitFromTexture` maps it 1:1 and the content lands in its
sub-rect with the reserved strips showing the fill color. Filling the whole composite with an
opaque clear first (rather than transparent) means the reserved edges are solid for free — no
separate edge-rect draw needed.

### MixinExtras is bundled, but not on the compile classpath by default

`@WrapOperation`/`Operation` live in package `com.llamalad7.mixinextras.injector.wrapoperation`
(one word — *not* `injector.wrap.WrapOperation` + `injector.wrap.operation.Operation`).
fabric-loader ships MixinExtras at runtime, but to *compile* against it you must add it to the
compile/AP classpath. For the `client` source set:

```kotlin
"clientCompileOnly"("io.github.llamalad7:mixinextras-fabric:0.5.3")
"clientAnnotationProcessor"("io.github.llamalad7:mixinextras-fabric:0.5.3")
```

`compileOnly` (not `implementation`) avoids double-bundling; track the version fabric-loader
already ships.

## Render-target color textures are stored bottom-up — flip V when sampling one

Blaze3D `RenderTarget` color textures are stored **bottom-up** (row 0 = bottom), the GL
convention. Vanilla's own `Screenshot` readback compensates with `image.setPixelABGR(x, height
- y - 1, ...)`. So sampling a render-target texture with a naive top-left UV mapping
(`v=0` at the top) presents it **upside-down** — the first real spike screenshot showed the
world vertically mirrored inside an otherwise correctly-placed sub-rect.

`BlitUvPipeline.blit(..., flipV: Boolean = false)` handles this: pass `flipV = true` whenever
`from` is a render-target color texture (as `MinecraftPresentMixin` does for the game texture).
The default `false` keeps the plain top-left mapping for ordinary top-left-origin textures
(atlases, PNG-backed). The destination rect (NDC positions) is unaffected — only the source
`V` is mirrored, so placement stays correct and only the image un-flips.

## The blended variant: `PIPELINE_BLEND` for the Compose overlay

`BlitUvPipeline.blit(...)` has a second pipeline variant, `PIPELINE_BLEND`, selected with a trailing
`blend: Boolean = false` parameter (`blend=true`). It exists for exactly one caller: compositing the
full-window Compose dock overlay over the already-presented world composite
(`ComposeOverlay.renderInto` — see [ui/compose-blended-overlay.md](../ui/compose-blended-overlay.md)).
The original opaque `PIPELINE` (used by `MinecraftPresentMixin`'s world-into-composite blit) simply
overwrites destination pixels; the Compose overlay instead needs the destination's existing (opaque)
world pixels to show through everywhere the Skia canvas painted nothing, so it needs real
premultiplied-alpha over-compositing (`dst = src + dst*(1-srcA)`), not a copy.

`PIPELINE_BLEND` sets this via `RenderPipeline.Builder.withColorTargetState(ColorTargetState(
BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))` — MC 26.2 has no `withBlend(...)` builder method;
blending is configured through the color-target state instead (see the linked article for the full
API shape and the sources-jar verification). All non-overlay call sites (the world-into-composite blit
above) keep the default `blend=false` and use the original opaque `PIPELINE`, so they are unaffected
by the new variant.

## Capturing the composite for visual proof

The normal screenshot path (`Screenshot.grab`, Fabric's `ctx.takeScreenshot`) reads
`mc.mainRenderTarget` — which is *upstream* of the present-time composite, so it can only ever
show the shrunk world full-frame, never the offset composite. To get a PNG of the actual
composited output, point the same vanilla readback at the composite target:
`CompositeTarget.captureToPng(target, path)` calls `Screenshot.takeScreenshot(target) { image
-> image.writeToFile(path) }`. The readback callback is asynchronous (a GPU download), so the
caller must poll for the file to appear. `MinecraftPresentMixin` consumes a one-shot
`ViewportState.compositeCaptureRequest` path so a client-test can request the dump for exactly
the frame it wants.
