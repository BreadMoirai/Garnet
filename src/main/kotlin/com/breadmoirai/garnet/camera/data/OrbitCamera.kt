package com.breadmoirai.garnet.camera.data

import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Degrees of rotation per pixel of mouse movement. */
const val ORBIT_SENSITIVITY: Float = 0.35f

/**
 * Blocks the camera travels per pixel of pan, in world space — **not** a fraction of [distance].
 *
 * Pan used to scale with distance, on the theory that a drag should cover the same fraction of the
 * screen at any zoom. That is the right rule for a camera orbiting a subject you chose from far
 * away, and the wrong one here, because the pivot is picked under the cursor and therefore lands
 * close constantly: at a hit 0.6 blocks away the old rate moved the camera 0.6 blocks per 500-pixel
 * drag, which reads as the view refusing to move at all.
 */
const val PAN_SENSITIVITY: Double = 0.02

/**
 * Blocks the camera travels along its view axis per scroll notch.
 *
 * Deliberately a world-space step rather than the multiplicative factor on [distance] this used to
 * be. Multiplicative zoom asymptotes into the pivot and stalls at [MIN_DISTANCE] — with a
 * cursor-picked pivot often less than a block away, scrolling did essentially nothing. A fixed step
 * moves the camera the same amount whatever the pivot is doing, and [dolly] pushes the pivot ahead
 * rather than stopping when the two would cross, so forward travel never runs out.
 */
const val DOLLY_STEP: Double = 0.5

/** The camera may never reach the pivot; at zero the look direction is undefined. */
const val MIN_DISTANCE: Double = 0.5

/** Past this the orbit radius is wider than any useful framing of a structure. */
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

/**
 * The camera's right-hand basis vector, derived from yaw alone.
 *
 * Pitch is deliberately not a factor: kept in the horizontal plane, "right" holds its screen-space
 * meaning when the camera looks steeply up or down instead of shearing with the pitch.
 *
 * Note the negation. MC's yaw 0 looks along +Z (south), and facing south your right hand points
 * *west*, which is -X. So the right basis vector is `-(cos, 0, sin)`, not `(cos, 0, sin)` — the
 * unnegated form is the LEFT vector, and using it inverts horizontal panning.
 */
fun rightVector(yaw: Float): Vec3 {
    val y = Math.toRadians(yaw.toDouble())
    return Vec3(-cos(y), 0.0, -sin(y))
}

/**
 * The camera's up basis vector, completing the frame with [rightVector] and [viewVector].
 *
 * A cross product rather than a third trig derivation: taken this way the three vectors cannot
 * disagree about handedness, which is the bug this shape rules out rather than a shortcut.
 */
fun upVector(yaw: Float, pitch: Float): Vec3 = rightVector(yaw).cross(viewVector(yaw, pitch))

/**
 * The world-space direction a cursor at [cursorX]/[cursorY] points, for a camera looking along
 * [yaw]/[pitch] and rendering into the window sub-rect at [rectX]/[rectY] sized [rectW] x [rectH]
 * with a vertical field of view of [fovDegrees].
 *
 * This is the inverse of the projection MC actually builds: `Matrix4f.setPerspective(fov * DEG_TO_RAD,
 * width / height, ...)` — JOML's `setPerspective` takes a **vertical** FOV, which is why the aspect
 * ratio multiplies the horizontal term only.
 *
 * The rect is not the window. While the dock is on screen `WindowMixin` shrinks the reported
 * framebuffer and `MinecraftPresentMixin` composites the result into the sub-rect the dock leaves
 * free, so the rendered image's centre — where the crosshair, and therefore `Entity.pick`, aims —
 * is offset from the window's centre by the reserved strips. Anything that treats a free cursor as
 * a world direction has to subtract that origin first.
 */
fun cursorRay(
    yaw: Float,
    pitch: Float,
    cursorX: Double,
    cursorY: Double,
    rectX: Int,
    rectY: Int,
    rectW: Int,
    rectH: Int,
    fovDegrees: Double,
): Vec3 {
    // Normalized device coordinates: -1..1 across the rect, Y flipped because screen Y grows
    // downward while the camera's up axis does not.
    val ndcX = 2.0 * (cursorX - rectX) / rectW - 1.0
    val ndcY = 1.0 - 2.0 * (cursorY - rectY) / rectH
    val tanHalf = tan(Math.toRadians(fovDegrees / 2.0))
    val aspect = rectW.toDouble() / rectH
    return viewVector(yaw, pitch)
        .add(rightVector(yaw).scale(ndcX * aspect * tanHalf))
        .add(upVector(yaw, pitch).scale(ndcY * tanHalf))
        .normalize()
}

/**
 * The orbit camera that sits at [eye] and looks at [pivot] — how entry seeds itself.
 *
 * Inverts [viewVector] rather than carrying the player's existing rotation over, because the pivot
 * is chosen under the *cursor* and the cursor is not the crosshair: the picked block is off the view
 * axis, and an [OrbitCamera] looks at its pivot by construction. Deriving the angle from the
 * eye→pivot direction is what keeps [eyePosition] equal to [eye], so entry moves the camera not at
 * all — the view only starts changing when the gesture that triggered entry is applied.
 *
 * Every field is clamped to the range the gestures themselves maintain, so a seeded camera is
 * immediately a legal one: pitch cannot start past [MAX_PITCH], and distance is held inside
 * [MIN_DISTANCE]..[MAX_DISTANCE] — which is why a pivot pressed against the lens shifts the eye
 * slightly rather than producing a camera that cannot be orbited back out.
 */
