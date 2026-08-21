package com.breadmoirai.garnet.camera.input

import com.breadmoirai.garnet.camera.data.PIVOT_RAYCAST_RANGE
import com.breadmoirai.garnet.camera.data.cursorRay
import com.breadmoirai.garnet.camera.data.viewVector
import com.breadmoirai.garnet.dock.viewport.ViewportState
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

/**
 * Turning a **free cursor** into a world raycast.
 *
 * Two features need this and need it to agree: [OrbitCameraController] picks its pivot with it, and
 * [WorldHover] highlights the hovered block with it. They must pick the same block from the same
 * pixel — a highlight that outlines one block while the press orbits another is worse than no
 * highlight — so the projection inverse and the clip settings live here once rather than twice.
 *
 * What differs between the callers is only *which* cursor position and *which* eye/angle to cast
 * from, so both are parameters and this object holds no state at all.
 */
internal object CursorPick {

    /**
     * The direction a cursor at [cursorX]/[cursorY] points, for a view along [yaw]/[pitch].
     *
     * `Entity.pick` — what the pivot used to call — casts along the *view* vector, which is the
     * centre of the rendered image. That is the right ray in ordinary play, where the cursor is
     * grabbed and the crosshair *is* the pointer. It is the wrong one here: the dock has freed the
     * cursor, so the user is pointing at a block somewhere off to the side and the ray would land on
     * whatever happened to be dead centre instead. Worse, "dead centre" is the centre of the shrunk
     * content sub-rect the dock leaves free, not of the window, so with a wide left strip open it is
     * not even where the cursor visually rests.
     *
     * Falls back to the plain view vector when there is nothing better to go on — no cursor position
     * has been recorded yet, or `WindowMixin` has not cached a real framebuffer size, so the sub-rect
     * is unknowable. That reproduces crosshair behaviour rather than aiming with a coordinate we
     * cannot interpret.
     */
    fun ray(mc: Minecraft, yaw: Float, pitch: Float, cursorX: Double?, cursorY: Double?): Vec3 {
        val crosshair = viewVector(yaw, pitch)
        if (cursorX == null || cursorY == null) return crosshair
        val realW = ViewportState.realWidth
        val realH = ViewportState.realHeight
        if (realW <= 0 || realH <= 0) return crosshair
        // With the shrink off the game fills the window, so the rect is the window itself.
        val rect = if (ViewportState.shouldModify()) {
            ViewportState.contentRect(realW, realH)
        } else {
            ViewportState.ContentRect(0, 0, realW, realH)
        }
        // The *rendered* FOV, not the option: MC folds a flying/sprinting modifier into it (77
        // rather than 70 while spectating, observed in a real client), and the ray has to invert
        // the projection actually on screen.
        val fov = mc.gameRenderer.mainCamera().fov.toDouble()
        if (fov <= 0.0) return crosshair
        return cursorRay(
            yaw = yaw,
            pitch = pitch,
            cursorX = cursorX,
            cursorY = cursorY,
            rectX = rect.frameX,
            rectY = rect.frameY,
            rectW = rect.frameWidth,
            rectH = rect.frameHeight,
            fovDegrees = fov,
        )
    }

    /**
     * Cast [ray] from [eye] out to [PIVOT_RAYCAST_RANGE], or null when there is no world to cast in.
     *
     * Blocks only, and `OUTLINE` rather than `COLLIDER`, so the block chosen is the one the user
     * visibly aimed at — the same shape the block highlight draws — rather than one the ray stopped
     * on by passing through a fence's or a slab's empty half. Fluids are skipped: neither orbiting a
     * water surface you cannot see the edges of nor outlining one is what the user meant.
     *
     * The range is deliberately the pivot's 64 blocks rather than the player's interaction reach.
     * This is an editor viewport, not gameplay: the camera routinely sits further from the structure
     * than a player could reach into it, and a highlight that vanished at 4.5 blocks would disagree
     * with the click that orbits the very same block.
     *
     * Note that a returned result may still be a `MISS` — `Level.clip` never returns null. Callers
     * decide what that means; `blockHitOrNull` and `pivotFor` are the two answers.
     */
    fun clip(mc: Minecraft, entity: Entity, eye: Vec3, ray: Vec3): HitResult? {
        val level = mc.level ?: return null
        return level.clip(
            ClipContext(
                eye,
                eye.add(ray.scale(PIVOT_RAYCAST_RANGE)),
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                entity,
            ),
        )
    }
}
