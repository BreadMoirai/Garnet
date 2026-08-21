---
title: Block targeting under the free cursor — the hovered-block highlight
tags: [camera, input, rendering, mixin, viewport]
summary: Why the hovered block gets vanilla's outline by replacing Minecraft.hitResult rather than by drawing anything, why aiming the pick is only half of it — GameRenderer.shouldRenderBlockOutline suppresses a spectator's outline, and camera mode makes the player one, so a mixin forces it back on under the free cursor — the CursorPick seam the highlight shares with the orbit pivot, why the ray starts at the render camera instead of the OrbitCamera, and why a miss is installed rather than falling back to the crosshair.
---

# Block targeting under the free cursor — the hovered-block highlight

While the dock has focus, the cursor is free and the pointer is over the bare world viewport, the
block under the **pointer** gets vanilla's block outline — the same highlight a player targeting a
block normally sees. It is live before any camera has been armed, so hovering never round-trips the
player's gamemode; see [orbit-camera.md](orbit-camera.md) for what a *gesture* over the same pixels
does instead.

## Two halves: aim the pick, then let it draw

The feature is **not** one field, though the aiming half is.

### Aiming is one field

MC 26.2 decides *which* block the outline goes around entirely from `Minecraft.hitResult`.
`LevelExtractor#extractBlockOutline` reads that field, skips a `MISS` or an air block, and builds the
`BlockOutlineRenderState` that `LevelRenderer#submitBlockOutline` draws. Neither of those consults
the gamemode, so aiming is just "point the existing hit result at the pointer", implemented as a
`TAIL` inject on `Minecraft#pick(F)V` (`mixin/client/MinecraftPickMixin.java`).

The rejected alternative was a mixin on `LevelExtractor#extractBlockOutline` that builds a
`BlockOutlineRenderState` itself: purely visual and with no interaction surface at all, but it would
have meant re-deriving vanilla's translucency flag, high-contrast option and shape lookup —
render-pipeline logic that moves between MC versions, in a mod that already carries a Stonecutter
version matrix.

### Whether it draws at all is decided somewhere else — and spectator fails it

`LevelExtractor` and `LevelRenderer` being gate-free is easy to mistake for "there is no spectator
gate anywhere", which is what an earlier version of this article claimed. It is wrong, and it cost a
bug. The gate sits one level **up**, in `GameRenderer#shouldRenderBlockOutline()`, whose boolean
`renderLevel` passes to `LevelRenderer#render` as `renderOutline` and which is what ultimately
reaches `submitBlockOutline`:

```java
boolean renderOutline = cameraEntity instanceof Player && !this.minecraft.gui.hud.isHidden();
if (renderOutline && !((Player)cameraEntity).getAbilities().mayBuild) {
    ...
    if (this.minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
        renderOutline = blockState.getMenuProvider(this.minecraft.level, pos) != null;
    } else { // adventure
        renderOutline = !itemStack.isEmpty() && (itemStack.canBreakBlockInAdventureMode(...) || ...);
    }
}
```

`GameType.SPECTATOR` is block-placing-restricted, so a spectator's `abilities.mayBuild` is false and
the restricted branch is taken — and inside it a spectator outlines **only** blocks that open a
container menu. Every ordinary block is suppressed.

That is exactly the state camera mode leaves the player in. Hovering alone never arms it, so the
highlight worked perfectly until the user's first pan, zoom or orbit; `OrbitCameraController.enter`
then put the player into spectator and the outline vanished for the rest of the session, with a
completely correct `hitResult` underneath it the whole time. A bug that looks like a pick bug and
isn't.

`mixin/client/GameRendererOutlineMixin.java` defeats it: a `RETURN` inject on
`shouldRenderBlockOutline()` that forces `true` when `DockInputRouter.hoveringWorld` is. It is gated
on that **same single condition** the pick override uses, rather than forced unconditionally, for two
reasons. Outside garnet mode the cursor is grabbed and the crosshair genuinely is the pointer, so
vanilla's rules — hidden HUD, adventure restrictions, spectator — are the right ones and keep
applying in full. And sharing the condition is what stops the two halves from disagreeing: a frame
that picks under the cursor is exactly a frame that draws under the cursor.

Forcing `true` on a frame where the cursor is over sky is harmless — the hit result is then a `MISS`,
which `extractBlockOutline` skips whatever the flag says.

### Overwriting `hitResult` is the honest semantics, not a shortcut

While the cursor is free, vanilla's crosshair-cast result is not a *stale* answer to "what is the
user pointing at" — it is an answer to a different question nobody asked, aimed at the centre of the
shrunk content sub-rect the dock leaves free (see
[shrink-viewport-compose-model.md](../architecture/shrink-viewport-compose-model.md)). Replacing it
keeps every reader of that field consistent with what the user sees, the F3 targeted-block readout
included.

The interaction paths that also read `hitResult` are unreachable in this window: dock focus means
`MouseHandlerMixin` swallows presses before vanilla's key mappings ever go down, so
`continueAttack`/`startUseItem` never fire against the substituted result. `crosshairPickEntity` is
deliberately left holding the crosshair's entity — this feature is about blocks, and nothing consults
that field while the dock is focused.

## A miss is installed, not treated as "no answer"

`WorldHover.pickResult` returns the raycast result **including a `MISS`**, and null only when the
feature does not apply at all (no dock focus, cursor over a panel, framebuffer geometry unknown, no
player or initialized render camera).

