package com.breadmoirai.garnet.camera.data

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan

/**
 * The cursor → world-ray arithmetic behind pivot selection.
 *
 * With the dock focused the cursor is a free pointer somewhere over the world viewport, so the
 * block the user is pointing at is **not** the one under the crosshair — and the world is rendered
 * into an offset sub-rect of the real window besides. Everything that could get that wrong (the
 * sub-rect origin, the aspect term, the sign of either screen axis) is pinned here rather than left
 * to a client run.
 */
class CursorRayTest : FunSpec({

    // A 16:9 viewport offset by a 300px left dock strip, at MC's default 70-degree vertical FOV.
    val rectX = 300
    val rectY = 0
    val rectW = 1600
    val rectH = 900
    val fov = 70.0
    val aspect = rectW.toDouble() / rectH
    val tanHalf = tan(Math.toRadians(fov / 2.0))

    fun ray(cursorX: Double, cursorY: Double, yaw: Float = 0f, pitch: Float = 0f) =
        cursorRay(yaw, pitch, cursorX, cursorY, rectX, rectY, rectW, rectH, fov)

    test("a cursor at the centre of the viewport gives the plain view vector") {
        // The crosshair case: the one cursor position for which the old `player.pick()` behaviour
        // was already correct, so it has to survive unchanged.
        val centre = ray(rectX + rectW / 2.0, rectY + rectH / 2.0, yaw = 25f, pitch = -14f)
        val forward = viewVector(25f, -14f)

        centre.x shouldBe (forward.x plusOrMinus 1e-9)
        centre.y shouldBe (forward.y plusOrMinus 1e-9)
        centre.z shouldBe (forward.z plusOrMinus 1e-9)
    }

    test("the centre is the centre of the sub-rect, not of the window") {
        // The regression this function exists for: the world renders into a rect offset by the
        // dock's reserved strips, so ignoring rectX aims a sixth of a screen off target.
        val windowCentre = ray(rectW / 2.0, rectH / 2.0)

        val ndcX = 2.0 * (rectW / 2.0 - rectX) / rectW - 1.0
        windowCentre.dot(rightVector(0f)) shouldBe (sin(atan(ndcX * aspect * tanHalf)) plusOrMinus 1e-9)
        (windowCentre.dot(rightVector(0f)) < 0.0).shouldBeTrue()
    }

    test("a cursor at the right edge tilts the ray right by the horizontal half-FOV") {
        val edge = ray(rectX + rectW.toDouble(), rectY + rectH / 2.0)
        val halfHorizontalFov = atan(aspect * tanHalf)

        acos(edge.dot(viewVector(0f, 0f))) shouldBe (halfHorizontalFov plusOrMinus 1e-9)
        // Signed, not just angular: an inverted horizontal axis has exactly the same angle.
        edge.dot(rightVector(0f)) shouldBe (sin(halfHorizontalFov) plusOrMinus 1e-9)
    }

    test("the vertical half-FOV is the FOV itself — that is what 'vertical FOV' means") {
        val top = ray(rectX + rectW / 2.0, rectY.toDouble())

        acos(top.dot(viewVector(0f, 0f))) shouldBe (Math.toRadians(fov / 2.0) plusOrMinus 1e-9)
    }

    test("screen Y grows downward, so a cursor above the centre aims upward") {
        val above = ray(rectX + rectW / 2.0, rectY + rectH / 4.0)
        val below = ray(rectX + rectW / 2.0, rectY + rectH * 3.0 / 4.0)

        (above.y > 0.0).shouldBeTrue()
        (below.y < 0.0).shouldBeTrue()
        above.y shouldBe (-below.y plusOrMinus 1e-9)
    }

    test("the ray is a unit vector") {
        ray(rectX + 13.0, rectY + 877.0, yaw = 143f, pitch = 31f).length() shouldBe (1.0 plusOrMinus 1e-9)
    }

    test("the basis rotates with the camera: at yaw 90 a rightward cursor tilts along -Z") {
        // Yaw 90 looks toward -X (west); facing west your right hand points north, which is -Z.
        // Asserted signed for the same reason pan's yaw-90 test is: an inverted axis passes any
        // magnitude-only check.
        val edge = ray(rectX + rectW.toDouble(), rectY + rectH / 2.0, yaw = 90f)

        (edge.z < 0.0).shouldBeTrue()
        edge.dot(rightVector(90f)) shouldBe (sin(atan(aspect * tanHalf)) plusOrMinus 1e-9)
    }

    test("pitch tilts the ray's vertical basis but never rolls it — right stays horizontal") {
        // The camera has no roll, so the right basis is a function of yaw alone at any pitch.
        // Without that, a steeply pitched view shears: the cursor's horizontal axis stops being
        // the screen's horizontal axis.
        rightVector(41f).y shouldBe (0.0 plusOrMinus 1e-9)

        val steep = ray(rectX + rectW.toDouble(), rectY + rectH / 2.0, pitch = 80f)
        steep.y shouldBe (viewVector(0f, 80f).y * steep.dot(viewVector(0f, 80f)) plusOrMinus 1e-9)
    }
})
