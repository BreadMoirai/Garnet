---
title: The orbit camera — moving the player to fly the viewport
tags: [camera, input, viewport, spectator, networking]
summary: Why the client owns the orbit camera outright and just moves the player's own entity through spectator — the handleMovePlayer exemption and LocalPlayer's own position sync that make that legal, why entry is lazy and gesture-driven, why the tick loop waits for isSpectator() while the pose itself is committed per render frame with absSnapTo, why the pivot is raycast through the free cursor rather than the crosshair, snapped to the centre of the block that was hit, and re-picked on every press, why pan and dolly translate at a flat world rate instead of scaling with distance, why the server keeps a grant flag so leave cannot demote a spectator it never created, and why exit leaves the player wherever the camera ended.
---

# The orbit camera — moving the player to fly the viewport

`G` frees the cursor for the dock. It does **not** enter camera mode — the first orbit, pan, or
dolly gesture over the bare world viewport does that, lazily. From that point until the dock loses
focus again, the world panel is not a separate render: it is the game's own first-person view, with
the player's `LocalPlayer` entity being driven in a circle around a pivot point while the server
thinks it is spectating.

## The authority split: the client just moves its own player

Every other piece of editor state in this mod is server-authoritative — the client asks, the server
decides and replies. The orbit camera breaks that pattern on purpose: `OrbitCameraController` calls
`absSnapTo` on the client's own `LocalPlayer` every rendered frame, with no packet describing
the new pose going anywhere. `CameraModeC2S` — the *only* payload this subsystem has — asks the
server for exactly one thing: flip this player into spectator, or back out of it. Everything after
that is client-local arithmetic.

Two decompiled-26.2 facts make this legal instead of a desync waiting to happen:

- **`LocalPlayer` already sends its own position to the server every tick**, the same as it does
  during ordinary walking. There is nothing extra to synchronize — the orbit camera just changes
  *what* pose that existing tick loop happens to be sending.
- **The server's move handler exempts spectators from both its distance check and its collision
  check.** Ordinary movement is rubber-banded the moment a claimed position moves further, or
  through more solid geometry, than physics allows in one tick — exactly what an orbit drag looks
  like, since a fast mouse movement can swing the eye tens of blocks around the pivot between two
  ticks. Spectator sidesteps both checks entirely, which is *why* camera mode has to actually enter
  spectator rather than just suppressing collision client-side: without that server-side exemption,
  the very first large orbit swing would get bounced straight back.

Sending orbit deltas to the server and waiting for it to confirm each frame's position was
considered and rejected: that puts a round trip inside every drag frame, which is the one thing a
camera control cannot tolerate.

## Why entry is lazy and gesture-driven, not bound to `G`

`G`'s job stays exactly what it always was: free the cursor so the dock can be clicked. Binding
camera entry to the same key would mean every trip to click an Explorer button also round-trips the
player's gamemode through the server and back — a cost that has nothing to do with why the player
pressed `G`. Instead, camera mode arms itself the moment the first orbit/pan/dolly gesture lands
over the bare world (`DockInputRouter`'s world-gesture branch — see
[dock-input-routing.md](dock-input-routing.md)), and `CameraModeC2S(enter = true)` goes out from
inside that same gesture handler. A dock session that only ever clicks panels never touches
spectator at all.

## The pivot is raycast through the *cursor*, not the crosshair

Camera mode only ever arms while the dock has **freed the cursor**, so the user is pointing at a
block with a real pointer somewhere over the viewport — not with the crosshair. Entry originally
picked the pivot with `Entity.pick`, which casts along the player's view vector, i.e. through the
centre of the rendered image. That is the correct ray in ordinary play, where the crosshair *is* the
pointer, and the wrong one here: it orbits around whatever happened to be dead centre while the user
was clearly aiming somewhere else.

The offset is worse than it first looks, because "dead centre" is not the centre of the window.
While the dock is on screen `WindowMixin` shrinks the reported framebuffer and `MinecraftPresentMixin`
composites the result into the sub-rect the dock's reserved strips leave free (see
[shrink-viewport-compose-model.md](shrink-viewport-compose-model.md)). With a 300 px left strip open,
the crosshair is 150 px to the right of the window's centre.

