@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.breadmoirai.garnet.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.breadmoirai.garnet.camera.input.OrbitCameraController
import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.compose.ComposeSurface
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
import net.minecraft.world.phys.Vec3
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.lwjgl.glfw.GLFW
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * The world viewport's modelling-viewport gestures, driven through the **real**
 * [DockInputRouter] entry points the GLFW mixins call — no mocks, no direct
 * [OrbitCameraController] pokes except where the test needs a clean baseline.
 *
 * One merged story (the convention `DockInputSpec` uses): a single probe panel is mounted once in
 * the LEFT region and the numbered steps below run in sequence against it, so every step shares the
 * same real dock geometry rather than re-deriving one.
 *
 * Two things about this spec are load-bearing and easy to get wrong:
 *
 * - **Step 1 is not ceremony.** Every world-vs-region decision in [DockInputRouter] is gated on
 *   `geometryKnown` (`ViewportState.realWidth/realHeight > 0`) and *skips itself* when the cached
 *   framebuffer size is unknown, rather than guessing the layout. Without step 1 — and without its
 *   `regionAt` sanity checks on the two coordinates the rest of the spec uses — every "the world
 *   press was swallowed" assertion below would pass on a client that simply never routed anything.
 * - **The arm timeout is real.** `OrbitCameraController` gives up and exits itself after
 *   `ARM_TIMEOUT_TICKS` (100) client ticks spent armed but not yet spectator, so an assertion on
 *   `active` placed after a long tick wait is a race against that timer, not a pin on the router.
 *   Every `active` assertion here is made promptly after the gesture that caused it (a handful of
 *   ticks at most), and step 7 samples the not-yet-spectator invariant in a bounded loop that ends
 *   well inside the 100-tick budget.
 *
 * Camera state itself is deliberately private to the controller, so the observable used for "the
 * drag ended" is the router's own forwarding behaviour: while a world drag owns the pointer,
 * `onGlfwMove` feeds the camera and **nothing** reaches Compose regardless of where the cursor is;
 * once the drag ends, moves route to the scene again. That is a stronger, and far more
 * deterministic, signal than waiting on the server's spectator round trip.
 */
