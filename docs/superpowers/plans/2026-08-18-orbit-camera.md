# Orbit Camera Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** While `G` has the cursor freed, let the player orbit, pan, and dolly the world viewport by moving their own player entity in spectator mode.

**Architecture:** The client owns the camera outright; the server owns only the gamemode. A pure `OrbitCamera` value type holds pivot/yaw/pitch/distance and does all the arithmetic. A thin client-side controller applies it to `LocalPlayer` once per client tick, and vanilla's existing `ServerboundMovePlayerPacket` does the syncing for free — the server exempts spectators from `handleMovePlayer`'s distance and collision checks. One C2S payload flips spectator on and back.

**Tech Stack:** Kotlin, Fabric API (`ClientTickEvents`, `ClientPlayNetworking`, `ServerPlayNetworking`, `PayloadTypeRegistry`), Minecraft 26.2, Kotest (`FunSpec`) for unit tests, `FabricClientGameTest` for the clientTest spec.

**Spec:** `docs/superpowers/specs/2026-08-18-orbit-camera-design.md`

## Global Constraints

- **Single Minecraft version: 26.2.** There is one Stonecutter slice (`versions/26.2`). No version conditions, no swaps.
- **Compile verification is never `compileKotlin` alone.** The full command, from WSL, is:
  `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- **`--tests` filtering does not work on Kotest specs.** Run `:26.2:test` unfiltered and read the per-class XML at `versions/26.2/build/test-results/test/TEST-<FQCN>.xml`. A "No tests found for given include pattern" error from `--tests` is the known interop gap documented in `docs/tooling/local-verification-commands.md`, not a missing test.
- **Payload ids are frozen wire protocol.** New ids go through `payloadId(p)` from `editor/network/PayloadIds.kt`, which mints `garnet:project_<p>`. Do not change the helper.
- **Server is the only writer.** A refused request replies `EditorErrorS2C(reason)` via `EditorHandlerSupport.fail`; it never fails silently.
- **Git commits must not carry a `Co-Authored-By` or "Generated with Claude Code" trailer.**
- **Fix what you find.** Anything broken noticed while working gets fixed in the same task, or is named explicitly in the report.
- Package root is `com.breadmoirai.garnet`. New code lands under a new top-level `camera/` feature package, split `camera/data` (pure), `camera/input` (client), `camera/network` (payload + handler).

---

### Task 1: `OrbitCamera` — the pure camera state

All of the arithmetic, with no Minecraft client dependency, so it is unit-testable in `src/test` with no live client. This is the same split `DockHitTest` and `DockFocusTarget` already use. `net.minecraft.world.phys.Vec3` is a plain math class and is safe to use here — `src/test` already sees both `main` and `client` classes.

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/camera/data/OrbitCamera.kt`
- Test: `src/test/kotlin/com/breadmoirai/garnet/camera/data/OrbitCameraTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `data class OrbitCamera(val pivot: Vec3, val yaw: Float, val pitch: Float, val distance: Double)`
  - `fun OrbitCamera.orbit(dx: Double, dy: Double): OrbitCamera`
  - `fun OrbitCamera.pan(dx: Double, dy: Double): OrbitCamera`
  - `fun OrbitCamera.dolly(scrollDy: Double): OrbitCamera`
  - `val OrbitCamera.eyePosition: Vec3`
  - `val OrbitCamera.yRot: Float` (== `yaw`)
  - `val OrbitCamera.xRot: Float` (== `pitch`)
  - `fun viewVector(yaw: Float, pitch: Float): Vec3`
  - Constants `ORBIT_SENSITIVITY`, `PAN_SENSITIVITY`, `DOLLY_FACTOR`, `MIN_DISTANCE`, `MAX_DISTANCE`, `MAX_PITCH`, `PIVOT_RAYCAST_RANGE`, `PIVOT_MISS_DISTANCE`

- [ ] **Step 1: Write the failing test**

Create `src/test/kotlin/com/breadmoirai/garnet/camera/data/OrbitCameraTest.kt`:

```kotlin
package com.breadmoirai.garnet.camera.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3

/**
 * The orbit camera's arithmetic. Pure by construction — no `Minecraft`, no GLFW — so the behaviour
 * a player actually feels (which way a drag turns the view, whether zoom stays proportional, that
 * the camera cannot flip over the pole) is pinned here rather than only inside a client gametest
 * that has to boot a real client to observe it. The gesture routing that feeds these functions is
 * covered by `OrbitCameraSpec` in `src/clientTest`.
 */