`camera/data/cursorRay` inverts the projection MC actually builds. `Projection` calls
`Matrix4f.setPerspective(fov * DEG_TO_RAD, width / height, …)`, and JOML's `setPerspective` takes a
**vertical** FOV — which is why the aspect ratio multiplies the horizontal term only:

```
ndcX = 2 * (cursorX - rectX) / rectW - 1        ndcY = 1 - 2 * (cursorY - rectY) / rectH
ray  = normalize(forward + right * (ndcX * aspect * tan(fov/2)) + up * (ndcY * tan(fov/2)))
```

`right` is derived from yaw alone, never from the pitched forward vector: MC's camera has no roll, so
its screen-horizontal axis stays in the world's horizontal plane however steeply you look up or down.
Deriving it from `forward` instead shears both this and `pan` near the poles.

The client-side half of that — reading the content rect, the rendered FOV, and falling back to the
plain view vector when either is unknowable — lives in `camera/input/CursorPick.kt`, because the
hovered-block highlight casts through the same pixels and the two must agree on which block a pixel
names. See [cursor-block-targeting.md](cursor-block-targeting.md).

`DockInputRouter` calls into the controller at the moment it recognizes a world gesture, not when the
lazy entry actually fires — entry happens on the first *move* of a drag, by which point the cursor
has already travelled away from what the user aimed at. Two entry points, because a press and a
scroll want different things:

- **`focusAt(x, y)`** on the press that takes ownership of a drag. Records the aim *and*, when camera
  mode is already armed, re-picks the pivot and turns to face it right there on the press.
- **`aimAt(x, y)`** on a scroll over the world. Records the aim only: a dolly should move the camera,
  not snap the view somewhere new.

The re-pick is not optional polish. `enter()` only runs while the camera is null, and the camera
deliberately survives the end of a drag — so without `focusAt` the pivot was chosen **once per dock
session** and every drag after the first orbited a stale point. Pressing on a block did nothing at
all, which is exactly how it was reported.

### Focusing turns the camera, but never moves it

An `OrbitCamera` looks at its pivot by construction, so a pivot chosen off the view axis cannot also
preserve the player's existing yaw/pitch — carrying the old rotation over would place `eyePosition`
somewhere other than where the player is standing, i.e. teleport them. `orbitAround(eye, pivot)`
resolves this the other way: it derives yaw/pitch from the eye→pivot direction, so the eye stays
exactly put and only the *angle* changes, bringing the block the user pointed at to the centre of the
view. There is a rotational snap at the start of every drag, bounded by half the FOV; this was chosen
over the alternatives (keep the view and use only the hit's *depth* for the pivot; or ease
the rotation over several frames, which needs a transition state the immutable `OrbitCamera` does not
have).

On a miss the ray still gets a pivot — `PIVOT_MISS_DISTANCE` (8 blocks) along it — rather than the
far end of a `PIVOT_RAYCAST_RANGE` (64 block) ray, which would orbit a point in empty sky far behind
anything visible. The clip is `Block.OUTLINE` / `Fluid.NONE`: the block picked should be the one the
user visibly aimed at, the same shape the block highlight draws, rather than one the ray reached by
passing through a fence's empty half, or a water surface whose edges you cannot see. That is not a
simile any more — the highlight really is drawn on this clip's block, because both go through
`CursorPick`.

### The pivot is the block's centre, not the point the ray hit

`camera/data/pivotFor` turns the clip result into an orbit centre, and a hit gives
`Vec3.atCenterOf(hit.blockPos)` — **not** `hit.location`. The raw hit location is where the ray
entered the block's outline, i.e. a point on the *face* turned toward the camera. Orbiting that
sweeps the camera around the block's skin: the picked block slides across the screen as you drag,
and its far side can never be brought into view. Orbiting the cell's centre keeps the block fixed
under the cursor through a full sweep, which is what "orbit this block" means.

