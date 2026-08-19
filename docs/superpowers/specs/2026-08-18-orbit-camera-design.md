# Orbit camera for the freed-cursor viewport

**Date:** 2026-08-18
**Status:** design approved, not yet implemented

## Context

`G` (`key.garnet.dock_focus`, shipped in `b6a56bc`) already toggles between "cursor grabbed for
play" and "cursor freed, dock has keyboard and pointer focus". See
`docs/ui/dock-input-routing.md#g--the-dock-focus-toggle-dockfocuskeybindkt`.

While the cursor is freed, the bare world viewport is currently inert: the only gesture it accepts
is a click, which means *return to the game*. This spec makes that freed-cursor state also drive a
3D camera over the world — orbit, pan, and dolly — the way a modeling application's viewport
behaves.

Related:
- `docs/architecture/shrink-viewport-compose-model.md` — how the world viewport is shrunk and
  composited, and which rect the "bare world" actually is.
- `docs/ui/dock-input-routing.md` — the router this modifies, including the world-click gesture
  this spec deletes.
- `docs/persistence/network-payload-contract.md` — the C2S/S2C authority model the one new payload
  follows.

## Scope

Touches the client (a new `camera/` feature package, and the world branch of `DockInputRouter`),
the protocol (one new C2S payload plus its server handler), and the docs.

It does **not** touch the render pipeline. The camera is not a render override: the player entity
itself moves, in spectator mode, and vanilla's existing camera follows it.

## 1. The authority split

**The client owns the camera; the server owns only the gamemode.**

Two facts, both verified against decompiled MC 26.2, are what make this cheap:

- `ServerGamePacketListenerImpl.handleMovePlayer` exempts spectators from both the
  `movedDist > 0.0625` "moved wrongly!" check and the new-collision test (a spectator has
  `noPhysics`). A spectating client may therefore move its own player arbitrarily and the server
  accepts it.
- `LocalPlayer` already sends `ServerboundMovePlayerPacket` from its own tick whenever its position
  or rotation differs from what it last sent.

Together: mutating `LocalPlayer`'s position each client tick *is* the sync. There is no per-frame
payload, and no orbit latency.

Only the gamemode transition is server state, and it is a single request each way.

### Rejected alternatives

- **Mixin `Camera`/`GameRenderer` and leave the player alone.** A pure render-side override, no
  server involvement whatsoever, and the smallest possible change. Rejected deliberately: the
  requirement is that the player *moves* and enters spectator. A render-only camera is a freecam —
  it sees through walls while the player's body stays put — which is the thing this design is
  choosing not to be.
- **Send orbit deltas to the server and let it teleport the player.** Strictly correct authority,
  but every drag frame becomes a round trip and the orbit visibly lags the mouse. Rejected on feel.

## 2. `OrbitCamera` — the pure state

Lives in `camera/data/OrbitCamera.kt`. No Minecraft imports, so it is unit-tested in `src/test`
with no client — the same split `DockHitTest` and `DockFocusTarget` already use.

State: `pivot` (world position), `yaw`, `pitch`, `distance`.

Operations:

- **`orbit(dx, dy)`** — adds to yaw and pitch from a mouse delta. Yaw wraps. Pitch is clamped just
  shy of ±90°, never *to* ±90°: at exactly vertical the look-at basis degenerates and the camera
  rolls unpredictably as it crosses over.
- **`pan(dx, dy)`** — translates the **pivot** along the camera's right and up basis vectors.
  Scaled by `distance`, so a given mouse movement covers the same fraction of the screen whether
  you are close in or far out; unscaled panning is unusable at range.
- **`dolly(scrollDy)`** — **multiplicative** on `distance`, not additive, for the same reason:
  constant-feel zoom at every scale. Clamped to a floor so the camera cannot reach or invert
  through the pivot.

Derived: `eyePosition` (pivot offset by `distance` along the yaw/pitch direction) and the
`(yRot, xRot)` pair that looks back at the pivot.

## 3. `OrbitCameraController` — the Minecraft-facing half

Lives in `camera/input/`. Deliberately thin — everything with arithmetic in it belongs in section 2.

Holds the active `OrbitCamera`, or `null` when camera mode is off.

**Applying it.** Each client tick, writes the derived position and rotation onto `LocalPlayer`,
setting **both** the current and the previous-tick fields (`xo/yo/zo`, `yRotO`, `xRotO`). Setting
only the current values leaves the render interpolator lerping from the previous tick's pose and
the orbit smears; this is the non-obvious part of the file and earns a comment.

**Entry (lazy).** Camera mode is *not* entered by `G`. `G` keeps its current meaning — free the
cursor for the dock — because flipping gamemode every time you want to click an Explorer button is
not acceptable. Instead the first orbit/pan/dolly gesture over the bare world enters it:

1. Raycast from the player's eye along their current view. The hit point becomes `pivot`. **On a
   miss, the pivot is placed at a fixed 8 blocks along the view ray** so there is always something
   to orbit.
2. Seed `yaw`, `pitch`, and `distance` from the player's *current* pose, so entering camera mode is
   visually a no-op — the view only changes once you actually drag.
3. Send `CameraModeC2S(enter = true)`.
4. **Accumulate the gesture but do not move the player until `isSpectator()` is actually true.**
   The gamemode change is a round trip; moving during it would be a non-spectator making a large
   position jump, which is exactly the case `handleMovePlayer` rubber-bands. The gesture is not
   dropped — it is replayed once spectator lands, so the drag feels continuous.

