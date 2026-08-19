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
    //
    // Note the negation: MC's yaw 0 looks along +Z (south), and facing south your right hand points
    // *west*, which is -X. So the right basis vector is -(cos, 0, sin), not (cos, 0, sin) — the
    // unnegated form is the LEFT vector, and using it inverts horizontal panning.
    val y = Math.toRadians(yaw.toDouble())
    val right = Vec3(-cos(y), 0.0, -sin(y))
    val up = right.cross(forward)
    val scale = distance * PAN_SENSITIVITY
    // Screen Y grows downward, so a downward drag (positive dy) must raise the pivot for the
    // scene to follow the cursor — hence +dy here, unlike the -dx above.
    return copy(pivot = pivot.add(right.scale(-dx * scale)).add(up.scale(dy * scale)))
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
