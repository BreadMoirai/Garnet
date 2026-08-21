package com.breadmoirai.garnet.camera.input

import com.breadmoirai.garnet.dock.input.DockInputRouter
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.HitResult

/**
 * The block under the **free cursor**, for the vanilla block highlight to outline.
 *
 * In ordinary play the cursor is grabbed and the crosshair is the pointer, so `Minecraft.hitResult`
 * — cast along the view vector — is the block you are pointing at. Once the dock frees the cursor
 * that stops being true: the pointer is somewhere off to the side, and the crosshair aims at
 * whatever happens to sit at the centre of the shrunk content rect. `MinecraftPickMixin` replaces
 * `hitResult` with [pickResult] for exactly that window, which is why this is not a "draw an extra
 * outline" feature: the outline (and the F3 targeted-block readout with it) simply follows the real
 * pointer instead of a crosshair nobody is aiming.
 *
 * **Pose comes from the render camera**, not from [OrbitCameraController]. A ray that inverts the
 * projection has to start where the projection did, and the two disagree in two ordinary cases: the
 * highlight is live *before* any camera has been armed (the player's own eye), and while an armed
 * entry waits out the spectator round trip the camera has moved but the rendered view has not. The
 * render camera is right in both, at the cost of nothing — `Minecraft.runTick` calls
 * `gameRenderer.update(...)`, which updates `mainCamera`, immediately before `pick(...)`.
 *
 * **Threading:** main (render) thread only, like everything it neighbours — [moveTo] arrives through
 * `DockInputRouter`'s `minecraft.execute(...)`-wrapped GLFW callbacks and [pickResult] is read
 * from the `Minecraft#pick` mixin. See `DockInputRouter`'s threading note.
 */
object WorldHover {

    /**
     * Live window-pixel cursor position, or `null` before any move has been seen.
     *
     * Deliberately **not** `OrbitCameraController.aimAt`'s position, which the pivot pick uses: that
     * one holds where the *press* was and must keep holding it, because entry is lazy and fires on a
     * move that has already travelled a drag's worth away. The highlight wants the opposite — where
     * the cursor is *now* — so the two cannot share a field.
     *
     * Raw GLFW window coordinates, inheriting the router's assumption that those equal framebuffer
     * pixels (true unless the OS reports a fractional HiDPI content scale).
     */
    private var cursorX: Double? = null
    private var cursorY: Double? = null

    /** Record the cursor position. Called by `DockInputRouter` on every move, gesture or not. */
    fun moveTo(x: Double, y: Double) {
        cursorX = x
        cursorY = y
    }

    /**
     * The hit result the free cursor is aiming at, or null to leave vanilla's crosshair pick alone.
     *
     * A **miss is a result, not a null**: with the cursor free over open sky the honest answer is
     * "nothing targeted", and `Level.clip`'s `MISS` says exactly that — `LevelExtractor` skips it and
     * no outline is drawn. Returning null there instead would fall back to the crosshair's own hit
     * and outline whatever block sits behind the centre of the content rect, which is the bug this
     * whole feature exists to remove.
     *
     * Null is reserved for "this feature does not apply":
     *
     * 1. The cursor is not free over the world at all — no dock focus, or it is over a panel, or the
     *    framebuffer geometry is not yet known. See [DockInputRouter.hoveringWorld].
     * 2. There is no player, level, or initialized render camera to cast from.
     */
    fun pickResult(mc: Minecraft): HitResult? {
        if (!DockInputRouter.hoveringWorld) return null
        val player = mc.player ?: return null
        val camera = mc.gameRenderer.mainCamera()
        if (!camera.isInitialized) return null
        val ray = CursorPick.ray(mc, camera.yRot(), camera.xRot(), cursorX, cursorY)
        return CursorPick.clip(mc, player, camera.position(), ray)
    }
}
