# Architecture

High-level system design, module boundaries, data flow, and the invariants that hold across the whole codebase. Use this folder for things that don't belong to a single subsystem.

**Tags:** design, data-model, lifecycle, modules, pipeline, dataflow

## Articles

- [Module map](module-map.md) — Tour of the source tree (`core/spec/`, `core/async/`, `core/events/`, `editor/structure/`, `core/config/`, `ui/`, `playback/`, `testing/`, `editor/`), package responsibilities, dependency direction, and where to start reading for a given concern. Tags: modules, layout, dependencies, entry-points.
- [The record → emit → run → verify pipeline](recording-pipeline.md) — End-to-end flow of a spec from world capture to validation, with handoff types and per-stage invariants — and why it currently has no in-game entry point. Tags: pipeline, lifecycle, recording, finalization, runner, verification, dataflow.
- [Redstone project](redstone-project.md) — Void-dim workspace per folder via runtime datapack; deterministic grid; per-spec bounds save-back. Single-dim+region fallback for first-start / mid-session folders. Tags: redstone-project, dimensions, grid, persistence, datapack.
- [Shrink + composite + Compose overlay — how one frame is built](shrink-viewport-compose-model.md) — How WindowMixin's framebuffer shrink, MinecraftPresentMixin's composite blit, and the full-window blended Compose overlay stack into one presented frame, why the shrink is never gated on a Compose pass, and how MouseHandlerViewportMixin re-maps the raw cursor through the content-rect offset so vanilla-screen clicks land on the shrunk/offset viewport. Tags: compose, viewport, rendering, frame-ordering, input, architecture.
