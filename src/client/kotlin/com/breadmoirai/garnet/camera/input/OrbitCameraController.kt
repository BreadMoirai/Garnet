package com.breadmoirai.garnet.camera.input

import com.breadmoirai.garnet.camera.data.OrbitCamera
import com.breadmoirai.garnet.camera.data.PIVOT_MISS_DISTANCE
import com.breadmoirai.garnet.camera.data.dolly
import com.breadmoirai.garnet.camera.data.eyePosition
import com.breadmoirai.garnet.camera.data.orbit
import com.breadmoirai.garnet.camera.data.orbitAround
import com.breadmoirai.garnet.camera.data.pan
import com.breadmoirai.garnet.camera.data.pivotFor
import com.breadmoirai.garnet.camera.data.xRot
import com.breadmoirai.garnet.camera.data.yRot
import com.breadmoirai.garnet.camera.network.CameraModeC2S
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3

/**
 * Drives the world viewport's orbit camera by moving the player's own entity while it spectates.
 *
 * Entry is **lazy and gesture-driven**, not bound to `G`. `G` keeps its existing meaning — free the
 * cursor for the dock — because round-tripping the player's gamemode through the server every time
 * they want to click an Explorer button is not acceptable. The first orbit/pan/dolly over the bare
 * world enters camera mode instead.
 *
 * **Threading:** every field below is written from the gesture path (`DockInputRouter`) and read or
 * written again from [applyTick] on `ClientTickEvents.END_CLIENT_TICK` and from [applyFrame] on
 * `MinecraftFrameMixin`'s per-frame hook, with no synchronization and no `@Volatile` — because all
 * three are the client's main (render) thread. `MouseHandler`/`KeyboardHandler` wrap their GLFW
 * callbacks in `minecraft.execute(...)`, so the mixin entry points that reach the gesture path
 * already run on the same thread as the tick event and the render loop that drains those tasks. See
 * `DockInputRouter`'s own threading note, which this deliberately matches.
 */
object OrbitCameraController {

    /**
     * How long to wait, in client ticks, for spectator to land before giving up on an entry.
     *
     * The server can refuse the gamemode flip — it answers only with `EditorErrorS2C` and nothing
     * else — so the client has no positive signal that distinguishes "refused" from "still in
     * flight"; it can only time out. A dedicated S2C refusal payload would remove the ambiguity,
     * but this feature deliberately has exactly one payload (`CameraModeC2S`) as a design pillar,
     * so the fix is a bounded wait rather than a second message. 100 ticks is 5 seconds at 20
     * TPS: generous enough that no real round trip is mistaken for a refusal, short enough that a
     * genuine refusal does not leave the feature dead until the process restarts.
     */
    private const val ARM_TIMEOUT_TICKS = 100

    private var camera: OrbitCamera? = null

    /** The `LocalPlayer` camera mode was entered with, to notice death and dimension change. */
    private var enteredPlayer: Player? = null

    /**
     * Ticks spent armed but not yet spectator, since the most recent [enter]. Only incremented
     * while waiting; once spectator lands it is never consulted again until the next [enter]
     * resets it, so the timeout can never interrupt an orbit that has already established
     * spectator.
     */
    private var ticksWaitingForSpectator = 0

    /**
     * Latched by an arm timeout: the server refused (or never answered) an entry, so stop asking.
     *
     * Without this a refusal retries forever *while the gesture is still going*. The timeout's
     * [exit] clears [camera], the button is still held and `DockInputRouter` still has a live
     * `dragKind`, so the very next mouse move calls [enter] again — one enter packet and one
     * "could not enter camera mode" chat error every five seconds for as long as the user keeps
     * dragging. The latch is deliberately *not* cleared by [exit]: ending a drag, or dropping the
     * camera for any ordinary reason, must leave camera mode re-enterable exactly as before. Only
     * [resetEntryRefusal] clears it, from the one place a dock session actually ends.
     */
    private var entryRefused = false

