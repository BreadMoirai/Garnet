package com.breadmoirai.garnet.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.breadmoirai.garnet.camera.data.viewVector
import com.breadmoirai.garnet.camera.input.OrbitCameraController
import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.input.DockInputRouter
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.shell.regionAt
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.test.closeClientScreen
import com.breadmoirai.garnet.test.onClient
import com.breadmoirai.garnet.test.runOnClient
import com.breadmoirai.garnet.test.waitClientTicks
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import net.minecraft.world.phys.Vec3
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.acos

/**
 * The hovered-block highlight: `Minecraft.hitResult` follows the **free cursor** while the dock has
 * focus and the pointer is over the bare world, and vanilla's crosshair pick is left alone otherwise.
 *
 * The observable is the *direction* of the installed hit, measured as the angle between
 * `hitResult.location - cameraPosition` and the camera's own view vector. That is deliberately not a
 * block-identity assertion, because it holds in any world the harness happens to spawn in:
 *
 * - On a **hit**, the location is the point on the block's outline the ray entered, which lies along
 *   the ray by construction.
 * - On a **miss**, `Level.clip` returns the far end of the ray, which lies along it too.
 *
 * So the angle answers exactly the question this feature is about — *was the pick aimed at the
 * cursor or at the crosshair?* — without depending on there being a particular block anywhere. A
 * spec that placed a block and asserted its `BlockPos` would be strictly weaker: it could pass with
 * the crosshair pick if the block happened to be near the middle of the screen.
 *
 * The centred-cursor step is what stops the off-centre step from passing vacuously. Both use the
 * same machinery; only the cursor differs, and at the centre of the content rect the cursor ray and
 * the view vector are the same ray, so the angle must collapse to zero.
 */
class WorldHoverSpec : ClientSpec({

    /** The player's pose before this spec touched anything, restored in teardown. */
    val startPos = AtomicReference<Vec3?>(null)

    /**
     * Teardown, not tidiness: every spec in this run shares one client, and a step that failed after
     * taking dock focus would leave the router captured for every later spec. [OrbitCameraController]
     * is exited too — nothing here should arm it (hovering is not a gesture), and that is itself one
     * of the assertions below, but a failed run must not leave the client spectating.
     */
    afterTest {
        runOnClient { mc ->
            OrbitCameraController.exit()
            DockInputRouter.clearFocus()
            ComposeOverlay.enabled = false
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        startPos.get()?.let { pos ->
            runOnClient { mc -> mc.player?.setPos(pos.x, pos.y, pos.z) }
        }
        waitClientTicks(2)
    }

    test("the block pick follows the free cursor over the world and the crosshair everywhere else") {
        closeClientScreen()
        waitClientTicks(2)

        runOnClient { mc ->
            DockState.reset()
            DockState.panels += Panel(
                "garnet.test.hover", "HoverProbe", DockRegion.LEFT,
                AllIconsKeys.General.Information,
            ) { Box(Modifier.fillMaxSize()) }
            DockState.showPanel("garnet.test.hover")
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
            startPos.set(mc.player?.position())
        }
        waitClientTicks(12)
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(4)

        // The router skips the world-vs-region split entirely while the framebuffer size is unknown,
        // so without this every assertion below could pass on a client that never routed anything.
        withClue("the router's world-vs-region split needs a cached framebuffer size") {
            onClient { ViewportState.realWidth > 0 && ViewportState.realHeight > 0 }.shouldBeTrue()
        }
        val realW = onClient { ViewportState.realWidth }
        val realH = onClient { ViewportState.realHeight }
        val rect = onClient { ViewportState.contentRect(realW, realH) }

        // The centre of the *content rect*, not of the window: with the LEFT strip open the two are
        // hundreds of pixels apart, and it is the content rect the projection was built for.
        val centreX = rect.frameX + rect.frameWidth / 2.0
        val centreY = rect.frameY + rect.frameHeight / 2.0
        // Three quarters of the way across the free rect: far enough off-axis that no plausible
        // rounding could confuse it with the crosshair, still comfortably inside the world.
        val offCentreX = rect.frameX + rect.frameWidth * 0.75
        val regionX = 80.0
        val regionY = 80.0
        withClue("($centreX, $centreY) must be bare world") {
            onClient { DockState.regionAt(centreX.toInt(), centreY.toInt(), realW, realH) } shouldBe null
        }
        withClue("($offCentreX, $centreY) must be bare world") {
            onClient { DockState.regionAt(offCentreX.toInt(), centreY.toInt(), realW, realH) } shouldBe null
        }
        withClue("($regionX, $regionY) must be the visible LEFT region") {
            onClient { DockState.regionAt(regionX.toInt(), regionY.toInt(), realW, realH) } shouldBe
                DockRegion.LEFT
        }

        /** Degrees between the current `hitResult` and the camera's own view axis. */
        suspend fun pickAngleDegrees(): Double = onClient { mc ->
            val camera = mc.gameRenderer.mainCamera()
            val hit = mc.hitResult ?: return@onClient -1.0
            val toHit = hit.location.subtract(camera.position())
            if (toHit.lengthSqr() < 1e-9) return@onClient -1.0
            val cos = toHit.normalize().dot(viewVector(camera.yRot(), camera.xRot()))
            Math.toDegrees(acos(cos.coerceIn(-1.0, 1.0)))
        }

        // 1. cursor over the bare world, dead centre of the content rect. The cursor ray and the
        // view vector coincide there, so the pick must be on-axis whichever code produced it. This
        // is the control: it proves the measurement itself reads zero when it should.
        runOnClient { DockInputRouter.onGlfwMove(centreX, centreY) }
        waitClientTicks(2)
        onClient { DockInputRouter.hoveringWorld }.shouldBeTrue()
        withClue("a centred cursor aims down the view axis") {
            pickAngleDegrees() shouldBeLessThan 1.0
        }

        // 2. the same cursor, moved well off-axis. Vanilla's pick cannot follow it — it casts along
        // the view vector and would still read ~0 — so a large angle here is the whole feature.
        runOnClient { DockInputRouter.onGlfwMove(offCentreX, centreY) }
        waitClientTicks(2)
        onClient { DockInputRouter.hoveringWorld }.shouldBeTrue()
        withClue("an off-centre cursor must aim off-axis, not down the crosshair") {
            pickAngleDegrees() shouldBeGreaterThan 5.0
        }

        // 3. hovering is not a gesture: looking at blocks must never round-trip the player's
        // gamemode. Two moves over the world have happened and nothing may be armed.
        withClue("hovering must not arm camera mode") {
            onClient { OrbitCameraController.active }.shouldBeFalse()
        }

        // 4. cursor onto the dock panel. The pointer is no longer over the world, so the override
        // stops applying and vanilla's crosshair result is what stands — back on-axis.
        runOnClient { DockInputRouter.onGlfwMove(regionX, regionY) }
        waitClientTicks(2)
        onClient { DockInputRouter.hoveringWorld }.shouldBeFalse()
        withClue("over a panel the crosshair pick must be left alone") {
            pickAngleDegrees() shouldBeLessThan 1.0
        }

        // 5. dropping dock focus re-grabs the cursor, at which point the crosshair genuinely *is*
        // the pointer again and the override must stay out of the way even over world pixels.
        runOnClient { DockInputRouter.clearFocus() }
        runOnClient { DockInputRouter.onGlfwMove(offCentreX, centreY) }
        waitClientTicks(2)
        onClient { DockInputRouter.hoveringWorld }.shouldBeFalse()
        withClue("with the cursor grabbed the crosshair is the pointer again") {
            pickAngleDegrees() shouldBeLessThan 1.0
        }
    }
})