class OrbitCameraTest : FunSpec({

    val origin = OrbitCamera(pivot = Vec3.ZERO, yaw = 0f, pitch = 0f, distance = 5.0)

    test("yaw 0 / pitch 0 looks along +Z, so the eye sits at -Z") {
        // MC's convention: getViewVector at yRot 0 is +Z. The eye is the pivot pushed back along
        // the *opposite* of the look direction, so a player standing there looks at the pivot.
        val eye = origin.eyePosition

        eye.x shouldBe (0.0 plusOrMinus 1e-9)
        eye.y shouldBe (0.0 plusOrMinus 1e-9)
        eye.z shouldBe (-5.0 plusOrMinus 1e-9)
    }

    test("the rotation is the yaw/pitch themselves — the camera looks at the pivot by construction") {
        val c = origin.copy(yaw = 37f, pitch = -12f)

        c.yRot shouldBe 37f
        c.xRot shouldBe -12f
    }

    test("yaw 90 puts the eye on +X") {
        // viewVector.x = -sin(yaw), so yaw 90 looks toward -X and the eye is pushed to +X.
        val eye = origin.copy(yaw = 90f).eyePosition

        eye.x shouldBe (5.0 plusOrMinus 1e-9)
        eye.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pitch 90 (straight down) puts the eye directly above the pivot") {
        // Clamped below 90 so it never fully degenerates, hence the loose tolerance on x/z.
        val eye = origin.copy(pitch = 90f).eyePosition

        eye.y shouldBe (5.0 plusOrMinus 0.01)
    }

    test("orbit turns yaw and pitch and leaves the pivot and distance alone") {
        val turned = origin.orbit(dx = 10.0, dy = 4.0)

        turned.yaw shouldBe (10f * ORBIT_SENSITIVITY plusOrMinus 1e-4f)
        turned.pitch shouldBe (4f * ORBIT_SENSITIVITY plusOrMinus 1e-4f)
        turned.pivot shouldBe Vec3.ZERO
        turned.distance shouldBe (5.0 plusOrMinus 1e-9)
    }

    test("yaw wraps at the 360 boundary instead of growing without bound") {
        val spun = origin.copy(yaw = 359f).orbit(dx = 100.0, dy = 0.0)

        // Left unwrapped, yaw accumulates forever across a long session and eventually loses float
        // precision in the trig below.
        spun.yaw shouldBe (spun.yaw % 360f)
        (spun.yaw in -360f..360f) shouldBe true
    }

    test("pitch clamps short of the pole in both directions") {
        // Never *to* 90: at exactly vertical the look-at basis degenerates and the view rolls
        // unpredictably as it crosses over.
        origin.orbit(dx = 0.0, dy = 10_000.0).pitch shouldBe MAX_PITCH
        origin.orbit(dx = 0.0, dy = -10_000.0).pitch shouldBe -MAX_PITCH
        (MAX_PITCH < 90f) shouldBe true
    }

    test("pan moves the pivot along the camera's own right/up basis, not the world axes") {
        // At yaw 0 the camera's right is +X and its up is +Y, so this is the case where the two
        // bases coincide and the signs are readable.
        val panned = origin.pan(dx = 10.0, dy = 0.0)

        panned.pivot.x shouldBe (-10.0 * PAN_SENSITIVITY * 5.0 plusOrMinus 1e-9)
        panned.pivot.y shouldBe (0.0 plusOrMinus 1e-9)
        panned.pivot.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pan at yaw 90 moves the pivot along Z — the basis rotated with the camera") {
        val panned = origin.copy(yaw = 90f).pan(dx = 10.0, dy = 0.0)

        panned.pivot.x shouldBe (0.0 plusOrMinus 1e-6)
        kotlin.math.abs(panned.pivot.z) shouldBe (10.0 * PAN_SENSITIVITY * 5.0 plusOrMinus 1e-6)
    }

    test("pan scales with distance so it covers the same screen fraction at every zoom") {
        val near = origin.copy(distance = 2.0).pan(dx = 10.0, dy = 0.0)
        val far = origin.copy(distance = 20.0).pan(dx = 10.0, dy = 0.0)

        // Unscaled panning is unusable at range: the pivot crawls when zoomed out.
        kotlin.math.abs(far.pivot.x) shouldBe (kotlin.math.abs(near.pivot.x) * 10.0 plusOrMinus 1e-9)
    }

    test("dolly is multiplicative, so zoom feels the same at every scale") {
        val out = origin.dolly(-1.0)
        val outTwice = origin.dolly(-1.0).dolly(-1.0)

        out.distance shouldBe (5.0 * DOLLY_FACTOR plusOrMinus 1e-9)
        outTwice.distance shouldBe (5.0 * DOLLY_FACTOR * DOLLY_FACTOR plusOrMinus 1e-9)
    }

    test("scrolling up moves the eye toward the pivot") {
        origin.dolly(1.0).distance shouldBe (5.0 / DOLLY_FACTOR plusOrMinus 1e-9)
    }

    test("dolly clamps at the floor rather than reaching or inverting through the pivot") {
        var c = origin
        repeat(200) { c = c.dolly(1.0) }

        c.distance shouldBe MIN_DISTANCE
        (MIN_DISTANCE > 0.0) shouldBe true
    }

    test("dolly clamps at the ceiling so the camera cannot be scrolled out past the render distance") {
        var c = origin
        repeat(200) { c = c.dolly(-1.0) }

        c.distance shouldBe MAX_DISTANCE
    }

    test("pan leaves the eye's offset from the pivot untouched") {
        // Panning moves what you are looking at, never how far away or from what angle.
        val panned = origin.pan(dx = 7.0, dy = -3.0)
        val before = origin.eyePosition.subtract(origin.pivot)
        val after = panned.eyePosition.subtract(panned.pivot)

        after.x shouldBe (before.x plusOrMinus 1e-9)
        after.y shouldBe (before.y plusOrMinus 1e-9)
        after.z shouldBe (before.z plusOrMinus 1e-9)
    }
})
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:testClasses"`

Expected: FAIL — compilation error, `Unresolved reference: OrbitCamera` (and the free functions/constants). Do not run the `test` task yet; the source set does not compile.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/breadmoirai/garnet/camera/data/OrbitCamera.kt`:

```kotlin
package com.breadmoirai.garnet.camera.data