**Exit.** Sends `CameraModeC2S(enter = false)` and clears the state. **The player stays wherever
the camera ended** — this is a deliberate choice, and it means camera mode doubles as a way to
travel. Exit is triggered by:

- `G` (the existing exit half in `DockInputRouter.onGlfwKey`),
- client disconnect,
- dimension change,
- player death.

The last three are not tidiness. A client that drops mid-orbit and leaves a player permanently
stuck in spectator is the failure mode this list exists to prevent.

## 4. `CameraModeC2S` — the one payload

Lives in `camera/network/`, registered on the existing `editor/network/EditorNetworkRegistry` spine
and using `payloadId()` from `editor/network/PayloadIds.kt`.

`CameraModeC2S(enter: Boolean)`. The server handler runs the **vanilla `/gamemode` command**
rather than calling `ServerPlayer.setGameMode` directly:

- `enter = true` → `gamemode spectator`.
- `enter = false` → `gamemode <previous>`, where previous is
  `ServerPlayerGameMode.getPreviousGameModeForPlayer()`. Vanilla already tracks this per player —
  it is the same field `/gamemode` and back uses — so this spec stores no previous-gamemode state
  of its own.

Going through the command is deliberate. It is the path every other gamemode change in the game
takes, so camera mode inherits `GameModeCommand`'s handling and feedback and stays consistent with
it as vanilla evolves, instead of drifting the moment a step is added there.

The cost is that `/gamemode` gates on `Permissions.COMMANDS_GAMEMASTER`, which an ordinary player
does not hold. The handler therefore runs the command on an **elevated** source
(`createCommandSourceStack().withPermission(PermissionSet.ALL_PERMISSIONS).withSuppressedOutput()`).
That is a real grant and worth stating plainly: camera mode is a first-class feature of this mod
rather than an operator tool, so the mod grants it. The elevation is scoped to one command string,
built server-side from a `GameType` and never from anything the client sent — the payload carries a
single boolean, so there is no path from client input into the command text.

Follows the established contract (`docs/persistence/network-payload-contract.md`): the server is
the only writer, and a refusal replies `EditorErrorS2C(reason)` rather than failing silently. A
refused entry must leave the client's camera state cleared, not half-armed.

## 5. Input routing — the behavior that changes

In `DockInputRouter`, the world branch (`regionUnderCursor() == null`) stops meaning "leave".

| Gesture over the bare world, cursor freed | Today | After |
|---|---|---|
| LMB press/drag | drops focus, returns to game | begins an orbit drag |
| MMB drag | routed to Compose | pans the pivot |
| Scroll | routed to Compose | dollies in/out |
| `G` | returns to game | unchanged — and now the **only** way back |

Gestures over a dock *region* are untouched: pointer and scroll still reach Compose exactly as
today. Only the `null`-region branch is rewritten.

`geometryKnown` still gates everything. Before `WindowMixin` has cached a real framebuffer size the
layout is unknowable, and a `null` from `regionAt` does not yet mean "the world".

### Deletions this forces

`swallowRelease` / `consumeSwallowedRelease` in `DockInputRouter`, and the `MouseHandlerMixin`
release branch that consults them, become dead code. They exist only because the world press
*dropped capture*, which made the matching release arrive uncaptured and reach vanilla as an
unmatched button-up. After this change the world press keeps capture, so the release arrives
captured and routes normally.

Per the standing "fix what you find" rule these are removed in the same change, along with:

- `DockInputSpec` step 7 (click-to-return-to-game, and its `consumeSwallowedRelease` assertion),
- the "World → game" bullet and the swallow-release paragraph in
  `docs/ui/dock-input-routing.md`,
- the sentence in `docs/architecture/shrink-viewport-compose-model.md` that describes a world click
  as the way out.

## 6. Constants

Orbit sensitivity, pan sensitivity, dolly factor, the pitch clamp, the minimum distance, and the
8-block raycast fallback are constants in `camera/data/`. **No config surface** — if they turn out
to need tuning, that is a follow-up with real usage behind it, not speculative settings.

## 7. Testing

**`OrbitCameraTest` (`src/test`, no client).** Pins the arithmetic, which is all of the logic worth
pinning:

- orbit wraps yaw across the 360° boundary and clamps pitch short of both poles,
- pan translates the pivot along the correct right/up basis and scales with distance,
- dolly is multiplicative and clamps at the floor rather than passing through the pivot,
- the derived look-at `(yRot, xRot)` is correct for the cardinal directions and for a known
  oblique case.

**A clientTest spec**, driving the real router the way `DockFocusKeybindSpec` drives both halves of
`G`:

- a drag over the bare world orbits and does **not** drop `focusedRegion`,
- a drag over a visible dock region still reaches Compose and does not touch the camera,
- scroll over the world dollies; scroll over a region still scrolls the panel,
- `G` while camera mode is active exits it,
- entry does not move the player before spectator is confirmed.

## Open risks

- **Spectator in the editor dimension.** The editor world's own lifecycle
  (`EditorDimLifecycle`, `EditorCellSaver`) has not been checked against a player who is spectating
  rather than building. Worth a look during implementation; nothing here assumes it is a problem.
- **Exit-stays-put in survival.** Because the player keeps the camera's final position, camera mode
  is a free teleport in any non-creative context. Accepted knowingly: this is an IDE for building,
  not an anticheat-sensitive mod.
