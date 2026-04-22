# Spec Origin Bounds UI Design

**Date:** 2026-04-21
**Status:** Approved

## Overview

Three related features:
1. Adopt Structure Block visual style across all existing screens and the new bounds sub-screen
2. Add a dedicated `SpecBoundsScreen` for editing origin offset and spec size
3. Ctrl+scroll plane nudging when holding a SpecOrigin block item, with face highlight feedback

---

## 1. Visual Style (all screens)

All three screens (`SpecOverviewScreen`, `SpecEditorScreen`, `SpecBoundsScreen`) adopt vanilla Structure Block conventions:

- Override `isInGameUi(): Boolean = true` — activates `INWORLD_MENU_BACKGROUND` (tiled stone texture)
- Call `super.extractBackground(extractor, mouseX, mouseY, partialTick)` at the start of `extractRenderState` to draw that background
- Draw a dark semi-transparent panel over it: `extractor.fill(panelX, panelY, panelX+panelW, panelY+panelH, 0xB0101010.toInt())` — opacity reduced from `0xCC` to `0xB0` since background already provides contrast
- Title drawn with `extractor.centeredText(font, title, panelX + panelW / 2, panelY + 6, 0xFFFFFF)`
- Widget groups preceded by gray section labels via `extractor.text(font, label, x, y, 0x888888)`

No custom texture asset is needed. This matches how `StructureBlockEditScreen` works in 26.x.

---

## 2. `SpecBoundsScreen`

### Access

New "Bounds…" button in `SpecOverviewScreen.init()` opens the screen directly client-side (no server round-trip — BE data is already synced):

```kotlin
Button.builder(Component.literal("Bounds…")) {
    minecraft?.setScreen(SpecBoundsScreen(originPos))
}.bounds(x + 196, y + panelH - 35, 60, 20).build()
```

### Layout (~260×200 panel)

```
┌──────────────────────────────────────────┐
│            Spec Bounds                   │  centeredText title
│                        [Offset+Size ▼]   │  CycleButton (Offset+Size / Min+Max)
│  Offset                                  │  section label
│  X [-4___] Y [-1___] Z [-4___]           │  3 integer EditBoxes
│  Size                                    │  section label
│  X [9____] Y [5____] Z [9____]           │  3 integer EditBoxes
│                                          │
│  [Save]                     [Cancel]     │
└──────────────────────────────────────────┘
```

In **Min+Max mode** the same 6 boxes show minX/Y/Z and maxX/Y/Z. The `CycleButton` toggles the display mode in-place; boxes repopulate when toggled.

### Coordinate mapping

- **Offset+Size:** `offset = (minX, minY, minZ)`, `size = (maxX-minX+1, maxY-minY+1, maxZ-minZ+1)`
- **Min+Max:** direct `BoundingBox` fields

On toggle: recalculate from current box values and repopulate EditBoxes.

### Save validation

- Size clamped to ≥ 1 per axis
- In Min+Max mode: `min ≤ max` enforced (swap if not)
- Sends `ResizeBoundsC2SPayload(originPos, BoundingBox)`

### New packet

```kotlin
data class ResizeBoundsC2SPayload(val originPos: BlockPos, val bounds: BoundingBox)
// STREAM_CODEC: BlockPos.STREAM_CODEC + ByteBufCodecs.fromCodec(BoundingBox.CODEC)
```

**Server handler:** `be.setSpec(spec.copy(bounds = newBounds))`

---

## 3. Face Highlight + Ctrl+Scroll Resize

### Shared state

In a new file `SpecBoundsInteraction.kt` (client-only):

```kotlin
data class HoveredFace(val originPos: BlockPos, val axis: Int, val isMax: Boolean)
// axis: 0=X, 1=Y, 2=Z

var currentHoveredFace: HoveredFace? = null
```

Written by the tick handler, read by the renderer's `extractRenderState`. Both run on the main thread in 26.x, so no synchronization needed.

### Client tick detection

Added to the existing `ClientTickEvents.END_CLIENT_TICK` handler in `HudOverlayRenderer`:

**Trigger condition:** player holds `ModRegistries.SPEC_ORIGIN_ITEM` in main or off hand AND no screen is open.