import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sin

/** Degrees of rotation per pixel of mouse movement. */
const val ORBIT_SENSITIVITY: Float = 0.35f

/** Pivot translation per pixel of mouse movement, as a fraction of the current distance. */
const val PAN_SENSITIVITY: Double = 0.002

/** Distance multiplier per scroll notch. Multiplicative, so zoom feels the same at every scale. */
const val DOLLY_FACTOR: Double = 1.15

/** The camera may never reach the pivot; at zero the look direction is undefined. */
const val MIN_DISTANCE: Double = 0.5

/** Past this the pivot is outside any plausible render distance and the view is useless. */
const val MAX_DISTANCE: Double = 256.0

/**
 * Just shy of vertical. Never *at* 90: there the yaw/pitch basis degenerates — every yaw produces
 * the same look direction — and the view visibly rolls as the camera crosses the pole.
 */
const val MAX_PITCH: Float = 89.9f

/** How far to look for something to orbit around when camera mode is entered. */
const val PIVOT_RAYCAST_RANGE: Double = 64.0

/** Where the pivot goes when that raycast hits nothing, so there is always something to orbit. */
const val PIVOT_MISS_DISTANCE: Double = 8.0

/**
 * A camera orbiting [pivot] at [distance], looking back at it along [yaw]/[pitch].
 *
 * Immutable: every gesture returns a new instance, so there is no partially-applied state to
 * reason about and the whole type is trivially testable. [yaw]/[pitch] are in Minecraft's own
 * convention and degrees — yaw 0 looks along +Z, positive pitch looks *down* — which is why
 * [yRot]/[xRot] are the fields themselves rather than a conversion: the camera looks at the pivot
 * by construction, so the rotation that achieves that *is* the orbit angle.
 */
data class OrbitCamera(
    val pivot: Vec3,
    val yaw: Float,
    val pitch: Float,
    val distance: Double,
)

/** MC's own view-direction convention, in degrees: yaw 0 is +Z, positive pitch is downward. */
fun viewVector(yaw: Float, pitch: Float): Vec3 {
    val y = Math.toRadians(yaw.toDouble())
    val p = Math.toRadians(pitch.toDouble())
    val cosPitch = cos(p)
    return Vec3(-sin(y) * cosPitch, -sin(p), cos(y) * cosPitch)
}

/** Where the player must stand to be looking at [OrbitCamera.pivot] from this angle and distance. */
val OrbitCamera.eyePosition: Vec3
    get() = pivot.subtract(viewVector(yaw, pitch).scale(distance))

val OrbitCamera.yRot: Float get() = yaw
val OrbitCamera.xRot: Float get() = pitch

/**
 * Turn the camera around the pivot. Yaw is wrapped rather than accumulated: over a long session an
 * unwrapped yaw grows without bound and eventually loses the float precision the trig above needs.
 */
fun OrbitCamera.orbit(dx: Double, dy: Double): OrbitCamera = copy(
    yaw = (yaw + dx.toFloat() * ORBIT_SENSITIVITY) % 360f,
    pitch = (pitch + dy.toFloat() * ORBIT_SENSITIVITY).coerceIn(-MAX_PITCH, MAX_PITCH),
)

/**
 * Slide the *pivot* across the camera's own right/up plane, leaving the viewing angle and distance
 * untouched. Scaled by [distance] so a given mouse movement covers the same fraction of the screen
 * however far out you are zoomed — unscaled, panning crawls to uselessness at range.
 */
fun OrbitCamera.pan(dx: Double, dy: Double): OrbitCamera {
    val forward = viewVector(yaw, pitch)
    // Right is derived from yaw alone, so panning stays horizontal-plane-stable when looking
    // steeply up or down instead of shearing with the pitch.
    val y = Math.toRadians(yaw.toDouble())
    val right = Vec3(cos(y), 0.0, sin(y))
    val up = right.cross(forward)
    val scale = distance * PAN_SENSITIVITY
    return copy(pivot = pivot.add(right.scale(-dx * scale)).add(up.scale(-dy * scale)))
}