    /**
     * Window-pixel cursor position of the world gesture that may be about to arm camera mode, or
     * `null` before any has been seen.
     *
     * Only [enter] reads it, and only to choose the pivot. Kept as state rather than threaded
     * through [orbitBy]/[panBy]/[dollyBy] because entry is *lazy*: the gesture that arms the camera
     * is a mouse **move**, whose own coordinates are already a drag's worth away from the press the
     * user was actually aiming with. `DockInputRouter` calls [aimAt] at the moment it recognizes a
     * world gesture, which is the position the pivot should be picked from.
     *
     * These are raw GLFW window coordinates, the same ones `DockInputRouter` compares against
     * `ViewportState.realWidth/realHeight` to decide which dock region the cursor is over — so this
     * inherits that path's existing assumption that GLFW window coordinates and framebuffer pixels
     * are the same unit (true unless the OS reports a fractional HiDPI content scale).
     */
    private var aimX: Double? = null
    private var aimY: Double? = null

    /** True from the first world gesture until [exit]; the router consults this, not the gamemode. */
    val active: Boolean get() = camera != null

    /**
     * Record where the cursor was when a world gesture was recognized, for a later lazy [enter].
     *
     * Called by `DockInputRouter` on the press that takes ownership of a world drag and on a scroll
     * over the bare world. Deliberately does *not* arm anything by itself: a click on the world that
     * never becomes a drag must not round-trip the player's gamemode.
     */
    fun aimAt(cursorX: Double, cursorY: Double) {
        aimX = cursorX
        aimY = cursorY
    }

    fun orbitBy(dx: Double, dy: Double) = mutate { it.orbit(dx, dy) }
    fun panBy(dx: Double, dy: Double) = mutate { it.pan(dx, dy) }
    fun dollyBy(scrollDy: Double) = mutate { it.dolly(scrollDy) }

    private fun mutate(f: (OrbitCamera) -> OrbitCamera) {
        val current = camera ?: enter() ?: return
        camera = f(current)
    }

    /**
     * Raycast the cursor from [eye] and return the point to orbit around, or null if the world is
     * not available to cast through.
     *
     * Takes the eye and angle as parameters rather than reading them off [player] because the two
     * can disagree: while an entry is armed but the server has not yet granted spectator, the camera
     * has been accumulating gestures the player's entity has not been moved by, so the ray must come
     * from where the *camera* is, not where the entity still stands. That is also why this does not
     * read the render camera the way [WorldHover] does — for one round trip's worth of frames the
     * rendered view is still the player's.
     *
     * The ray is aimed with [aimX]/[aimY], the position of the *press*, not [WorldHover]'s live
     * cursor: see [aimAt] for why entry must not aim with a drag's worth of travel already applied.
     */
    private fun pickPivot(mc: Minecraft, player: Player, eye: Vec3, yaw: Float, pitch: Float): Vec3? {
        val ray = CursorPick.ray(mc, yaw, pitch, aimX, aimY)
        val hit = CursorPick.clip(mc, player, eye, ray) ?: return null
        // On a miss `clip` still returns a location — the far end of the ray — but that is
        // PIVOT_RAYCAST_RANGE away, which would orbit around a point in empty sky far behind
        // anything the player can see. A miss gets its own, much closer, fixed pivot. A hit gives
        // the centre of the block cell rather than the point on its face; see `pivotFor`.
        return pivotFor(hit, missPivot = eye.add(ray.scale(PIVOT_MISS_DISTANCE)))
    }

    /**
     * Re-aim the camera at whatever is under the cursor **now**, turning to face it.
     *
     * Called by `DockInputRouter` on every press that takes ownership of a world drag. Without it the
     * pivot was chosen exactly once per dock session — [enter] only runs while [camera] is null, and
     * the camera deliberately survives the end of a drag — so every drag after the first orbited a
     * stale point and pressing on a block did nothing at all.
     *
     * Scroll deliberately does **not** route here (it calls [aimAt] instead): a dolly should move the
     * camera, not snap the view somewhere new.
     */
    fun focusAt(cursorX: Double, cursorY: Double) {
        aimAt(cursorX, cursorY)
        val current = camera ?: return
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return
        if (player !== enteredPlayer) return
        val eye = current.eyePosition
        val pivot = pickPivot(mc, player, eye, current.yaw, current.pitch) ?: return
        camera = orbitAround(eye, pivot)
    }