**Algorithm:**
1. `SpecOriginBlockEntity.allFor(level)` — iterate all loaded spec origins
2. For each with a spec, build world-space AABB: `minX = be.blockPos.x + bounds.minX()`, etc.
3. Cast camera ray (position + normalized look direction) against AABB using the slab method
4. Collect all intersecting BEs with their hit t-value; pick nearest
5. On that BE, determine the hovered face: compare each slab entry/exit t-value against the hit t to identify the axis and min/max side
6. Write `currentHoveredFace`; write `null` if no intersection or wrong item in hand

### Scroll interception

Registered in `RedstonespecsClient.onInitializeClient()`:

```kotlin
MouseScrollCallback.EVENT.register { _, _, _, _, vertical ->
    if (vertical == 0.0 || !Screen.hasControlDown()) return@register EventResult.pass()
    val face = currentHoveredFace ?: return@register EventResult.pass()
    ClientPlayNetworking.send(
        NudgeSpecBoundsC2SPayload(face.originPos, face.axis, face.isMax, vertical.sign.toInt())
    )
    EventResult.interruptFalse()  // consume scroll, skip vanilla zoom/etc.
}
```

### New packet

```kotlin
data class NudgeSpecBoundsC2SPayload(
    val originPos: BlockPos,
    val axis: Int,     // 0=X, 1=Y, 2=Z
    val isMax: Boolean,
    val delta: Int,    // +1 or -1
)
```

**Server handler** — clamps so no dimension collapses below 1 block:

```kotlin
val b = be.spec?.bounds ?: return
val new = when (axis) {
    0 -> if (isMax) b.copy(maxX = (b.maxX() + delta).coerceAtLeast(b.minX()))
         else b.copy(minX = (b.minX() + delta).coerceAtMost(b.maxX()))
    1 -> // Y equivalent
    2 -> // Z equivalent
    else -> return
}
be.setSpec(spec.copy(bounds = new))
```

Note: `BoundingBox` is immutable but has no `copy()`; server code manually constructs the new `BoundingBox` with the adjusted coordinate.

### Renderer changes

**`SpecOriginRenderState`** gains:
```kotlin
var hoveredFace: HoveredFace? = null
```

**`extractRenderState`** populates it:
```kotlin
state.hoveredFace = if (entity.blockPos == currentHoveredFace?.originPos) currentHoveredFace else null
```

**`submit()`** — if `state.hoveredFace != null`, draw a translucent filled quad on that face using a `RenderTypes.TRANSLUCENT`-compatible vertex consumer:
- Axis 0 (X) → red `0x44FF4444`
- Axis 1 (Y) → green `0x4444FF44`
- Axis 2 (Z) → blue `0x444444FF`

The quad covers the full face (e.g. for max-X face: x=maxX+1, y∈[minY,maxY+1], z∈[minZ,maxZ+1]).

---

## Component Summary

### New files
- `src/client/.../client/SpecBoundsInteraction.kt` — `HoveredFace` data class + `currentHoveredFace` var
- `src/client/.../client/screen/SpecBoundsScreen.kt` — bounds editing screen

### Modified files
| File | Change |
|------|--------|
| `SpecOverviewScreen` | Add `isInGameUi`, `extractBackground`, section labels, "Bounds…" button |
| `SpecEditorScreen` | Add `isInGameUi`, `extractBackground`, section labels |
| `SpecOriginRenderState` | Add `hoveredFace: HoveredFace?` |
| `SpecOriginBlockEntityRenderer` | Extract + render highlighted face quad |
| `HudOverlayRenderer` | Add face detection tick logic + scroll callback registration |
| `RedstonespecsClient` | Register scroll callback |
| `Packets.kt` | Add `ResizeBoundsC2SPayload`, `NudgeSpecBoundsC2SPayload` |
| `NetworkRegistry` | Register both new packets (C2S + handler) |

---

## Open Questions Resolved

- **Sub-screen vs inline**: dedicated `SpecBoundsScreen`
- **Background style**: `extractBackground()` + dark overlay, no custom texture
- **Face highlight**: yes, translucent colored quad per axis
- **Bounds display mode**: both Offset+Size and Min+Max, toggled by `CycleButton`
