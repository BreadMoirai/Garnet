---
title: Custom blit RenderPipeline on the 26.2 Blaze3D GPU API
tags: [mc-api, render-state, gpu, blaze3d, versions, quirks]
summary: How to build a RenderPipeline and record a RenderPass to blit a GpuTextureView into a sub-rect on MC 26.2, and the non-obvious traps (nullable getters, per-frame vertex buffer, shared quad index buffer, lazy shader compile).
---

# Custom blit RenderPipeline on the 26.2 Blaze3D GPU API

MC 26.2 replaced the old immediate-mode/`GlStateManager` blit path with an explicit
GPU API (`RenderPipeline` + `CommandEncoder` + `RenderPass`). Our clean-room blit lives in
`src/client/kotlin/.../client/viewport/BlitUvPipeline.kt` and `CompositeTarget.kt`, with
shaders under `src/client/resources/assets/redstonespecs/shaders/core/blit_uv.{vsh,fsh}`.
This article records what the code alone doesn't make obvious.

## Two ways to blit — and why we chose the harder one

Vanilla's own render-target blit (`RenderTarget.blitAndBlendToTexture`) uses the
`core/screenquad` vertex shader with `DefaultVertexFormat.EMPTY` and
`VertexFormat.Mode.TRIANGLES`, then calls `renderPass.draw(0, 3)` — a full-screen triangle
generated from `gl_VertexID` with **no vertex buffer at all**. That is the simplest path but
it always covers the whole target.

We need an arbitrary destination sub-rect and source UV region, so we use a real
`DefaultVertexFormat.POSITION_TEX` quad. That means:

- Build a per-call vertex buffer: 4 vertices × (3 floats position + 2 floats UV) = 20-byte
  stride, in a `ByteBuffer.allocateDirect(...).order(ByteOrder.nativeOrder())`, uploaded via
  `RenderSystem.getDevice().createBuffer(label, GpuBuffer.USAGE_VERTEX, byteBuffer)`.
- Reuse the shared quad index buffer instead of building one:
  `RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS)` → `.getBuffer(6)` + `.type()`
  (maps 4 verts → 6 indices, `0,1,2,2,3,0`). Then `pass.setIndexBuffer(buf, type)` and
  `pass.drawIndexed(baseVertex=0, firstIndex=0, indexCount=6, instanceCount=1)`.

## Vertex-attribute names must match the format

The GLSL `in` names are bound by name to the `VertexFormat` elements. For
`POSITION_TEX` they must be exactly `Position` (vec3) and `UV0` (vec2) — same as vanilla's
`position_tex.vsh`. Our vertex shader deliberately omits the `ProjMat`/`ModelViewMat`
uniforms vanilla uses: we feed positions already in NDC, so `gl_Position = vec4(Position, 1.0)`.

## Shader id → asset path, and lazy compilation

`withVertexShader(Identifier.fromNamespaceAndPath("redstonespecs", "core/blit_uv"))` resolves
to `assets/redstonespecs/shaders/core/blit_uv.vsh` (the `FileToIdConverter("shaders", ".vsh")`
in `ShaderType`). The custom pipeline needs **no explicit registration** — it compiles lazily
the first time a `RenderPass` uses it. If the `.vsh`/`.fsh` are missing or malformed, the
failure surfaces at that first use, not at `RenderPipeline.build()`.

## Nullable platform getters — must null-check in Kotlin

`RenderTarget.getColorTextureView()`, `getColorTexture()`, and `getDepthTexture()` are
`@Nullable` (they return null before buffers are created). Kotlin sees them as nullable, so
`to.colorTextureView` etc. need `requireNotNull(...)` / `!!` before passing to
`createRenderPass` / `bindTexture` / `clearColorTexture`.

## RenderPass creation: clear vs. load

`commandEncoder.createRenderPass(label, colorView, OptionalInt.empty())` **loads** the
existing target contents (draws on top) — pass `OptionalInt.of(argb)` to clear first. For a
sub-rect blit that must leave the edges untouched, use `OptionalInt.empty()`. `CompositeTarget`
clears via `CommandEncoder.clearColorAndDepthTextures(color, argb, depth, 1.0)` (or
`clearColorTexture` when the target has no depth).

## Gotcha: blitting into the main target mid-frame is not screenshot-visible

A blit into `mc.mainRenderTarget` issued outside the present path (e.g. from a client-tick or
test hook) is overwritten by the next full frame render before it is presented, so a
screenshot taken afterward won't show it. Visual confirmation of a composited sub-rect
requires intercepting the present blit itself (a mixin on the surface blit) — you cannot prove
it by drawing into the main target from an ad-hoc hook.
