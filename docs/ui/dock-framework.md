---
title: RedstoneDock — full-window Compose dock over the world composite
tags: [compose, dock, layout, panels, input, rendering]
summary: How RedstoneDock lays out LEFT/RIGHT/BOTTOM/CENTER regions at real framebuffer pixels via ComposeSceneHost, why the center is transparent by omission, and two Compose 1.12 API gotchas.
---

# RedstoneDock — full-window Compose dock

The dock is a single `@Composable` (`RedstoneDock(realW, realH)`,
`src/client/kotlin/.../ui/compose/dock/RedstoneDock.kt`) hosted full-window by
`ComposeSceneHost` and blitted over the world composite by `ComposeSurface`. It replaced the
feasibility spike's `ComposeScenePanel` demo (button + `clickCount`), which was deleted.

## Hosting: `ComposeSceneHost`

`ComposeSceneHost(width, height, content)` is a generic `ImageComposeScene` wrapper (the spike's
`ComposeScenePanel` generalized: content is now a constructor parameter). It renders the tree to a CPU
raster `org.jetbrains.skia.Image` each frame (`render(nanos)`) and exposes pointer/scroll/key
forwarders (`pointerMove/Press/Release`, `scroll`, `sendKey`) for Task 4's input routing.
`ComposeSurface.ensureHost(w, h)` recreates it on window-size change and hosts `RedstoneDock(w, h)`.

## Layout is in **real framebuffer pixels**

The scene runs at `Density(1f)`, so `dp == px`. Region sizes come straight from `DockState`
(`leftWidth`, `rightWidth`, `bottomHeight`, all plain Int px) and are placed with absolute
`Modifier.offset(x.dp, y.dp).width(...).height(...)`. This is deliberate: the same integer geometry
drives both the Compose layout and the viewport framebuffer shrink (`DockInsets`), so a splitter drag
that writes `DockState.setSize(...)` moves the panel edge and the world inset in lockstep with no
density conversion. LEFT/RIGHT columns stop above the BOTTOM region (`height = realH - bottom`); BOTTOM
spans the full width and owns the bottom-left/right corners.

## The center is transparent **by omission**, not by clear-color

`RedstoneDock`'s root `Box(Modifier.fillMaxSize())` has **no** `background` modifier. Only the visible
edge regions paint an opaque `PANEL_BG`; the CENTER paints nothing unless a center panel exists. Skia's
canvas is pre-cleared to `0x00000000` in `ComposeSurface`, so every un-painted pixel stays fully
transparent and the composited world shows through. Do not add a background to the root Box — it would
occlude the world. (See `compose-blended-overlay.md` for the premultiplied-alpha blend that composites
these transparent pixels.)

## Two Compose 1.12 API gotchas

- **`detectTapGestures` must be imported, not fully-qualified.** A fully-qualified call
  `androidx.compose.foundation.gestures.detectTapGestures(...)` fails to resolve ("Unresolved
  reference") in foundation 1.12; `detectDragGestures` at the same package resolves only because it is
  imported. Import both and call unqualified.
- **Splitter has two overloads that differ only by lambda arity.** The full `Splitter(Modifier,
  (dx, dy) -> Unit)` and the horizontal convenience `(Modifier, (dx) -> Unit)` don't clash at the JVM
  level (`Function2` vs `Function1`), but to avoid call-site overload ambiguity the horizontal one is
  named `SplitterX`. LEFT/RIGHT use `SplitterX`; BOTTOM uses the two-arg `Splitter` and reads `dy`.

## `ImageComposeScene` input API (verified against 1.12.0-beta02)

`sendPointerEvent(eventType, position, scrollDelta = Offset(...))` — the scroll delta parameter is
named `scrollDelta`. `sendKeyEvent(KeyEvent): Boolean`. Both confirmed against the `ui-desktop` jar.
