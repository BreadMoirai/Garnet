package com.breadmoirai.garnet.camera.data

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/**
 * The orbit camera's arithmetic. Pure by construction — no `Minecraft`, no GLFW — so the behaviour
 * a player actually feels (which way a drag turns the view, that scroll and pan translate the
 * camera at a usable rate instead of stalling, that the camera cannot flip over the pole) is pinned here rather than only inside a client gametest
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

        panned.pivot.x shouldBe (10.0 * PAN_SENSITIVITY plusOrMinus 1e-9)
        panned.pivot.y shouldBe (0.0 plusOrMinus 1e-9)
        panned.pivot.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pan drags the scene with the cursor vertically: a downward drag raises the pivot") {
        // Screen Y grows downward, so dy > 0 is a downward drag. For the scene to follow the
        // cursor down, the pivot — what sits under the crosshair — must move up (+Y at yaw 0).
        val panned = origin.pan(dx = 0.0, dy = 10.0)

        panned.pivot.x shouldBe (0.0 plusOrMinus 1e-9)
        panned.pivot.y shouldBe (10.0 * PAN_SENSITIVITY plusOrMinus 1e-9)
        panned.pivot.z shouldBe (0.0 plusOrMinus 1e-9)
    }

    test("pan at yaw 90 moves the pivot along Z — the basis rotated with the camera") {
        // Yaw 90 looks west, so right is now +Z. Asserted signed, not by magnitude: an
        // absolute-value assertion here is what let a fully inverted horizontal pan ship.
        val panned = origin.copy(yaw = 90f).pan(dx = 10.0, dy = 0.0)

        panned.pivot.x shouldBe (0.0 plusOrMinus 1e-6)
        panned.pivot.z shouldBe (10.0 * PAN_SENSITIVITY plusOrMinus 1e-6)
    }

    test("pan moves the camera at a flat world rate, whatever the orbit radius is") {
        // Deliberately NOT proportional to distance any more. With the pivot picked under the
        // cursor it routinely lands under a block away, and the old distance-scaled rate then moved
        // the camera ~0.6 blocks per 500-pixel drag — the view simply refused to budge.
        val near = origin.copy(distance = 0.6).pan(dx = 10.0, dy = 0.0)
        val far = origin.copy(distance = 60.0).pan(dx = 10.0, dy = 0.0)

        near.pivot.x shouldBe (10.0 * PAN_SENSITIVITY plusOrMinus 1e-9)
        far.pivot.x shouldBe (near.pivot.x plusOrMinus 1e-9)
    }

    test("pan translates the whole camera, eye and pivot alike") {
        val panned = origin.pan(dx = 500.0, dy = 0.0)
        val eyeShift = panned.eyePosition.subtract(origin.eyePosition)
        val pivotShift = panned.pivot.subtract(origin.pivot)

        eyeShift.x shouldBe (pivotShift.x plusOrMinus 1e-9)
        eyeShift.y shouldBe (pivotShift.y plusOrMinus 1e-9)
        eyeShift.z shouldBe (pivotShift.z plusOrMinus 1e-9)
        // 500 px is roughly a full-screen drag; it must be worth a useful number of blocks.
        (eyeShift.length() > 5.0) shouldBe true
    }

    test("dolly moves the eye a fixed world step per notch, not a proportion of the distance") {
        val near = origin.copy(distance = 5.0)
        val far = origin.copy(distance = 100.0)

        near.dolly(1.0).eyePosition.subtract(near.eyePosition).length() shouldBe
            (DOLLY_STEP plusOrMinus 1e-9)
        far.dolly(1.0).eyePosition.subtract(far.eyePosition).length() shouldBe
            (DOLLY_STEP plusOrMinus 1e-9)
    }

    test("scrolling up moves the eye toward the pivot") {
        val closer = origin.dolly(1.0)

        closer.distance shouldBe (5.0 - DOLLY_STEP plusOrMinus 1e-9)
        // Toward the pivot, which sits at +Z of the eye at yaw 0.
        (closer.eyePosition.z > origin.eyePosition.z) shouldBe true
    }

    test("dolly flies straight through the pivot instead of stalling against the near clamp") {
        // The old multiplicative dolly asymptoted into the pivot and stopped dead at MIN_DISTANCE,
        // which is what made scrolling feel like a zoom control that had run out of travel. Past the
        // clamp the remainder of each step is spent pushing the pivot ahead, so the eye keeps moving
        // by exactly DOLLY_STEP every notch.
        var c = origin
        val startZ = c.eyePosition.z
        repeat(40) { c = c.dolly(1.0) }

        c.distance shouldBe MIN_DISTANCE
        c.eyePosition.z shouldBe (startZ + 40 * DOLLY_STEP plusOrMinus 1e-6)
        // ...and the pivot has been carried along ahead of it, still centred in the view.
        c.pivot.z shouldBe (c.eyePosition.z + MIN_DISTANCE plusOrMinus 1e-6)
    }

    test("dolly keeps retreating past the far clamp too, dragging the pivot back with it") {
        var c = origin
        val startZ = c.eyePosition.z
        repeat(600) { c = c.dolly(-1.0) }

        c.distance shouldBe MAX_DISTANCE
        c.eyePosition.z shouldBe (startZ - 600 * DOLLY_STEP plusOrMinus 1e-6)
    }

    test("the eye moves by exactly the step whether or not a clamp bites") {
        // The identity the clamp handling rests on: whatever is not spent on `distance` is spent on
        // the pivot, so the eye's travel is independent of both.
        listOf(0.6, 1.0, 5.0, 255.0, 256.0).forEach { d ->
            val c = origin.copy(distance = d)
            listOf(1.0, -1.0, 3.0, -3.0).forEach { notches ->
                withClue("distance=$d notches=$notches") {
                    c.dolly(notches).eyePosition.subtract(c.eyePosition).length() shouldBe
                        (abs(notches) * DOLLY_STEP plusOrMinus 1e-6)
                }
            }
        }
    }

    test("orbitAround leaves the eye exactly where it is — entry turns the camera, never moves it") {
        // The invariant that makes cursor-picked pivots safe to enter on: the pivot is off the
        // view axis, so the seed has to derive the angle from the eye->pivot direction. Carrying
        // the player's own yaw/pitch over instead teleports them to wherever that angle and
        // distance happen to put the eye.
        val eye = Vec3(12.0, 71.0, -4.0)
        val pivot = Vec3(20.0, 68.0, 9.0)

        val seeded = orbitAround(eye, pivot)

        seeded.pivot shouldBe pivot
        seeded.eyePosition.x shouldBe (eye.x plusOrMinus 1e-6)
        seeded.eyePosition.y shouldBe (eye.y plusOrMinus 1e-6)
        seeded.eyePosition.z shouldBe (eye.z plusOrMinus 1e-6)
        seeded.distance shouldBe (eye.distanceTo(pivot) plusOrMinus 1e-9)
    }

    test("orbitAround recovers the yaw/pitch that looks along the eye-to-pivot direction") {
        // Round trip through viewVector: whatever angle comes out must reproduce the direction
        // that went in, which is what actually centres the picked block in the view.
        val eye = Vec3.ZERO
        val direction = viewVector(143f, 27f)

        val seeded = orbitAround(eye, direction.scale(6.0))

        seeded.yaw shouldBe (143f plusOrMinus 1e-3f)
        seeded.pitch shouldBe (27f plusOrMinus 1e-3f)
    }

    test("orbitAround still respects the pitch and distance clamps") {
        // Aiming straight down is a legal cursor position; producing a camera at the degenerate
        // pole, or one closer than the near clamp, is not.
        val straightDown = orbitAround(Vec3(0.0, 10.0, 0.0), Vec3(0.0, 0.0, 0.0))
        straightDown.pitch shouldBe MAX_PITCH

        val onTopOfIt = orbitAround(Vec3.ZERO, Vec3(0.0, 0.0, 0.01))
        onTopOfIt.distance shouldBe MIN_DISTANCE

        val milesOff = orbitAround(Vec3.ZERO, Vec3(0.0, 0.0, 10_000.0))
        milesOff.distance shouldBe MAX_DISTANCE
    }

    test("orbitAround on a pivot at the eye falls back to MC's default facing instead of NaN") {
        val degenerate = orbitAround(Vec3.ZERO, Vec3.ZERO)

        degenerate.yaw shouldBe (0f plusOrMinus 1e-6f)
        degenerate.pitch shouldBe (0f plusOrMinus 1e-6f)
        degenerate.distance shouldBe MIN_DISTANCE
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
