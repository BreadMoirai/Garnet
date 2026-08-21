package com.breadmoirai.garnet.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
import io.kotest.matchers.shouldBe
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.world.level.GameType
import net.minecraft.world.phys.HitResult
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import java.util.concurrent.atomic.AtomicReference

/**
 * The **render** half of the hovered-block highlight: `GameRendererOutlineMixin` forces
 * `GameRenderer#shouldRenderBlockOutline` true while the free cursor is driving the pick, and leaves
 * vanilla's answer alone otherwise.
 *
 * `WorldHoverSpec` covers the pick — that `Minecraft.hitResult` follows the cursor — and passed
 * throughout the bug this spec pins. That is exactly the point: the pick was never broken. Vanilla
 * gates the *drawing* of the outline separately, and once a pan/zoom/orbit armed camera mode the
 * player was a spectator, whose `abilities.mayBuild` is false. `shouldRenderBlockOutline` then takes
 * its restricted branch and, for `GameType.SPECTATOR`, outlines only blocks with a container menu:
 *
 * ```java
 * if (renderOutline && !((Player)cameraEntity).getAbilities().mayBuild) {           // spectator: true
 *     if (this.minecraft.gameMode.getPlayerMode() == GameType.SPECTATOR) {
 *         renderOutline = blockState.getMenuProvider(this.minecraft.level, pos) != null;
 *     }
 * ```
 *
 * so every ordinary block went un-outlined for the rest of the session while the hit result underneath
 * stayed perfectly correct. The assertion here is on that boolean, not on pixels: a screenshot diff
 * would be at the mercy of whatever block the harness world put under the cursor, whereas the gate is
 * the precise thing that regressed.
 *
 * ## Spectator is applied locally, not asked for
 *
 * The restricted branch is reached with `mc.gameMode.setLocalMode(GameType.SPECTATOR)` — the client's
 * own view of its gamemode, which is all `shouldRenderBlockOutline` reads (`getPlayerMode()` plus the
 * abilities `setLocalMode` derives from it). No `CameraModeC2S` is sent and the server is never told,
 * so unlike `OrbitCameraSpec` this spec never actually round-trips the player's gamemode: the entity
 * keeps its attributes, its reach, and its position, and `Minecraft#pick` keeps producing an ordinary
 * block hit. Driving a real entry instead would have coupled this spec to the arm timeout and the
 * server's grant, neither of which has anything to do with the render gate.
 *
 * `abilities.flying` is snapshotted and restored by hand because `GameType.updatePlayerAbilities`
 * only ever *sets* it (spectator's branch) and never clears it on the way back to creative.
 */