fun orbitAround(eye: Vec3, pivot: Vec3): OrbitCamera {
    val offset = pivot.subtract(eye)
    // A pivot exactly at the eye has no direction to look along; keep MC's default facing (+Z)
    // rather than normalizing a zero-length vector.
    val dir = if (offset.lengthSqr() < 1e-12) Vec3(0.0, 0.0, 1.0) else offset.normalize()
    return OrbitCamera(
        pivot = pivot,
        yaw = Math.toDegrees(atan2(-dir.x, dir.z)).toFloat(),
        pitch = Math.toDegrees(-asin(dir.y.coerceIn(-1.0, 1.0))).toFloat()
            .coerceIn(-MAX_PITCH, MAX_PITCH),
        distance = offset.length().coerceIn(MIN_DISTANCE, MAX_DISTANCE),
    )
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
 * Translate the whole camera across its own right/up plane — eye and pivot together, rigidly.
 *
 * The eye is not stored: [eyePosition] is derived from the pivot, so the eye travels exactly the
 * vector the pivot does. This slides the camera sideways through the world rather than sweeping it
 * around the subject, which is the difference between a pan and an [orbit].
 */
fun OrbitCamera.pan(dx: Double, dy: Double): OrbitCamera {
    val right = rightVector(yaw)
    val up = upVector(yaw, pitch)
    // Screen Y grows downward, so a downward drag (positive dy) must raise the pivot for the
    // scene to follow the cursor — hence +dy here, unlike the -dx above.
    return copy(
        pivot = pivot
            .add(right.scale(-dx * PAN_SENSITIVITY))
            .add(up.scale(dy * PAN_SENSITIVITY)),
    )
}

/**
 * Fly the camera along its own view axis, [DOLLY_STEP] blocks per scroll notch.
 *
 * A notch is a fixed distance of travel toward — or through — the point being looked at.
 * Ordinarily that is spent shortening [distance] — the camera closes on the pivot, which is
 * what zoom means — but the clamp can refuse part of the step, and when it does the refused
 * remainder is spent pushing the *pivot* along the same axis instead, so travel continues straight
 * through where the pivot was rather than stalling against it. The identity that makes this work,
 * with `shortfall = clamped - desired`:
 *
 * ```
 * eye' = (pivot + view * shortfall) - view * clamped
 *      = pivot - view * (clamped - shortfall)
 *      = pivot - view * desired               = eye + view * step
 * ```
 *
 * — the eye lands exactly where an unclamped step would have put it, so the camera never notices
 * the clamp it just hit.
 *
 * The previous multiplicative-factor version could not do this: it asymptoted into the pivot and
 * stopped dead at `MIN_DISTANCE`, which with a cursor-picked pivot often less than a block away made
 * scrolling forward feel like it had run out of world.
 */
fun OrbitCamera.dolly(scrollDy: Double): OrbitCamera {
    if (scrollDy == 0.0) return this
    val desired = distance - scrollDy * DOLLY_STEP
    val clamped = desired.coerceIn(MIN_DISTANCE, MAX_DISTANCE)
    val shortfall = clamped - desired
    return copy(
        pivot = if (shortfall == 0.0) pivot else pivot.add(viewVector(yaw, pitch).scale(shortfall)),
        distance = clamped,
    )
}

/**
 * The point to orbit around for a pivot raycast that returned [hit], with [missPivot] standing in
 * for a ray that hit nothing.
 *
 * A hit gives the **centre of the block cell**, not `hit.location`. The raw hit location is the
 * point on the block's outline where the ray entered it, which puts the pivot on the *surface*
 * facing the camera: orbiting a wall then sweeps around a point on its skin, so the block the user
 * picked slides across the screen and the far side of it can never be brought into view. Orbiting
 * the cell's centre keeps the picked block fixed under the cursor through a full sweep, which is
 * what "orbit this block" means.
 *
 * The cell centre, deliberately, and not the centroid of the shape that was actually hit: for a
 * slab, a fence post or a stair the two differ, and a pivot that moves depending on which part of a
 * block you clicked is harder to predict than one that is always `blockPos + (0.5, 0.5, 0.5)`. The
 * cost is that on a slab the pivot floats a quarter-block above the surface — a much smaller
 * surprise than the pivot shifting between clicks on the same block.
 *
 * `Type.MISS` is not the only non-block outcome — a world-border hit reports `BLOCK` from a
 * [BlockHitResult] whose `blockPos` is the clamped border cell — so this tests the result's own
 * type rather than assuming anything the clip did not state.
 */
fun pivotFor(hit: HitResult, missPivot: Vec3): Vec3 =
    if (hit is BlockHitResult && hit.type == HitResult.Type.BLOCK) Vec3.atCenterOf(hit.blockPos) else missPivot