class OrbitCameraSpec : ClientSpec({

    /** The player's pose before this spec touched anything, restored in teardown. */
    val startPos = AtomicReference<Vec3?>(null)

    /**
     * Teardown, not tidiness: every spec in this run shares one client. A step that fails
     * mid-gesture would otherwise leave the client armed — and, once the server answers, the player
     * spectating with `applyTick` rewriting its position every tick — which cascades into every
     * later spec's baseline.
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
        // Leaving camera mode is a server round trip; wait for the gamemode to actually come back
        // before restoring the pose, otherwise a still-spectating client immediately overwrites it.
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline &&
            onClient { mc -> mc.player?.isSpectator ?: false }
        ) {
            Thread.sleep(50)
        }
        startPos.get()?.let { pos ->
            runOnClient { mc -> mc.player?.setPos(pos.x, pos.y, pos.z) }
        }
        waitClientTicks(2)
    }

    test("world gestures drive the orbit camera while dock-region gestures still reach Compose") {
        closeClientScreen()
        waitClientTicks(2)

        val presses = AtomicInteger(0)
        val moves = AtomicInteger(0)
        val scrolls = AtomicInteger(0)

        runOnClient { mc ->
            DockState.reset()
            DockState.panels += Panel(
                "garnet.test.orbit", "OrbitProbe", DockRegion.LEFT,
                AllIconsKeys.General.Information,
            ) {
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                when (awaitPointerEvent().type) {
                                    PointerEventType.Press -> presses.incrementAndGet()
                                    // Enter/Exit count as position events too: a move that
                                    // crosses into the probe from the bare world is delivered as
                                    // Enter, not Move, so counting only Move would make step 5's
                                    // "moves reach the scene again" check depend on where the
                                    // cursor happened to be beforehand.
                                    PointerEventType.Move,
                                    PointerEventType.Enter,
                                    PointerEventType.Exit,
                                    -> moves.incrementAndGet()
                                    PointerEventType.Scroll -> scrolls.incrementAndGet()
                                    else -> Unit
                                }
                            }
                        }
                    },
                )
            }
            DockState.showPanel("garnet.test.orbit")
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
            startPos.set(mc.player?.position())
        }
        waitClientTicks(12)
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(4)

        // 1. the cached framebuffer size must be real, and the two coordinates every later step
        // relies on must actually be what this spec claims they are. DockInputRouter skips the
        // world/region split entirely when the geometry is unknown, so without this the whole spec
        // passes vacuously.
        withClue("the router's world-vs-region split needs a cached framebuffer size") {
            onClient { ViewportState.realWidth > 0 && ViewportState.realHeight > 0 }.shouldBeTrue()
        }
        val realW = onClient { ViewportState.realWidth }
        val realH = onClient { ViewportState.realHeight }
        val worldX = realW * 0.6
        val worldY = realH * 0.5
        val regionX = 80.0
        val regionY = 80.0
        withClue("($worldX, $worldY) must be bare world for the world steps to mean anything") {
            onClient { DockState.regionAt(worldX.toInt(), worldY.toInt(), realW, realH) } shouldBe null
        }
        withClue("($regionX, $regionY) must be the visible LEFT region") {
            onClient { DockState.regionAt(regionX.toInt(), regionY.toInt(), realW, realH) } shouldBe
                DockRegion.LEFT
        }

        // Clean baseline: nothing armed before the first gesture, so step 2's `active` is caused by
        // that gesture and not left over from another spec.
        runOnClient { OrbitCameraController.exit() }
        onClient { OrbitCameraController.active }.shouldBeFalse()

        // 2. a left-drag over the bare world orbits and does NOT drop dock focus.
        val pressesBefore = presses.get()
        runOnClient {
            DockInputRouter.onGlfwMove(worldX, worldY)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            DockInputRouter.onGlfwMove(worldX + 40.0, worldY + 24.0)
        }
        waitClientTicks(2)
        withClue("step 2: a world press must not drop dock focus (that gesture is G/ESC's job now)") {
            onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        }
        withClue("step 2: the first world drag arms camera mode lazily") {
            onClient { OrbitCameraController.active }.shouldBeTrue()
        }
        withClue("step 2: the world press is swallowed and must never reach Compose") {
            presses.get() shouldBe pressesBefore
        }
        // ...and while the drag owns the pointer, moves feed the camera wherever the cursor is —
        // even over the dock. This is the negative half of step 5's observable.
        val movesDuringDrag = moves.get()
        runOnClient { DockInputRouter.onGlfwMove(regionX, regionY) }
        waitClientTicks(2)
        withClue("step 2: an in-drag move must not leak into the scene") {
            moves.get() shouldBe movesDuringDrag
        }
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }
        waitClientTicks(2)

        // 3. the same drag over a visible dock region is an ordinary Compose gesture: the camera is
        // untouched and the probe sees the press.
        val activeBeforeRegionDrag = onClient { OrbitCameraController.active }
        val pressesBeforeRegion = presses.get()
        runOnClient {
            DockInputRouter.onGlfwMove(regionX, regionY)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            DockInputRouter.onGlfwMove(regionX + 16.0, regionY + 8.0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }
        waitClientTicks(3)
        withClue("step 3: a region drag must leave camera mode exactly as it found it") {
            onClient { OrbitCameraController.active } shouldBe activeBeforeRegionDrag
        }
        if (!ComposeSurface.disabled) {
            withClue("step 3: a region press must reach the probe panel") {
                (presses.get() > pressesBeforeRegion).shouldBeTrue()
            }
        }

        // 4. scroll splits the same way: a dolly over the world, a scroll event over the region.
        val scrollsBeforeWorld = scrolls.get()
        runOnClient {
            DockInputRouter.onGlfwMove(worldX, worldY)
            DockInputRouter.onGlfwScroll(0.0, 1.0)
        }
        waitClientTicks(3)
        withClue("step 4: a scroll over the world dollies and must not reach the probe panel") {
            scrolls.get() shouldBe scrollsBeforeWorld
        }
        runOnClient {
            DockInputRouter.onGlfwMove(regionX, regionY)
            DockInputRouter.onGlfwScroll(0.0, 1.0)
        }
        waitClientTicks(3)
        if (!ComposeSurface.disabled) {
            withClue("step 4: a scroll over the region must reach the probe panel") {
                (scrolls.get() > scrollsBeforeWorld).shouldBeTrue()
            }
        }

        // 5. press + release over the world, then a move: the drag is over (moves route to Compose
        // again) but camera mode survives — the camera keeps its pivot and angle between drags,
        // which is the whole point of orbiting a fixed subject.
        runOnClient {
            DockInputRouter.onGlfwMove(worldX, worldY)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            DockInputRouter.onGlfwMove(worldX + 20.0, worldY)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }
        waitClientTicks(2)
        withClue("step 5: releasing the drag button must not end camera mode") {
            onClient { OrbitCameraController.active }.shouldBeTrue()
        }
        val movesAfterRelease = moves.get()
        runOnClient { DockInputRouter.onGlfwMove(regionX, regionY) }
        waitClientTicks(3)
        if (!ComposeSurface.disabled) {
            withClue("step 5: once the drag ended, moves must reach the scene again") {
                (moves.get() > movesAfterRelease).shouldBeTrue()
            }
        }

        // 6. G is the way out, and it takes the camera with it: clearFocus is the single choke
        // point that must never leave the player spectating with control back in the game.
        withClue("step 6: G while captured is consumed by the router") {
            onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_G, GLFW.GLFW_PRESS, 0) }.shouldBeTrue()
        }
        onClient { DockState.focusedRegion } shouldBe null
        withClue("step 6: dropping focus must also exit camera mode") {
            onClient { OrbitCameraController.active }.shouldBeFalse()
        }

        // 7. entry arms the camera but must not move the player until spectator has actually landed
        // — a non-spectator making this size of jump is exactly what the server rubber-bands.
        //
        // The harness runs a real integrated server, which *does* answer CameraModeC2S, so spectator
        // lands a round trip later; asserting "position unchanged" after a fixed tick wait would be
        // a race against that answer in one direction and against the 100-tick arm timeout in the
        // other. Instead the gesture is driven inside a single render-thread task (no client tick
        // can interleave, so `applyTick` cannot have run) and the invariant is then sampled in a
        // bounded loop that reads `isSpectator` and the position *together*, asserting only while
        // the precondition actually holds. Both loops end far inside the arm timeout's budget.
        runOnClient { OrbitCameraController.exit() }
        val notSpectatorBy = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < notSpectatorBy &&
            onClient { mc -> mc.player?.isSpectator ?: false }
        ) {
            Thread.sleep(50)
        }
        withClue("step 7 needs the player out of spectator to start from") {
            onClient { mc -> mc.player?.isSpectator ?: true }.shouldBeFalse()
        }
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)

        val posBefore = AtomicReference<Vec3?>(null)
        val posAfterGesture = AtomicReference<Vec3?>(null)
        val armedByGesture = AtomicReference(false)
        runOnClient { mc ->
            posBefore.set(mc.player?.position())
            DockInputRouter.onGlfwMove(worldX, worldY)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            DockInputRouter.onGlfwMove(worldX + 60.0, worldY + 30.0)
            armedByGesture.set(OrbitCameraController.active)
            posAfterGesture.set(mc.player?.position())
        }
        withClue("step 7: the gesture arms camera mode immediately") {
            armedByGesture.get().shouldBeTrue()
        }
        withClue("step 7: entry itself must not move the player") {
            posAfterGesture.get() shouldBe posBefore.get()
        }
        // Sample (isSpectator, position) atomically for up to ~2s: while spectator has not landed
        // the position must still be untouched. This ends after at most ~40 ticks, well inside the
        // controller's 100-tick arm timeout, so a slow round trip can never turn into a spurious
        // "camera mode exited by itself" failure here.
        var sawSpectator = false
        var samples = 0
        while (!sawSpectator && samples < 40) {
            samples++
            val sample = onClient { mc ->
                val p = mc.player
                (p?.isSpectator ?: false) to p?.position()
            }
            if (sample.first) {
                sawSpectator = true
            } else {
                withClue("step 7: the player must not move while armed but not yet spectating") {
                    sample.second shouldBe posBefore.get()
                }
                Thread.sleep(50)
            }
        }

        // 8. and once spectator *has* landed, the camera does drive the player — the other half of
        // step 7, without which "did not move" could be satisfied by a camera that never works.
        // Guarded on the round trip actually completing rather than asserted blind: if the server
        // never grants spectator this is reported, not silently passed.
        withClue("step 8: the server should have granted spectator for camera mode") {
            sawSpectator.shouldBeTrue()
        }
        val yawBefore = onClient { mc -> mc.player?.yRot ?: 0f }
        runOnClient { DockInputRouter.onGlfwMove(worldX + 160.0, worldY + 30.0) }
        waitClientTicks(3)
        val yawAfter = onClient { mc -> mc.player?.yRot ?: 0f }
        withClue("step 8: orbiting while spectating must turn the player ($yawBefore -> $yawAfter)") {
            (abs(yawAfter - yawBefore) > 0.5f).shouldBeTrue()
        }

        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }
        waitClientTicks(2)
    }
})
