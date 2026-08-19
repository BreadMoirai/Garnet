package com.breadmoirai.garnet.camera.input

import com.breadmoirai.garnet.camera.data.OrbitCamera
import com.breadmoirai.garnet.camera.data.MIN_DISTANCE
import com.breadmoirai.garnet.camera.data.PIVOT_MISS_DISTANCE
import com.breadmoirai.garnet.camera.data.PIVOT_RAYCAST_RANGE
import com.breadmoirai.garnet.camera.data.dolly
import com.breadmoirai.garnet.camera.data.eyePosition
import com.breadmoirai.garnet.camera.data.orbit
import com.breadmoirai.garnet.camera.data.pan
import com.breadmoirai.garnet.camera.data.xRot
import com.breadmoirai.garnet.camera.data.yRot
import com.breadmoirai.garnet.camera.network.CameraModeC2S
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.HitResult
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
 * written again from [applyTick] on `ClientTickEvents.END_CLIENT_TICK`, with no synchronization and
 * no `@Volatile` — because both are the client's main (render) thread. `MouseHandler`/`KeyboardHandler`
 * wrap their GLFW callbacks in `minecraft.execute(...)`, so the mixin entry points that reach the
 * gesture path already run on the same thread as the tick event. See `DockInputRouter`'s own
 * threading note, which this deliberately matches.
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

    /** True from the first world gesture until [exit]; the router consults this, not the gamemode. */
    val active: Boolean get() = camera != null

    fun orbitBy(dx: Double, dy: Double) = mutate { it.orbit(dx, dy) }
    fun panBy(dx: Double, dy: Double) = mutate { it.pan(dx, dy) }
    fun dollyBy(scrollDy: Double) = mutate { it.dolly(scrollDy) }

    private fun mutate(f: (OrbitCamera) -> OrbitCamera) {
        val current = camera ?: enter() ?: return
        camera = f(current)
    }

    /**
     * Seed the camera from the player's *current* pose and ask the server for spectator.
     *
     * Seeding from the current pose is what makes entry visually a no-op: the view does not move
     * until the gesture that triggered entry is applied on the very next line. Returns null (and
     * enters nothing) when there is no player to orbit, or when there is no connection to ask —
     * arming with nobody to answer would leave the client stuck waiting out the full timeout for
     * an entry that was never sent.
     */
    private fun enter(): OrbitCamera? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        if (mc.level == null) return null
        if (!ClientPlayNetworking.canSend(CameraModeC2S.TYPE)) return null

        val hit = player.pick(PIVOT_RAYCAST_RANGE, 1.0f, false)
        // On a miss `pick` still returns a location — the far end of the ray — but that is
        // PIVOT_RAYCAST_RANGE away, which would orbit around a point in empty sky far behind
        // anything the player can see. A miss gets its own, much closer, fixed pivot.
        val pivot = if (hit.type == HitResult.Type.MISS) {
            player.eyePosition.add(player.getViewVector(1.0f).scale(PIVOT_MISS_DISTANCE))
        } else {
            hit.location
        }

        val eye = player.eyePosition
        val seeded = OrbitCamera(
            pivot = pivot,
            yaw = player.yRot,
            pitch = player.xRot,
            distance = eye.distanceTo(pivot).coerceAtLeast(MIN_DISTANCE),
        )
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
        // canSend guards the disconnect path: the connection may already be gone, and the server
        // restores the gamemode on its own when the player reconnects.
        if (ClientPlayNetworking.canSend(CameraModeC2S.TYPE)) {
            ClientPlayNetworking.send(CameraModeC2S(enter = false))
        }
    }

    /**
     * Apply the camera to the player, once per client tick.
     *
     * Three things are load-bearing here:
     *
     * 1. **We wait for spectator to actually land before moving anything.** The gamemode change is
     *    a round trip; a non-spectator making this large a position jump is exactly the case
     *    `handleMovePlayer` rubber-bands. The gesture is not dropped in the meantime — the camera
     *    state has been accumulating all along — so the drag resumes seamlessly the moment the
     *    server answers.
     * 2. **A refused entry must not leave the client half-armed forever.** While waiting for
     *    spectator we count ticks in [ticksWaitingForSpectator]; past [ARM_TIMEOUT_TICKS] we give
     *    up and [exit] rather than sit dead until the process restarts. See [ARM_TIMEOUT_TICKS]
     *    for why a timeout, not a second payload, is the mechanism. The counter is only touched in
     *    the not-yet-spectator branch, so it can never fire once an orbit has established
     *    spectator — the check below short-circuits and returns before the counter is even read.
     * 3. **`setPos`/`setYRot`, deliberately NOT `absSnapTo`.** `absSnapTo` also writes the
     *    previous-tick fields (`xo/yo/zo`, `yRotO`, `xRotO`), which kills the render interpolator.
     *    That is right for a teleport and wrong for a drag: interpolating from last tick's pose is
     *    exactly what makes a 20 Hz camera update look smooth at 144 fps instead of stepping.
     *    Entry seeds from the player's current pose, so there is never a jump to smear.
     *
     * Delta movement is zeroed because spectator flight keeps coasting on residual momentum, which
     * would fight the camera every tick and drift the eye off the orbit.
     */
    private fun applyTick(mc: Minecraft) {
        val c = camera ?: return
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
            if (ticksWaitingForSpectator > ARM_TIMEOUT_TICKS) exit()
            return
        }
        val eye = c.eyePosition
        player.setPos(eye.x, eye.y - player.eyeHeight, eye.z)
        player.setDeltaMovement(Vec3.ZERO)
        player.yRot = c.yRot
        player.xRot = c.xRot
    }

    /**
     * Registered from `GarnetClient.onInitializeClient()`.
     *
     * The DISCONNECT hook is not tidiness. A client that drops mid-orbit and never sends the
     * leaving payload leaves that player permanently in spectator on the server; clearing local
     * state here means the next session starts clean rather than half-armed. Death and dimension
     * change need no hook of their own — [applyTick]'s player-identity check covers both.
     */
    fun registerOrbitCamera() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> applyTick(mc) }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> exit() }
    }
}

/** File-scope alias so `GarnetClient` reads like its neighbouring `register*` calls. */
fun registerOrbitCamera() = OrbitCameraController.registerOrbitCamera()