class BlockOutlineGateSpec : ClientSpec({

    /** Client state this spec overwrites, restored in teardown. */
    val startMode = AtomicReference<GameType?>(null)
    val startFlying = AtomicReference<Boolean?>(null)
    val startXRot = AtomicReference<Float?>(null)

    /**
     * Teardown, not tidiness: every spec in this run shares one client. A step that failed after
     * putting the client into local spectator would leave every later spec running as a spectator —
     * with, among other things, this very outline gate stuck off.
     */
    afterTest {
        runOnClient { mc ->
            OrbitCameraController.exit()
            DockInputRouter.clearFocus()
            ComposeOverlay.enabled = false
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
            startMode.get()?.let { mc.gameMode?.setLocalMode(it) }
            startFlying.get()?.let { mc.player?.abilities?.flying = it }
            startXRot.get()?.let { mc.player?.xRot = it; mc.player?.xRotO = it }
        }
        waitClientTicks(2)
    }

    test("the block outline stays gated on the free cursor, not on the player's gamemode") {
        closeClientScreen()
        waitClientTicks(2)

        runOnClient { mc ->
            val player = mc.player ?: error("no client player")
            startMode.set(mc.gameMode?.playerMode)
            startFlying.set(player.abilities.flying)
            startXRot.set(player.xRot)

            // Look steeply down so both picks below — vanilla's crosshair cast and the cursor cast —
            // are aimed at the ground a couple of blocks away. Without this the spec would depend on
            // there happening to be a block near the horizon of whatever world the harness spawned.
            // `xRotO` too: `Minecraft#pick` interpolates rotation by the partial tick, so leaving the
            // previous pitch behind would aim the very next frame's pick somewhere between the two.
            player.xRot = 80f
            player.xRotO = 80f

            DockState.reset()
            DockState.panels += Panel(
                "garnet.test.outline", "OutlineProbe", DockRegion.LEFT,
                AllIconsKeys.General.Information,
            ) { Box(Modifier.fillMaxSize()) }
            DockState.showPanel("garnet.test.outline")
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)

        withClue("the router's world-vs-region split needs a cached framebuffer size") {
            onClient { ViewportState.realWidth > 0 && ViewportState.realHeight > 0 }.shouldBeTrue()
        }
        val realW = onClient { ViewportState.realWidth }
        val realH = onClient { ViewportState.realHeight }
        val rect = onClient { ViewportState.contentRect(realW, realH) }

        // Centre of the *content rect*, not of the window: with the LEFT strip open the two are
        // hundreds of pixels apart, and it is the content rect the projection was built for.
        val centreX = rect.frameX + rect.frameWidth / 2.0
        val centreY = rect.frameY + rect.frameHeight / 2.0
        val regionX = 80.0
        val regionY = 80.0
        withClue("($centreX, $centreY) must be bare world") {
            onClient { DockState.regionAt(centreX.toInt(), centreY.toInt(), realW, realH) } shouldBe null
        }
        withClue("($regionX, $regionY) must be the visible LEFT region") {
            onClient { DockState.regionAt(regionX.toInt(), regionY.toInt(), realW, realH) } shouldBe
                DockRegion.LEFT
        }

        /**
         * `GameRenderer#shouldRenderBlockOutline()` — private, so read reflectively.
         *
         * Safe here and only here: `clientTest` runs in the dev environment, where Minecraft is on
         * the classpath under **named** mappings, so the method's source name is its runtime name.
         * A production (intermediary-remapped) jar would not answer to this string, which is fine —
         * nothing outside this source set calls it.
         */
        val gate = GameRenderer::class.java.getDeclaredMethod("shouldRenderBlockOutline")
            .apply { isAccessible = true }
        suspend fun outlineWouldRender(): Boolean = onClient { mc -> gate.invoke(mc.gameRenderer) as Boolean }

        /** True when the currently installed pick is a real block, which the gate's branch needs. */
        suspend fun pickIsBlock(): Boolean = onClient { mc -> mc.hitResult?.type == HitResult.Type.BLOCK }

        // Baseline: still a builder, cursor grabbed, no dock focus. Vanilla outlines the block under
        // the crosshair, so the gate is true before anything here has had a chance to force it.
        withClue("the crosshair must be on a block for the gate's branch to be reachable") {
            pickIsBlock().shouldBeTrue()
        }
        withClue("an ordinary builder gets vanilla's outline") {
            outlineWouldRender().shouldBeTrue()
        }

        // Now the state a pan/zoom/orbit leaves behind: the client believes it is spectating.
        runOnClient { mc -> (mc.gameMode ?: error("no client game mode")).setLocalMode(GameType.SPECTATOR) }
        waitClientTicks(2)
        withClue("setLocalMode must actually clear mayBuild, or the gate never takes its branch") {
            onClient { mc -> mc.player?.abilities?.mayBuild } shouldBe false
        }

        // 1. Spectating with the cursor still grabbed: this is plain vanilla, and vanilla suppresses
        // the outline on any block without a container menu. Forcing it here would be a behavior
        // change well outside this feature, so it must stay suppressed.
        onClient { DockInputRouter.hoveringWorld }.shouldBeFalse()
        withClue("the crosshair must still be on a block") { pickIsBlock().shouldBeTrue() }
        withClue("outside garnet mode a spectator must keep vanilla's suppressed outline") {
            outlineWouldRender().shouldBeFalse()
        }

        // 2. Garnet mode: dock focused, cursor freed and resting on the bare world. This is the
        // regression — the pick is right, and before the fix the outline was gated off anyway.
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        runOnClient { DockInputRouter.onGlfwMove(centreX, centreY) }
        waitClientTicks(4)
        onClient { DockInputRouter.hoveringWorld }.shouldBeTrue()
        withClue("the free cursor must be on a block") { pickIsBlock().shouldBeTrue() }
        withClue("the free cursor drives the pick, so its block must be outlined even in spectator") {
            outlineWouldRender().shouldBeTrue()
        }

        // 3. The same spectator, cursor moved onto the panel. `hoveringWorld` goes false, the pick
        // reverts to the crosshair, and so must the gate — the override is tied to the cursor being
        // over the world, not latched on for the rest of the session.
        runOnClient { DockInputRouter.onGlfwMove(regionX, regionY) }
        waitClientTicks(2)
        onClient { DockInputRouter.hoveringWorld }.shouldBeFalse()
        withClue("over a panel vanilla's spectator rule must apply again") {
            outlineWouldRender().shouldBeFalse()
        }
    }
})