The distinction matters. With the cursor free over open sky the honest answer is "nothing targeted",
and `Level.clip`'s `MISS` says exactly that — `LevelExtractor` skips it and no outline is drawn.
Returning null there instead would fall back to whatever vanilla's crosshair pick found, outlining a
block behind the middle of the screen that the user is demonstrably not pointing at. That is the
original bug, reintroduced on every miss.

## The ray starts at the render camera

`WorldHover` takes its eye and angles from `mc.gameRenderer.mainCamera()`, **not** from
`OrbitCameraController`'s `OrbitCamera`. A ray that inverts the projection has to start where the
projection did, and the two disagree in two ordinary cases:

- The highlight is live **before** any camera has been armed, when the rendered view is the player's
  own eye and there is no `OrbitCamera` at all.
- While an armed entry waits out the spectator round trip, the `OrbitCamera` has been accumulating
  gestures but `applyFrame` has not committed them yet, so the rendered view is still the player's.

The render camera is right in both, and costs nothing: `Minecraft.runTick` calls
`gameRenderer.update(...)` — which updates `mainCamera` — immediately before `pick(...)`, so the
camera is already current for this frame when the mixin runs.

The pivot pick goes the other way for the same reason, and deliberately: `OrbitCameraController`
casts from where the *camera* is, because a gesture must keep accumulating against the camera it is
driving even while the entity has not caught up.

## `CursorPick`: one projection inverse, two callers

`camera/input/CursorPick.kt` holds the ray construction and the clip settings, and holds no state.
Both callers pass their own cursor position and their own eye/angle:

| | cursor position | eye / angle |
|---|---|---|
| `WorldHover` (highlight) | live, updated on **every** move | render camera |
| `OrbitCameraController` (pivot) | the **press** position (`aimAt`) | the `OrbitCamera` |

They must agree about which block a given pixel names — a highlight that outlines one block while the
press orbits another is worse than no highlight at all — which is why the projection inverse and the
`Block.OUTLINE` / `Fluid.NONE` clip live in one place rather than two.

The two cursor positions cannot be merged into one field. Entry is lazy: it fires on the first
*move* of a drag, by which point the cursor has already travelled away from what the user aimed at,
so `aimAt` must keep holding the press position. The highlight wants the exact opposite — where the
cursor is *now*.

### The range is 64 blocks, not the player's reach

`PIVOT_RAYCAST_RANGE` is shared with the pivot pick rather than using the player's block-interaction
reach. This is an editor viewport, not gameplay: the camera routinely sits further from a structure
than a player could reach into it, and a highlight that vanished at 4.5 blocks would disagree with
the click that orbits the very same block.

## When it applies

`DockInputRouter.hoveringWorld` is the single gate, and all three of its clauses are load-bearing:

- **`captured`** — without dock focus the cursor is grabbed and the crosshair genuinely *is* the
  pointer, so vanilla's own result is already right.
- **`regionUnderCursor() == null`** — otherwise a cursor resting on a panel would keep outlining
  whatever block lies behind it.
- **`geometryKnown`** — a startup or resize race answers `null` ("the world") from a zero-sized
  window, and the ray would be built from a content rect that does not exist.

It is deliberately true **during** a world drag as well. The highlight simply follows the cursor;
suppressing it mid-orbit would blink it off for the whole gesture.

Hovering is not a gesture: `WorldHover.moveTo` records a position and nothing else, so looking around
never arms camera mode. `WorldHoverSpec` pins that explicitly.

## Where the pieces live

- `camera/input/WorldHover.kt` — the live cursor position and `pickResult`, the gate and the pose
  choice.
- `camera/input/CursorPick.kt` — the projection inverse (`ray`) and the clip (`clip`), shared with
  the pivot pick.
- `camera/data/OrbitCamera.kt` — `cursorRay` and the `rightVector`/`upVector` basis it is built on;
  pure, and unit-tested by `CursorRayTest`.
- `mixin/client/MinecraftPickMixin.java` — the `TAIL` inject on `Minecraft#pick` that aims the hit.
- `mixin/client/GameRendererOutlineMixin.java` — the `RETURN` inject on
  `GameRenderer#shouldRenderBlockOutline` that defeats the spectator gate, so the aimed hit
  actually draws.
- `dock/input/DockInputRouter.kt` — feeds `WorldHover.moveTo` on every move and owns `hoveringWorld`.
- `src/clientTest/kotlin/.../camera/BlockOutlineGateSpec.kt` — the render-gate spec: it puts the
  client into *local* spectator (no packet, no server grant) and reads
  `shouldRenderBlockOutline` reflectively — true under the free cursor, still false for a
  spectator whose cursor is grabbed or resting on a panel.
- `src/clientTest/kotlin/.../camera/WorldHoverSpec.kt` — the end-to-end spec, which measures the
  *angle* between the installed hit and the camera's view axis rather than asserting a `BlockPos`, so
  it holds in whatever world the harness spawns in and cannot pass with a crosshair pick.

## See also

- [orbit-camera.md](orbit-camera.md) — the camera the cursor ray was originally built for, and what a
  drag over the same pixels does.
- [dock-input-routing.md](dock-input-routing.md) — how a move reaches `DockInputRouter` at all, and
  the world-vs-region hit test `hoveringWorld` is built on.