    /**
     * Seed the camera from the player's *current* pose and ask the server for spectator.
     *
     * Seeding from the current pose is what makes entry visually a no-op: the view does not move
     * until the gesture that triggered entry is applied on the very next line. Returns null (and
     * enters nothing) when there is no player to orbit, when there is no connection to ask —
     * arming with nobody to answer would leave the client stuck waiting out the full timeout for
     * an entry that was never sent — or when [entryRefused] has latched a previous refusal.
     */
    private fun enter(): OrbitCamera? {
        if (entryRefused) return null
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        if (!ClientPlayNetworking.canSend(CameraModeC2S.TYPE)) return null

        val eye = player.eyePosition
        val pivot = pickPivot(mc, player, eye, player.yRot, player.xRot) ?: return null

        // Seeded from the eye→pivot direction, not from the player's own rotation: the pivot is under
        // the cursor, which is not the crosshair, so carrying the current yaw/pitch over would put the
        // camera somewhere other than where the player is standing. See `orbitAround`.
        val seeded = orbitAround(eye, pivot)
        camera = seeded
        enteredPlayer = player
        ticksWaitingForSpectator = 0
        ClientPlayNetworking.send(CameraModeC2S(enter = true))
        return seeded
    }

    /** Hand the gamemode back. The player **stays where the camera left them** — a deliberate choice. */
    fun exit() {
        if (camera == null) return
        camera = null
        enteredPlayer = null
        ticksWaitingForSpectator = 0
        // canSend guards the disconnect path: the connection may already be gone, in which case
        // there is nobody left to tell. The gamemode is not lost with it — the server's own
        // ServerPlayConnectionEvents.DISCONNECT handler (CameraModeHandlers.handleDisconnect)
        // restores any player still holding a camera-mode grant, which is the only thing that can
        // help a client that crashed rather than exited.
        if (ClientPlayNetworking.canSend(CameraModeC2S.TYPE)) {
            ClientPlayNetworking.send(CameraModeC2S(enter = false))
        }
    }

    /**
     * Per-tick bookkeeping: notice a lost player, wait out (or give up on) the spectator round trip,
     * and keep spectator flight's residual momentum from fighting the camera.
     *
     * The pose itself is **not** written here — that is [applyFrame]'s job, and the whole reason the
     * two are split. What is left is the state that genuinely belongs to the tick:
     *
     * 1. **We wait for spectator to actually land before moving anything.** The gamemode change is
     *    a round trip; a non-spectator making this large a position jump is exactly the case
     *    `handleMovePlayer` rubber-bands. The gesture is not dropped in the meantime — the camera
     *    state has been accumulating all along — so the drag resumes seamlessly the moment the
     *    server answers.
     * 2. **A refused entry must not leave the client half-armed forever.** While waiting for
     *    spectator we count ticks in [ticksWaitingForSpectator]; past [ARM_TIMEOUT_TICKS] we give
     *    up, latch [entryRefused] and [exit] rather than sit dead until the process restarts. The
     *    latch is what keeps the still-held drag from re-arming on its very next mouse move and
     *    asking again. See [ARM_TIMEOUT_TICKS] for why a timeout, not a second payload, is the
     *    mechanism. The counter is only touched in
     *    the not-yet-spectator branch, so it can never fire once an orbit has established
     *    spectator — the check below short-circuits and returns before the counter is even read.
     * 3. **Delta movement is zeroed** because spectator flight keeps coasting on residual momentum,
     *    which would fight the camera every tick and drift the eye off the orbit.
     */
    private fun applyTick(mc: Minecraft) {
        if (camera == null) return
        val player = mc.player
        // Death and dimension change both REPLACE the LocalPlayer rather than moving it. Comparing
        // identity catches both without needing a hook for either: driving the camera on into the
        // new player would snap them to a pivot in a world they have left, and — worse — leave them
        // spectating with the gesture that would have exited already gone.
        if (player == null || player !== enteredPlayer) {
            exit()
            return
        }
        if (!player.isSpectator) {
            ticksWaitingForSpectator++
            if (ticksWaitingForSpectator > ARM_TIMEOUT_TICKS) {
                entryRefused = true
                exit()
            }
            return
        }
        player.setDeltaMovement(Vec3.ZERO)
    }