/** Zoom. Multiplicative for constant feel; clamped so the camera can neither reach nor invert. */
fun OrbitCamera.dolly(scrollDy: Double): OrbitCamera {
    if (scrollDy == 0.0) return this
    val factor = if (scrollDy > 0) 1.0 / DOLLY_FACTOR else DOLLY_FACTOR
    val steps = max(1, abs(scrollDy).toInt())
    var d = distance
    repeat(steps) { d *= factor }
    return copy(distance = min(MAX_DISTANCE, max(MIN_DISTANCE, d)))
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`

Then read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.camera.data.OrbitCameraTest.xml` and confirm `failures="0" errors="0"` with 14 test cases.

Expected: PASS. Two tests are sign-sensitive and may legitimately fail on first run — `pan moves the pivot along the camera's own right/up basis` and `pan at yaw 90`. If a sign is inverted, fix the sign in `pan` (not the test) so that dragging right moves the *scene* right, which means the pivot moves left.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/camera/data/OrbitCamera.kt \
        src/test/kotlin/com/breadmoirai/garnet/camera/data/OrbitCameraTest.kt
git commit -m "feat: add the pure OrbitCamera state and its arithmetic

Immutable pivot/yaw/pitch/distance with orbit, pan and dolly. Pan scales with
distance and dolly is multiplicative so both feel identical at every zoom level;
pitch clamps short of the pole, where the yaw/pitch basis degenerates.

No Minecraft client dependency, so the behaviour a player actually feels is
pinned by a plain JUnit run rather than only by a client gametest."
```

---

### Task 2: `CameraModeC2S` — the gamemode payload and its server handler

The only server-side state this feature owns. Vanilla already tracks a per-player *previous* gamemode (`ServerPlayerGameMode.getPreviousGameModeForPlayer`) — the same field `/gamemode spectator` and back uses — so this task stores none of its own.

**Files:**
- Create: `src/main/kotlin/com/breadmoirai/garnet/camera/network/CameraPackets.kt`
- Create: `src/main/kotlin/com/breadmoirai/garnet/camera/network/CameraModeHandlers.kt`
- Modify: `src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt`
- Test: `src/gametest/kotlin/com/breadmoirai/garnet/camera/CameraModeGameTest.kt`

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces:
  - `data class CameraModeC2S(val enter: Boolean) : CustomPacketPayload` with `TYPE` and `STREAM_CODEC` companions
  - `object CameraModeHandlers { fun handleCameraMode(server: MinecraftServer, player: ServerPlayer, payload: CameraModeC2S) }`

- [ ] **Step 1: Write the failing test**

Look first at an existing file in `src/gametest/kotlin/com/breadmoirai/garnet/` to copy the harness's registration convention exactly (test-class discovery in this repo is explicit, not autoscanned). Then create `src/gametest/kotlin/com/breadmoirai/garnet/camera/CameraModeGameTest.kt`:

```kotlin
package com.breadmoirai.garnet.camera

import com.breadmoirai.garnet.camera.network.CameraModeC2S
import com.breadmoirai.garnet.camera.network.CameraModeHandlers
import net.minecraft.world.level.GameType

/**
 * The gamemode half of camera mode. Needs a real `ServerPlayer` — `setGameMode` touches abilities,
 * the player list and the client sync packet — so it is a gametest rather than a unit test, per
 * `docs/gametest/unit-vs-gametest-split.md`.
 *
 * What is worth pinning here is *restore*: the handler deliberately keeps no previous-gamemode
 * state of its own and leans on the field vanilla already maintains. A future refactor that starts
 * storing one would pass a naive "enter then leave" check while silently breaking the case where
 * something else changed the player's gamemode in between.
 */
// Register this class the same way the sibling gametest classes in src/gametest are registered.
// Two cases, each as its own @GameTest method:
//
//   1. "entering camera mode puts the player in spectator"
//        val player = <make a ServerPlayer via the harness>
//        player.setGameMode(GameType.CREATIVE)
//        CameraModeHandlers.handleCameraMode(server, player, CameraModeC2S(enter = true))
//        assert player.gameMode() == GameType.SPECTATOR
//
//   2. "leaving restores the gamemode the player had before, not a hardcoded default"
//        val player = <make a ServerPlayer via the harness>
//        player.setGameMode(GameType.ADVENTURE)      // deliberately NOT survival/creative
//        CameraModeHandlers.handleCameraMode(server, player, CameraModeC2S(enter = true))
//        CameraModeHandlers.handleCameraMode(server, player, CameraModeC2S(enter = false))
//        assert player.gameMode() == GameType.ADVENTURE
```

Fill the two method bodies using the surrounding files' harness idiom — do not invent a new one.

- [ ] **Step 2: Run the test to verify it fails**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:gametestClasses"`

Expected: FAIL — `Unresolved reference: CameraModeC2S` / `CameraModeHandlers`.

- [ ] **Step 3: Write the implementation**

Create `src/main/kotlin/com/breadmoirai/garnet/camera/network/CameraPackets.kt`:

```kotlin
package com.breadmoirai.garnet.camera.network

import com.breadmoirai.garnet.editor.network.payloadId
import io.netty.buffer.ByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Enter or leave camera mode. The *only* thing the orbit camera asks the server for: the client
 * owns the camera outright and moves its own player, which the server accepts because
 * `ServerGamePacketListenerImpl.handleMovePlayer` exempts spectators from both its distance check
 * and its collision check. Sending orbit deltas instead would put a round trip inside every drag
 * frame.
 */
data class CameraModeC2S(val enter: Boolean) : CustomPacketPayload {
    companion object {
        val TYPE = CustomPacketPayload.Type<CameraModeC2S>(payloadId("camera_mode"))
        val STREAM_CODEC: StreamCodec<ByteBuf, CameraModeC2S> = StreamCodec.composite(
            ByteBufCodecs.BOOL, CameraModeC2S::enter,
            ::CameraModeC2S,
        )
    }
    override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
}
```

Create `src/main/kotlin/com/breadmoirai/garnet/camera/network/CameraModeHandlers.kt`:

```kotlin
package com.breadmoirai.garnet.camera.network

import com.breadmoirai.garnet.editor.network.EditorHandlerSupport
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType

object CameraModeHandlers {

    /**
     * Flip the requesting player into spectator, or back out of it.
     *
     * **Restore deliberately reads vanilla's own previous-gamemode field** rather than remembering
     * one here. That field is what `/gamemode spectator` and back uses, it survives a relog, and
     * leaning on it means there is no per-player map of ours to leak, to get out of sync with a
     * gamemode changed by an operator mid-orbit, or to strand a player in spectator if the client
     * never sends the matching leave.
     */
    fun handleCameraMode(server: MinecraftServer, player: ServerPlayer, payload: CameraModeC2S) {
        if (payload.enter) {
            if (player.gameMode() == GameType.SPECTATOR) return
            if (!player.setGameMode(GameType.SPECTATOR)) {
                EditorHandlerSupport.fail(player, "could not enter camera mode")
            }
            return
        }
        if (player.gameMode() != GameType.SPECTATOR) return
        val previous = player.gameMode.previousGameModeForPlayer ?: server.defaultGameType
        if (!player.setGameMode(previous)) {
            EditorHandlerSupport.fail(player, "could not leave camera mode")
        }
    }
}
```

`ServerPlayer.gameMode` is a `public final ServerPlayerGameMode` field and `getPreviousGameModeForPlayer()` is public, so `player.gameMode.previousGameModeForPlayer` resolves directly from Kotlin — this needs no accessor mixin. The getter is `@Nullable` (a player who has never changed gamemode has no previous one), which is what the `?: server.defaultGameType` fallback is for.

Then register both in `EditorNetworkRegistry.register()` — add the payload registration next to the other `serverboundPlay().register(...)` lines:

```kotlin
PayloadTypeRegistry.serverboundPlay().register(CameraModeC2S.TYPE, CameraModeC2S.STREAM_CODEC)
```

and the receiver next to the other `registerGlobalReceiver` blocks:

```kotlin
ServerPlayNetworking.registerGlobalReceiver(CameraModeC2S.TYPE) { payload, ctx ->
    ctx.server().execute { CameraModeHandlers.handleCameraMode(ctx.server(), ctx.player(), payload) }
}
```

with the matching imports for `CameraModeC2S` and `CameraModeHandlers`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`

Expected: PASS, both cases.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/breadmoirai/garnet/camera/network/ \
        src/main/kotlin/com/breadmoirai/garnet/editor/network/EditorNetworkRegistry.kt \
        src/gametest/kotlin/com/breadmoirai/garnet/camera/CameraModeGameTest.kt
git commit -m "feat: add the CameraModeC2S gamemode payload

The one thing camera mode asks the server for. Restore reads vanilla's own
previous-gamemode field rather than remembering one: that field already survives
a relog and already tracks an operator changing the mode mid-orbit, so there is
no per-player map of ours to leak or desync."
```

---

### Task 3: `OrbitCameraController` — applying the camera to `LocalPlayer`

The Minecraft-facing half, deliberately thin: anything with arithmetic in it belongs in Task 1.

**Files:**
- Create: `src/client/kotlin/com/breadmoirai/garnet/camera/input/OrbitCameraController.kt`
- Modify: `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt`

**Interfaces:**
- Consumes: `OrbitCamera`, `orbit`, `pan`, `dolly`, `eyePosition`, `yRot`, `xRot`, `PIVOT_RAYCAST_RANGE`, `PIVOT_MISS_DISTANCE`, `MIN_DISTANCE`, `MAX_DISTANCE` (Task 1); `CameraModeC2S` (Task 2).
- Produces:
  - `object OrbitCameraController` with:
    - `val active: Boolean`
    - `fun orbitBy(dx: Double, dy: Double)`
    - `fun panBy(dx: Double, dy: Double)`
    - `fun dollyBy(scrollDy: Double)`
    - `fun exit()`
  - `fun registerOrbitCamera()`

- [ ] **Step 1: Write the implementation**

There is no unit test in this task: every line of it needs a live `Minecraft`, `LocalPlayer` and network connection. Its behaviour is covered by the clientTest spec in Task 5, and all of its arithmetic already has a unit test in Task 1. That is the deliberate split — do not add a mock-heavy test here to fill the gap.

Create `src/client/kotlin/com/breadmoirai/garnet/camera/input/OrbitCameraController.kt`:

```kotlin
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
 */
object OrbitCameraController {

    private var camera: OrbitCamera? = null

    /** The `LocalPlayer` camera mode was entered with, to notice death and dimension change. */
    private var enteredPlayer: Player? = null

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
     * enters nothing) when there is no player to orbit.
     */
    private fun enter(): OrbitCamera? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        if (mc.level == null) return null

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
        ClientPlayNetworking.send(CameraModeC2S(enter = true))
        return seeded
    }

    /** Hand the gamemode back. The player **stays where the camera left them** — a deliberate choice. */
    fun exit() {
        if (camera == null) return
        camera = null
        enteredPlayer = null
        // canSend guards the disconnect path: the connection may already be gone, and the server
        // restores the gamemode on its own when the player reconnects.
        if (ClientPlayNetworking.canSend(CameraModeC2S.TYPE)) {
            ClientPlayNetworking.send(CameraModeC2S(enter = false))
        }
    }

    /**
     * Apply the camera to the player, once per client tick.
     *
     * Two things are load-bearing here:
     *
     * 1. **We wait for spectator to actually land before moving anything.** The gamemode change is
     *    a round trip; a non-spectator making this large a position jump is exactly the case
     *    `handleMovePlayer` rubber-bands. The gesture is not dropped in the meantime — the camera
     *    state has been accumulating all along — so the drag resumes seamlessly the moment the
     *    server answers.
     * 2. **`setPos`/`setYRot`, deliberately NOT `absSnapTo`.** `absSnapTo` also writes the
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
        if (!player.isSpectator) return
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
```

Then in `src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt`, add the import
`com.breadmoirai.garnet.camera.input.registerOrbitCamera` and the call `registerOrbitCamera()` immediately after `registerDockFocusKeybind()`.

- [ ] **Step 2: Verify it compiles**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`

Expected: BUILD SUCCESSFUL. If `player.yRot = ...` does not resolve as a Kotlin property, use `player.setYRot(...)`/`player.setXRot(...)`; if `player.eyeHeight` does not resolve, use `player.getEyeHeight()`. Confirm the accessor against the decompiled source rather than guessing.

- [ ] **Step 3: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/camera/input/OrbitCameraController.kt \
        src/client/kotlin/com/breadmoirai/garnet/GarnetClient.kt
git commit -m "feat: drive the orbit camera by moving the spectating player

Entry is lazy: the first world gesture seeds the camera from the player's
current pose (so entry is visually a no-op) and requests spectator, while the
gesture keeps accumulating and only reaches the player once spectator lands --
a non-spectator making that jump is what handleMovePlayer rubber-bands.

Uses setPos/setYRot rather than absSnapTo on purpose. absSnapTo also writes the
previous-tick fields, which is right for a teleport and wrong for a drag: the
render interpolator is what makes a 20 Hz camera update smooth at 144 fps."
```

---

### Task 4: Route world gestures to the camera, and delete click-to-return

The behaviour change. This is the task a reviewer is most likely to push back on, because it removes a shipped gesture.

**Files:**
- Modify: `src/client/kotlin/com/breadmoirai/garnet/dock/input/DockInputRouter.kt`
- Modify: `src/client/java/com/breadmoirai/garnet/mixin/client/MouseHandlerMixin.java`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/dock/input/DockInputSpec.kt` (path may differ — locate it first)
- Modify: `docs/ui/dock-input-routing.md`
- Modify: `docs/architecture/shrink-viewport-compose-model.md`

**Interfaces:**
- Consumes: `OrbitCameraController.orbitBy/panBy/dollyBy/exit/active` (Task 3).
- Produces: `DockInputRouter.onGlfwPress/onGlfwRelease/onGlfwMove/onGlfwScroll` keep their signatures. `consumeSwallowedRelease` is **removed**.

- [ ] **Step 1: Rewrite the world branch in `DockInputRouter`**

Replace `onGlfwPress`, `onGlfwRelease`, `onGlfwMove` and `onGlfwScroll` with:

```kotlin
    /** Which world gesture a held button is currently driving, or null when none is. */
    private enum class WorldDrag { ORBIT, PAN }
    @Volatile private var worldDrag: WorldDrag? = null

    fun onGlfwMove(x: Double, y: Double) {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (!captured) return
        when (worldDrag) {
            WorldDrag.ORBIT -> OrbitCameraController.orbitBy(dx, dy)
            WorldDrag.PAN -> OrbitCameraController.panBy(dx, dy)
            null -> ComposeInput.sendPointerMove(Offset(x.toFloat(), y.toFloat()))
        }
    }

    /**
     * Press while a panel is focused.
     *
     * A press over the **bare world viewport** begins a camera gesture — left orbits, middle pans —
     * and never reaches Compose. It does *not* drop dock focus: with the cursor freed, the world is
     * a viewport to fly around, and `G` is the way back to playing.
     *
     * This replaces click-to-return-to-game. That gesture had to go: it and orbit want the same
     * press, and a drag that sometimes ends in "you are now back in the game holding a pickaxe" is
     * worse than a single unambiguous exit key.
     *
     * Everything else routes into the scene as before.
     */
    fun onGlfwPress(button: Int) {
        if (!captured) return
        if (geometryKnown && regionUnderCursor() == null) {
            worldDrag = when (button) {
                GLFW.GLFW_MOUSE_BUTTON_LEFT -> WorldDrag.ORBIT
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> WorldDrag.PAN
                else -> null
            }
            return
        }
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeInput.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    fun onGlfwRelease(button: Int) {
        if (!captured) return
        if (worldDrag != null) {
            // Ends the gesture but does NOT leave camera mode: the camera keeps its pivot and
            // angle between drags, which is the whole point of orbiting a fixed subject.
            worldDrag = null
            return
        }
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeInput.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    /** Scroll dollies over the bare world and scrolls the panel over a dock region. */
    fun onGlfwScroll(dx: Double, dy: Double) {
        if (!captured) return
        if (geometryKnown && regionUnderCursor() == null) {
            OrbitCameraController.dollyBy(dy)
            return
        }
        ComposeInput.sendScroll(Offset(lastX.toFloat(), lastY.toFloat()), Offset(dx.toFloat(), dy.toFloat()))
    }
```

Add the import `com.breadmoirai.garnet.camera.input.OrbitCameraController`.

- [ ] **Step 2: Delete the now-dead swallow-release machinery**

In `DockInputRouter.kt`, delete the `swallowRelease` property and the entire `consumeSwallowedRelease` function. They exist only because the world press *dropped capture*, which made the matching release arrive uncaptured and reach vanilla as an unmatched button-up. The world press now keeps capture, so the release arrives captured and routes through `onGlfwRelease` normally.

In `MouseHandlerMixin.java`, delete the release branch that calls it, leaving:

```java
        if (!DockInputRouter.INSTANCE.getCaptured()) {
            // Uncaptured is not an unconditional fall-through: click-to-focus works inbound too.
            // A click on a dock region while a vanilla Screen is open focuses that region and lands
            // on the widget (onGlfwPressUncaptured). Returns false unless it actually handled the
            // event, so with the dock closed this branch is a plain field read and input stays
            // byte-for-byte vanilla.
            if (action == GLFW.GLFW_PRESS) {
                if (DockInputRouter.INSTANCE.onGlfwPressUncaptured(buttonInfo.button())) ci.cancel();
            }
            return;
        }
```

Update that mixin's class javadoc, which currently says `onButton` "consults two click-to-focus helpers" — it now consults one.

- [ ] **Step 3: Update `DockInputRouter`'s class KDoc and the `G` keybind's KDoc**

`DockInputRouter`'s class KDoc currently ends: *"it is dropped by any of those plus ESC and a click on the bare world."* Drop the last clause — a click on the bare world no longer drops focus. `DockFocusKeybind.kt`'s KDoc says *"Pressing it again — like ESC, or clicking the bare world — hands both back"* and *"ESC and a click on the bare world remain the other two ways out"*; both must lose the bare-world clause. Leaving them is exactly the "doc that describes an architecture the code abandoned" the project rule names.

- [ ] **Step 4: Remove the obsolete clientTest step**

Locate the clientTest that covers click-to-return (`grep -rn "consumeSwallowedRelease" src/clientTest`). Delete that step and its `consumeSwallowedRelease` assertion, and renumber the remaining steps in the spec's own numbering and in its KDoc so the list stays honest.

- [ ] **Step 5: Update the docs**

In `docs/ui/dock-input-routing.md`:
- Delete the **World → game** bullet from "Click-to-focus, both directions" and the sentence about the asymmetry between leaving and entering, which no longer has two halves.
- Delete the swallow-release paragraph from "What is and isn't forwarded".
- Add a new subsection describing the world-gesture table (LMB orbit, MMB pan, scroll dolly, `G` the only exit).
- Update the frontmatter `summary:` — it currently advertises the removed gesture.

In `docs/architecture/shrink-viewport-compose-model.md`, fix the sentence in the cursor-remap section that refers to a world click as the way out.

Run `grep -rn "consumeSwallowedRelease\|swallowRelease\|click on the bare world\|clicking the bare world" docs/ src/` and confirm zero hits describing the removed behaviour.

- [ ] **Step 6: Verify it compiles**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add src/client/kotlin/com/breadmoirai/garnet/dock/input/DockInputRouter.kt \
        src/client/java/com/breadmoirai/garnet/mixin/client/MouseHandlerMixin.java \
        src/client/kotlin/com/breadmoirai/garnet/dock/input/DockFocusKeybind.kt \
        src/clientTest docs/
git commit -m "feat: route bare-world gestures to the orbit camera

LMB orbits, MMB pans, scroll dollies; a world press no longer drops dock focus,
so G becomes the single way back to playing. Click-to-return-to-game and orbit
want the same press, and a drag that sometimes ends with you back in the game
holding a pickaxe is worse than one unambiguous exit key.

That makes swallowRelease/consumeSwallowedRelease dead -- they existed only
because the world press dropped capture, leaving vanilla an unmatched button-up.
Removed along with the mixin branch, the clientTest step and the docs for all of
it."
```

---

### Task 5: Client gametest for the gesture path

Drives the real router the way `DockFocusKeybindSpec` drives both halves of `G` — no mocks, production path only.

**Files:**
- Create: `src/clientTest/kotlin/com/breadmoirai/garnet/camera/OrbitCameraSpec.kt`
- Modify: `src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–4.
- Produces: nothing consumed by later tasks.

- [ ] **Step 1: Read the two specs this one imitates**

Read `src/clientTest/kotlin/com/breadmoirai/garnet/dock/input/DockFocusKeybindSpec.kt` and the `DockInputSpec` edited in Task 4 in full before writing anything. Copy their context setup, their `DockState.reset()` + panel-registration idiom, and their `ComposeSurface.disabled` skip guard. Autoscan is **off** — a spec that is not added to `ClientTestSentinel` silently never runs.

- [ ] **Step 2: Write the spec**

Cover exactly these cases, each as a numbered step in one merged story (the convention `DockInputSpec` uses):

1. **Assert `ViewportState.realWidth/realHeight` are non-zero first.** Every world-vs-region decision hit-tests against that cached size and *skips itself* when it is unknown, so without this assertion every step below passes vacuously.
2. With LEFT focused, `onGlfwMove` to a bare-world coordinate, `onGlfwPress(GLFW_MOUSE_BUTTON_LEFT)`, `onGlfwMove` some pixels away: `DockState.focusedRegion` is still `LEFT` (the press did **not** drop focus) and `OrbitCameraController.active` is `true`.
3. The same drag over a **visible dock region**'s coordinates leaves `OrbitCameraController.active` untouched and increments a probe panel's pointer counter — region gestures still reach Compose.
4. `onGlfwScroll(0.0, 1.0)` over the bare world does not reach the probe panel; the same scroll over the region does.
5. `onGlfwPress` + `onGlfwRelease` over the world, then a further `onGlfwMove`: the move after the release no longer orbits (the drag ended) but `OrbitCameraController.active` is still `true` (camera mode persists between drags).
6. `onGlfwKey(<the dock-focus key>, GLFW_PRESS, 0)` exits: `OrbitCameraController.active` is `false` and `DockState.focusedRegion` is `null`.
7. Entry does not move the player before spectator lands: with the player *not* a spectator, drive a world drag and assert the player's position is unchanged after a client tick, while `OrbitCameraController.active` is already `true`.

Use `OrbitCameraController.exit()` in an `afterTest`-equivalent so a failing step cannot strand the test client in spectator and cascade into every later spec.

- [ ] **Step 3: Register the spec**

Add it to `ClientTestSentinel` alongside the existing entries.

- [ ] **Step 4: Run it**

Run: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"`

Expected: PASS, and the log shows the spec actually ran (a spec missing from the sentinel produces a *green* build having run nothing — check for its name in the output, do not trust the exit code alone).

- [ ] **Step 5: Commit**

```bash
git add src/clientTest/kotlin/com/breadmoirai/garnet/camera/OrbitCameraSpec.kt \
        src/clientTest/kotlin/com/breadmoirai/garnet/test/ClientTestSentinel.kt
git commit -m "test: pin the orbit camera's gesture routing end to end

Drives the real router, not a mock: a world drag orbits without dropping focus,
a region drag still reaches Compose, scroll splits the same way, camera mode
survives between drags, and G exits. Asserts the cached framebuffer size is
non-zero first -- every world-vs-region decision skips itself when it is unknown,
so without that the whole spec passes vacuously."
```

---

### Task 6: Document the subsystem

**Files:**
- Create: `docs/ui/orbit-camera.md`
- Modify: `docs/ui/INDEX.md`
- Modify: `docs/architecture/module-map.md`
- Modify: `docs/persistence/network-payload-contract.md`

- [ ] **Step 1: Write the article**

Create `docs/ui/orbit-camera.md` with the standard frontmatter:

```
---
title: The orbit camera — moving the player to fly the viewport
tags: [camera, input, viewport, spectator, networking]
summary: <one line>
---
```

Cover, as prose that explains *why* rather than restating the code:
- The authority split, and the two decompiled-26.2 facts that justify it (`handleMovePlayer` exempts spectators from the distance and collision checks; `LocalPlayer` already sends its own position). Cite them as descriptions, not `file:line` — those drift.
- Why entry is lazy and gesture-driven rather than bound to `G`.
- Why the controller waits for `isSpectator()` before moving the player.
- Why it uses `setPos`/`setYRot` and **not** `absSnapTo` — the interpolation argument. This is the single most non-obvious line in the subsystem and the one a future reader would "fix" back.
- Why pan scales with distance and dolly is multiplicative.
- Why restore reads vanilla's previous-gamemode field instead of storing one.
- That exit leaves the player where the camera ended, and that this is a knowing choice.

- [ ] **Step 2: Register and cross-reference**

- Add to `docs/ui/INDEX.md` as `- [Title](orbit-camera.md) — summary` plus tags, matching the file's existing format exactly.
- Add the `camera/` package to `docs/architecture/module-map.md`'s tour, in the same style as its siblings.
- Add `CameraModeC2S` to `docs/persistence/network-payload-contract.md`'s payload inventory, noting it is the one payload outside the `editor/*/network/` files that rides the same registry spine.
- Add a "See also" link from `docs/ui/dock-input-routing.md`'s new world-gesture section to this article.

- [ ] **Step 3: Verify every cross-reference resolves**

Run `grep -rn "orbit-camera.md" docs/` and confirm each hit points at a real file with matching summary text. Confirm `docs/ui/INDEX.md`'s new line matches the article's own `summary:` frontmatter verbatim.

- [ ] **Step 4: Commit**

```bash
git add docs/
git commit -m "docs: document the orbit camera subsystem

Records the reasoning the code cannot: why the client owns the camera outright,
why entry waits for spectator to land, and why the controller uses setPos rather
than the absSnapTo that looks like the obviously-correct call."
```

---

## Final verification

- [ ] Full compile: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:clientClasses :26.2:classes :26.2:gametestClasses :26.2:clientTestClasses :26.2:testClasses"`
- [ ] Unit tests: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:test"`, then read `versions/26.2/build/test-results/test/TEST-com.breadmoirai.garnet.camera.data.OrbitCameraTest.xml`
- [ ] Gametests: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runGameTest"`
- [ ] Client gametests: `cmd.exe /c "cd /d H:\\Repo\\Garnet && gradlew.bat :26.2:runClientTest"` — and confirm `OrbitCameraSpec` appears by name in the output
- [ ] `grep -rn "consumeSwallowedRelease\|swallowRelease" src/ docs/` returns zero hits
- [ ] Manual smoke test in a real client: `G`, drag to orbit, middle-drag to pan, scroll to zoom, `G` to leave, confirm the gamemode came back and the player is standing where the camera was

## Deliberately not in scope

- **No config surface** for sensitivities or clamps. They are constants in `camera/data/`. If they need tuning that is a follow-up with real usage behind it.
- **No pivot indicator.** Rendering a marker at the orbit pivot would help orientation, but it is a render-pipeline change and this feature deliberately touches none.
- **No editor-dimension gating.** Camera mode works wherever the dock has focus. The spec records the two accepted consequences: exit-stays-put is a free teleport in survival, and the editor world's lifecycle has not been checked against a *spectating* player. Both are named in the spec's Open Risks, not silently dropped.
