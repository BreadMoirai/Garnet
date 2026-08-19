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
        // At yaw 0 the camera looks along +Z (south), so its right is -X (west) and its up is +Y.
        // Dragging the cursor right must carry the scene right with it, which means the pivot --
        // and the camera rigidly following it -- moves the OTHER way, to +X.
        val panned = origin.pan(dx = 10.0, dy = 0.0)

        panned.pivot.x shouldBe (10.0 * PAN_SENSITIVITY * 5.0 plusOrMinus 1e-9)
        panned.pivot.y shouldBe (0.0 plusOrMinus 1e-9)
        panned.pivot.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pan drags the scene with the cursor vertically: a downward drag raises the pivot") {
        // Screen Y grows downward, so dy > 0 is a downward drag. For the scene to follow the
        // cursor down, the pivot — what sits under the crosshair — must move up (+Y at yaw 0).
        val panned = origin.pan(dx = 0.0, dy = 10.0)

        panned.pivot.x shouldBe (0.0 plusOrMinus 1e-9)
        panned.pivot.y shouldBe (10.0 * PAN_SENSITIVITY * 5.0 plusOrMinus 1e-9)
        panned.pivot.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pan at yaw 90 moves the pivot along Z — the basis rotated with the camera") {
        // Yaw 90 looks west, so right is now +Z. Asserted signed, not by magnitude: an
        // absolute-value assertion here is what let a fully inverted horizontal pan ship.
        val panned = origin.copy(yaw = 90f).pan(dx = 10.0, dy = 0.0)

        panned.pivot.x shouldBe (0.0 plusOrMinus 1e-6)
        panned.pivot.z shouldBe (10.0 * PAN_SENSITIVITY * 5.0 plusOrMinus 1e-6)
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