    /**
     * Write the camera's pose onto the player, **once per rendered frame**, snapping the
     * previous-pose fields with it.
     *
     * This is the fix for a visibly jittery orbit, and both halves of it matter:
     *
     * 1. **Per frame, not per tick.** Mouse deltas arrive and accumulate into [camera] every frame,
     *    but client ticks only happen 20 times a second. Committing the pose on
     *    `END_CLIENT_TICK` therefore froze the view for every frame that did not happen to contain
     *    a tick — six frames out of seven at 144 fps — and then advanced it by however much mouse
     *    motion that tick had collected, which varies from tick to tick. Vanilla never does this to
     *    the player's own view: `Minecraft.runTick` calls `MouseHandler.handleAccumulatedMovement()`
     *    every frame, and `MinecraftFrameMixin` hooks that exact point so the orbit camera is applied
     *    on the same schedule.
     * 2. **`absSnapTo`, deliberately NOT `setPos`/`setYRot`.** `absSnapTo` also writes the
     *    previous-pose fields (`xo/yo/zo`, `yRotO`, `xRotO`) — and with a per-frame commit that is
     *    exactly right, because the renderer's tick interpolation (`Camera.setup` lerps `xOld`→`x`
     *    and `Entity.getViewYRot` rot-lerps `yRotO`→`yRot`) has nothing left to interpolate *to*:
     *    the pose is already current for this frame. Leaving the old fields behind instead makes
     *    the renderer smear each frame's fresh pose back toward a stale one, which is the same
     *    lag by another route. This mirrors what vanilla mouse-look does in `Entity.turn`, which
     *    advances `xRotO`/`yRotO` by the same delta as `xRot`/`yRot` for precisely this reason.
     *
     * Deliberately does *not* call [exit] or send anything when the player is gone or not yet
     * spectating: entering, refusing and leaving are round-tripped state that belongs to the tick,
     * and firing them from a render frame would send them at frame rate. [applyTick] owns that;
     * this only ever draws.
     */
    fun applyFrame(mc: Minecraft) {
        val c = camera ?: return
        val player = mc.player ?: return
        if (player !== enteredPlayer || !player.isSpectator) return
        val eye = c.eyePosition
        player.absSnapTo(eye.x, eye.y - player.eyeHeight, eye.z, c.yRot, c.xRot)
    }

    /**
     * Clear a latched entry refusal so the next dock session may arm camera mode again.
     *
     * Called from `DockInputRouter.clearFocus()`, the single choke point every way of dropping dock
     * focus already passes through — and therefore the one boundary at which "the user is still
     * mid-gesture, being told no over and over" has definitely ended. Kept separate from [exit] on
     * purpose: [exit] runs whenever the camera is dropped, including the ordinary end of a drag,
     * and clearing the latch there would restore the retry loop it exists to break. An explicit
     * function rather than a settable field so the router never reaches into this object's state.
     */
    fun resetEntryRefusal() {
        entryRefused = false
    }

    /**
     * Registered from `GarnetClient.onInitializeClient()`.
     *
     * The DISCONNECT hook is not tidiness. A client that drops mid-orbit and never sends the
     * leaving payload leaves that player in spectator on the server until the server's own
     * disconnect handler undoes it (`CameraModeHandlers.handleDisconnect`); clearing local state
     * here means the next session starts clean rather than half-armed, and clearing the refusal
     * latch means a new connection is not judged by the last one's answer. Death and dimension
     * change need no hook of their own — [applyTick]'s player-identity check covers both.
     */
    fun registerOrbitCamera() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> applyTick(mc) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            exit()
            resetEntryRefusal()
        }
    }
}

/** File-scope alias so `GarnetClient` reads like its neighbouring `register*` calls. */
fun registerOrbitCamera() = OrbitCameraController.registerOrbitCamera()