The **cell** centre, deliberately, and not the centroid of the shape actually hit. For a slab, a
stair or a fence post the two differ, and a pivot that moves depending on which part of a block you
clicked is harder to predict than one that is always `blockPos + (0.5, 0.5, 0.5)`. The cost — on a
bottom slab the pivot floats a quarter-block above the surface — is a far smaller surprise than the
pivot shifting between two clicks on the same block.

`pivotFor` tests the result's own type rather than only checking for `MISS`, because `MISS` is not
the only non-block outcome: a world-border hit reports type `BLOCK` from a `BlockHitResult` whose
`blockPos` is the clamped border cell.

One knock-on: the pivot now sits roughly half a block *deeper* than the surface, so `distance` at
entry is that much larger. That slightly relieves the sub-block orbit radii described under
[Why pan and dolly translate at a flat world rate](#why-pan-and-dolly-translate-at-a-flat-world-rate)
— it does not remove them, since the pivot is still cursor-picked and often close.

## Why the tick loop waits for `isSpectator()` before moving the player

Entering spectator is a round trip: the client sends `CameraModeC2S(enter = true)` and the server's
reply is the gamemode packet coming back down, not anything synchronous. If the tick loop moved the
player the moment entry was requested, it would be making exactly the large, distance-check-tripping
jump described above while the player is *still* in their old gamemode — precisely the case
`handleMovePlayer` rubber-bands. So `OrbitCameraController.applyTick` checks `player.isSpectator`
every tick and does nothing to the player's pose until it reports true. The gesture itself is never
dropped in the meantime: the immutable `OrbitCamera` state keeps accumulating every orbit/pan/dolly
call regardless, so the moment spectator lands the view snaps straight to wherever the drag has
gotten to, with no lost motion.

That wait needs a way to give up. The server can *refuse* the gamemode flip — a permission problem,
a plugin denying it, anything — and the only thing it sends back on refusal is `EditorErrorS2C`, a
generic error message with no structured "no" the client can act on. There is no way to tell
"refused" apart from "still in flight" except by waiting, and this feature deliberately has exactly
one payload as a design pillar, so a second, dedicated S2C refusal type was ruled out rather than
added. The fallback is `ARM_TIMEOUT_TICKS` (100 ticks, five seconds at 20 TPS): while armed but not
yet spectator, the controller counts ticks, and past the timeout it calls `exit()` on its own rather
than leaving the client waiting forever for an answer that is never coming.

**A refusal latches.** Giving up is not enough on its own: `exit()` clears the camera, but the user
is typically still mid-drag, with the button held and `DockInputRouter` still holding a live
`dragKind` — so the very next mouse move would call `enter()` again, and the user would collect one
enter packet and one "could not enter camera mode" chat error every five seconds for as long as they
kept dragging. So the timeout also sets a latch that blocks further entries. The latch is
deliberately *not* cleared by `exit()`, which also runs at the ordinary end of a drag: it is cleared
only by `OrbitCameraController.resetEntryRefusal()`, called from `DockInputRouter.clearFocus()` (the
end of the whole dock session) and on client disconnect. Ending a drag still keeps camera mode
exactly as before.

The counter itself is only touched in the not-yet-spectator branch, so a refusal that arrives late
can never fire once an orbit has already established spectator — the `isSpectator` check above
returns before the counter is even read.

## Why the pose is committed per *frame*, with `absSnapTo`

This section used to argue the exact opposite — that `applyTick` should call `setPos`/`setYRot` and
leave the previous-pose fields alone so MC's tick interpolator could smooth a 20 Hz camera update
into 144 fps of motion. That reasoning shipped, and it is what made orbiting **jittery**. It is
recorded here rather than deleted because it is a genuinely tempting mistake to make twice.

The flaw is that MC's tick interpolation is not a smoothing filter, it is a *delay*. `Camera.setup`
renders the eye at `Mth.lerp(partialTick, entity.xo, entity.getX())` and the angle at
`Entity.getViewYRot(partialTick)` (which rot-lerps `yRotO` → `yRot`). Committing the pose on
`END_CLIENT_TICK` therefore meant the frame the user sees is always replaying the motion of the tick
*before* — and, worse, replaying it at a rate set by how much mouse movement that particular tick
happened to collect. Mouse deltas arrive per frame; ticks arrive every 50 ms; the two do not divide
evenly, so consecutive ticks hand the interpolator different-sized steps to stretch over the same
50 ms. Uneven step sizes stretched over a fixed interval is the definition of jitter, on top of a
constant 50–100 ms of lag.

Vanilla never does this to the player's own view, and how it avoids it is the fix. `Minecraft.runTick`
drains this frame's GLFW callbacks (`runAllTasks()`), runs zero or more `tick()`s, then calls
`MouseHandler.handleAccumulatedMovement()` — **every frame, before `renderFrame`** — and that path
ends in `Entity.turn`, which advances `xRotO`/`yRotO` by the same delta it adds to `xRot`/`yRot`.
Vanilla mouse-look is applied per frame *and* leaves the interpolator nothing to interpolate.

So the orbit camera does the same thing:

- **`MinecraftFrameMixin`** injects immediately after that `handleAccumulatedMovement()` call and
  invokes `OrbitCameraController.applyFrame`, putting the camera commit on exactly vanilla's
  schedule: after this frame's input, before this frame's render.
- **`applyFrame` calls `absSnapTo`**, which writes `xo`/`yo`/`zo`/`yRotO`/`xRotO` alongside the
  current pose. With a per-frame commit that is precisely right — the pose is already current for
  the frame about to be drawn, so any interpolation away from it is error, not smoothing.

`applyTick` still exists and still runs on `END_CLIENT_TICK`, but only for the state that genuinely
belongs to the tick: noticing a replaced `LocalPlayer`, counting out `ARM_TIMEOUT_TICKS`, and zeroing
delta movement. It deliberately does **not** write the pose, and `applyFrame` deliberately never
calls `exit()` or sends a packet — round trips must not fire at frame rate.

## Why pan and dolly translate at a flat world rate

Both of these were originally scaled by `distance`, on the standard modelling-viewport reasoning:
pan should cover the same *fraction of the screen* at any zoom, and a dolly notch should be the same
*proportional* change in scale whether you are 1 block out or 200. That reasoning assumes you chose
your subject from a comfortable distance. Cursor-picked pivots break the assumption — the pick
routinely lands on a surface less than a block away, and a real client run captured exactly that:

```
hit=BLOCK  eye=(0.85,-64.50,11.88)  pivot=(0.83,-64.00,11.51)  dist=0.62
```

(That trace predates the block-centre pivot above, so its `pivot` is the raw hit location. Snapping
to the cell centre would have pushed `dist` out by up to half a block — still well inside the range
that broke the distance-scaled rates.)

At `distance = 0.62` the old rates gave `0.002 * 0.62` = 0.0012 blocks per pixel of pan — a
500-pixel drag moved the camera **0.6 blocks** — and a multiplicative dolly that asymptoted into the
pivot and stopped dead at `MIN_DISTANCE = 0.5` after about one notch. The controls did not read as
sluggish; they read as a zoom control with no travel left, which is precisely how they were reported.

So both are now world-space:

- **`PAN_SENSITIVITY` is blocks per pixel**, flat. Pan already translated eye and pivot rigidly
  together (the angle and radius are untouched, so `eyePosition` moves by the same vector the pivot
  does); only the rate changed.
- **`DOLLY_STEP` is blocks per scroll notch** along the view axis. The eye moves by exactly
  `scrollDy * DOLLY_STEP` **whatever the clamps do**. Normally that is spent shortening `distance`,
  which is what "move closer" should mean; once `distance` would leave `MIN_DISTANCE..MAX_DISTANCE`
  the remainder is spent pushing the *pivot* along the same axis instead, so you fly straight through
  where the pivot was rather than stalling against it. The identity that makes this exact:

  ```
  eye' = (pivot + view * shortfall) - view * clamped
       = pivot - view * (clamped - shortfall)
       = pivot - view * desired            = eye + view * step
  ```

`distance` is therefore no longer a zoom level — it is only the orbit radius, i.e. how far the
rotation centre sits in front of the camera. `MIN_DISTANCE`/`MAX_DISTANCE` bound that radius and
nothing else.

Both constants are single tunable numbers in `camera/data/OrbitCamera.kt` with no config surface;
`DOLLY_STEP` in particular is deliberately conservative and is the first thing to raise if travel
feels slow.

## Why restore reads vanilla's previous-gamemode field instead of storing one

`CameraModeHandlers.handleCameraMode` does not keep a per-player "gamemode before camera mode"
map of its own. On exit, it reads `player.gameMode.previousGameModeForPlayer` — the same field
`/gamemode` and its own "back" behavior already maintain — and falls back to the server's default
game type only if that field is unset. Storing a separate map here would introduce a second source
of truth for something vanilla already tracks correctly, with all the ways that can drift: a map
entry surviving a relog when it shouldn't, an operator changing the player's gamemode mid-orbit and
leaving camera mode's own record stale, or — worst case — a client that never sends the matching
"leave" payload stranding a per-player map entry with nothing left to clean it up. Reading vanilla's
own field sidesteps all of that: it survives a relog because vanilla already persists it, it cannot
go stale relative to an operator's mid-session gamemode change because there is nothing of ours to
go stale, and there is no map to leak.

### The one piece of state the server *does* keep: the grant flag

Not storing a previous gamemode does **not** mean the server stores nothing. `CameraModeHandlers`
keeps one thing per player: a set of UUIDs it has itself put into spectator for camera mode. That is
authority state — the boolean fact *"the mod granted camera mode to this player"* — and it is a
different kind of thing from duplicating a field vanilla already maintains. Nothing in it is a
`GameType`; restore still reads `previousGameModeForPlayer`.

It exists because without it the leave path is a privilege escalation. Enter no-ops when the player
is already spectating, so it writes nothing, and vanilla leaves no mark distinguishing "spectating
because an operator put them there" from "spectating for camera mode". An unprivileged client could
therefore send `enter` (a server-side no-op) then `leave`, and the leave path would elevate itself to
`ALL_PERMISSIONS` and hand them back `previousGameModeForPlayer` — walking straight out of an
operator-imposed spectator, possibly into creative, without camera mode ever having been granted.
Gating leave on a grant written only by a transition the handler actually performed closes that: a
spectator state the mod did not cause cannot be undone through `CameraModeC2S` at all.

The grant is in memory only and cleared on disconnect, so it cannot leak across sessions or be
resurrected by a relog. `CameraModeSpec` pins the escalation case directly.

### Why the server also restores on disconnect

`Garnet.onInitialize`'s `ServerPlayConnectionEvents.DISCONNECT` block calls
`CameraModeHandlers.handleDisconnect`, which restores any player still holding a grant and drops the
grant either way. The client has its own `ClientPlayConnectionEvents.DISCONNECT` hook, but that hook
can only help a client that is still running: a client that crashes, is killed, or loses its
connection mid-orbit never sends the leave payload, and vanilla persists spectator across a relog —
so without the server-side half that player is stranded in spectator until an operator notices. The
grant flag is exactly what makes this safe to run on every disconnect: a player spectating for any
reason the mod did not cause holds no grant and is left alone. Fabric fires the event from
`Connection.handleDisconnection`, before `PlayerList.remove` saves the player, so the restored
gamemode is the one written to disk.

Restore also goes through the vanilla `/gamemode` command rather than calling `setGameMode`
directly, for the same "don't maintain a second copy of behavior vanilla already has" reason — see
the KDoc on `CameraModeHandlers.handleCameraMode` for that half of the design. The command runs with
elevated permissions scoped to this one call, since an ordinary player does not otherwise have
`Permissions.COMMANDS_GAMEMASTER`; camera mode is a first-class mod feature, not an operator tool, so
the mod grants exactly the permission needed for its own gamemode string and nothing else.

## Exit leaves the player where the camera ended — on purpose

`OrbitCameraController.exit()` does not move the player back to wherever camera mode was entered.
It clears the camera state, tells the server to restore the previous gamemode, and stops — the
player's entity stays exactly where the last `applyFrame` left it, now standing (or floating) there
in whatever gamemode restore lands them in. This is a deliberate choice, not an oversight: an orbit
camera that snapped back to the entry point on exit would throw away the very thing that makes
flying around a structure useful — ending the session looking at the angle you flew to. The two
consequences of that choice are named as open risks in the design rather than silently accepted:
exit-stays-put is effectively a free teleport when restore lands the player back in survival, and
the editor world's lifecycle has not been checked against having a genuinely *spectating* player in
it mid-session. The free teleport also has a sharper edge worth naming: exiting hands the player
back a gamemode with physics and collision at whatever pose the camera happened to end on, so a
survival player can be dropped from altitude to their death, or left suffocating inside solid
geometry the camera flew through. Both are scoped out of this feature's own work, not solved here.

## `clearFocus()` is the single exit choke point

`OrbitCameraController.exit()` is called from `DockInputRouter.clearFocus()`, not from the `G`-key
branch of the input handler directly. `clearFocus()` is where **every** way of dropping dock focus
converges — `G`, ESC, and any future path that drops focus — so routing the camera's exit through it
means none of those paths can forget to call it. Wiring `exit()` only into `G`'s own branch was
tried first and left the identical bug reachable through ESC: a player who dropped focus with ESC
while still orbiting would stay in spectator, with `applyFrame` overwriting their entity's position
every frame, control back in the game and no way to move or look around. Because `exit()` lives in
the one place every exit path already passes through, that failure mode is structurally impossible
rather than merely tested against.

## Where the pieces live

- `camera/data/OrbitCamera.kt` — the pure, immutable pivot/yaw/pitch/distance state and its
  `orbit`/`pan`/`dolly` transforms, the `rightVector`/`upVector` basis they share with `cursorRay`,
  the `cursorRay` projection inverse, `pivotFor`'s hit → block-centre choice, the `orbitAround` entry
  seed, and the derived `eyePosition`. No Minecraft client dependency; unit-tested directly by
  `OrbitCameraTest`, `CursorRayTest` and `PivotTest`.
- `camera/network/CameraPackets.kt` + `CameraModeHandlers.kt` — the one C2S payload, its server
  handler, the per-player grant flag, and the disconnect restore. Registration lives in
  `editor/network/EditorNetworkRegistry.kt` (the payload and its receiver) and in `Garnet.kt`'s
  `ServerPlayConnectionEvents.DISCONNECT` block (the restore), beside the other per-player server
  state cleared there.
- `camera/input/OrbitCameraController.kt` — `applyFrame` writes the pose onto the `LocalPlayer` every
  rendered frame, `applyTick` owns the tick-scoped bookkeeping (lost player, arm timeout, delta
  zeroing); also owns entry, the cursor aim, and exit.
- `camera/input/CursorPick.kt` — the free-cursor ray (the projection inverse above) and the
  `Block.OUTLINE` / `Fluid.NONE` clip, held in one stateless place because the hovered-block
  highlight raycasts through the same pixels and must name the same block; see
  [cursor-block-targeting.md](cursor-block-targeting.md).
- `mixin/client/MinecraftFrameMixin.java` — the per-frame hook, injected after
  `MouseHandler.handleAccumulatedMovement()` in `Minecraft.runTick` so the camera commit lands on
  exactly the schedule vanilla mouse-look uses.
- `dock/input/DockInputRouter.kt` — routes world-viewport gestures (LMB drag / MMB drag / scroll)
  into the controller; see [dock-input-routing.md](dock-input-routing.md) for the routing mechanics
  and the swallowed-press bookkeeping that keeps those gestures from leaking into the Compose scene.
- `src/clientTest/kotlin/.../camera/OrbitCameraSpec.kt` — the end-to-end gesture spec, driving the
  real router entry points against a real client.

## Deliberately out of scope

No config surface for the sensitivity/clamp constants in `camera/data/` — they are tuned as
constants, and a config surface is a follow-up with real usage behind it rather than speculative
work here. No pivot-indicator render — marking the orbit pivot on screen would help orientation, but
it is a render-pipeline change and this feature touches none.
