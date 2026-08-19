---
title: The orbit camera — moving the player to fly the viewport
tags: [camera, input, viewport, spectator, networking]
summary: Why the client owns the orbit camera outright and just moves the player's own entity through spectator — the handleMovePlayer exemption and LocalPlayer's own position sync that make that legal, why entry is lazy and gesture-driven, why the tick loop waits for isSpectator() and uses setPos/setYRot instead of the obviously-correct absSnapTo, why the server keeps a grant flag so leave cannot demote a spectator it never created, and why exit leaves the player wherever the camera ended.
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
`setPos`/`setYRot`/`setXRot` on the client's own `LocalPlayer` every tick, with no packet describing
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

## Why `setPos`/`setYRot`, not `absSnapTo`

This is the one line in the subsystem a future reader is most likely to "fix" back, so it is worth
stating plainly: **`applyTick` deliberately calls `setPos`/`setYRot`/`setXRot`, not
`absSnapTo`.**

`absSnapTo` looks like the obviously-correct call for "teleport the player to an exact pose" — and
for an actual teleport it would be right. But `absSnapTo` also overwrites the *previous*-tick pose
fields (`xo`/`yo`/`zo`, `yRotO`, `xRotO`), which is precisely what a real teleport needs: there is no
old position worth interpolating from, so the renderer should not try. A 20 Hz camera update is the
opposite case. The client renders at whatever the display's frame rate is, and between two ticks it
interpolates the rendered pose from the previous tick's fields to the current ones so a 20 Hz update
reads as smooth motion at 144 fps instead of a visible step every tick. Calling `absSnapTo` here
would erase the "previous" side of that interpolation every single tick, turning every orbit drag
into a strobe of discrete jumps instead of a smooth pan. `setPos`/`setYRot` update only the current
pose and leave the previous-tick fields for the interpolator to read, which is exactly the behavior
a moving camera needs. Entry itself needs no interpolation to worry about: it seeds the `OrbitCamera`
from the player's *current* pose before sending `CameraModeC2S`, so there is never a jump between
"not in camera mode" and "in camera mode, but the view already matches" for the renderer to smear.

## Why pan scales with distance, and dolly is multiplicative

Panning slides the pivot across the camera's own right/up plane by an amount proportional to the
current `distance`. Fixed-size panning would crawl to uselessness once zoomed out over a large
structure — the same mouse movement that comfortably repositions a close-up pivot would barely nudge
one twenty blocks further out. Scaling by distance keeps a given drag covering roughly the same
*fraction of the screen* regardless of zoom level.

Dolly (scroll-to-zoom) multiplies `distance` by a constant factor per notch rather than adding or
subtracting a fixed amount. Additive zoom feels wildly different at different scales: the same
scroll notch that is imperceptible at `distance = 200` would rocket the camera straight through the
pivot at `distance = 1`. A multiplicative step feels like the same *proportional* change in scale no
matter how far out the camera already is, which is what "zoom" is expected to feel like.

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
player's entity stays exactly where the last `applyTick` left it, now standing (or floating) there
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
while still orbiting would stay in spectator, with `applyTick` overwriting their entity's position
every tick, control back in the game and no way to move or look around. Because `exit()` lives in
the one place every exit path already passes through, that failure mode is structurally impossible
rather than merely tested against.

## Where the pieces live

- `camera/data/OrbitCamera.kt` — the pure, immutable pivot/yaw/pitch/distance state and its
  `orbit`/`pan`/`dolly` transforms plus the derived `eyePosition`. No Minecraft client dependency;
  unit-tested directly.
- `camera/network/CameraPackets.kt` + `CameraModeHandlers.kt` — the one C2S payload, its server
  handler, the per-player grant flag, and the disconnect restore. Registration lives in
  `editor/network/EditorNetworkRegistry.kt` (the payload and its receiver) and in `Garnet.kt`'s
  `ServerPlayConnectionEvents.DISCONNECT` block (the restore), beside the other per-player server
  state cleared there.
- `camera/input/OrbitCameraController.kt` — applies the camera to the `LocalPlayer` every client
  tick; owns entry, the arm timeout, and exit.
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
