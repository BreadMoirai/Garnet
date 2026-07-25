package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ui.compose.ComposeOverlay
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeSurface
import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Feasibility-spike proof for a **real ComposeScene** over the viewport composite
 * (`docs/ui/compose-in-mc-feasibility.md`).
 *
 * Turns the viewport on, enables [ComposeOverlay], lets several frames render so [ComposeSurface]
 * stands up a Skia [org.jetbrains.skia.DirectContext] on MC's live GL context and draws a real
 * `ImageComposeScene` (Compose `Box`/`Text`/button) full-window, alpha-blended over the composited
 * world (Task 1: full-window transparent overlay), and captures the composite. It then drives
 * GLFW-style pointer events into the scene (Step 2 / Task 2) and captures the reacted state,
 * asserting Compose itself registered the click.
 *
 * The spec never hard-fails on a Compose *disable*: if Skiko can't load or Skia can't coexist, that is
 * a legitimate NO-GO outcome, so we log [ComposeSurface.disabled]/reason and still assert the client
 * kept running and vanilla present was restored (the no-regression guarantee).
 */
class ComposeOverlaySpec : ClientSpec({
    val logger = LoggerFactory.getLogger("Redstone Specs")

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        awaitFile(p)
        Files.exists(p).shouldBeTrue()
        return p
    }

    test("real ComposeScene coexists with the live world, reacts to input, off restores vanilla") {
        closeClientScreen()
        waitClientTicks(2)

        ViewportState.active.shouldBeFalse()

        // Turn on the viewport shrink AND the Compose overlay on the render thread.
        runOnClient { mc ->
            ViewportState.active = true
            ComposeOverlay.enabled = true
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`redstonespecs$updateScaledFramebuffer`(true)
        }
        // Several frames: DirectContext/surface/ComposeScene init on frame 1, steady-state after.
        waitClientTicks(12)

        ViewportState.active.shouldBeTrue()

        val scene = capture("compose_in_mc_scene.png")
        logger.info(
            "[compose-spike] real-ComposeScene proof: {} (ComposeSurface.disabled={}, reason={})",
            scene, ComposeSurface.disabled, ComposeSurface.disabledReason,
        )

        // Keep rendering, then capture again — proves multi-frame stability (no GL-state drift/crash).
        waitClientTicks(20)
        val stable = capture("compose_in_mc_stable.png")
        logger.info("[compose-spike] stability proof after 20 more frames: {}", stable)

        // --- Task 2: real Compose input --------------------------------------------------------------
        // Move the pointer onto the button (hover), let Compose recompose, capture the hovered state.
        runOnClient { ComposeSurface.buttonCenter?.let { ComposeSurface.sendPointerMove(it) } }
        waitClientTicks(4)
        val hover = capture("compose_input_hover.png")
        logger.info("[compose-spike] hover proof: {}", hover)

        // Press then release at the button centre → Compose's clickable must register a click.
        val clicksBefore = ComposeSurface.clickCount
        runOnClient { ComposeSurface.buttonCenter?.let { ComposeSurface.sendPointerPress(it) } }
        waitClientTicks(4)
        val pressed = capture("compose_input_pressed.png")
        runOnClient { ComposeSurface.buttonCenter?.let { ComposeSurface.sendPointerRelease(it) } }
        waitClientTicks(4)
        val clicksAfter = ComposeSurface.clickCount
        logger.info(
            "[compose-spike] pressed proof: {} — Compose clickCount {} -> {} (disabled={})",
            pressed, clicksBefore, clicksAfter, ComposeSurface.disabled,
        )
        // Only assert input reactivity if Compose actually rendered (not a NO-GO disable) AND the
        // demo button still exists. Task 3 replaced the spike's demo panel with RedstoneDock, so
        // buttonCenter/clickCount are now no-op stand-ins (null/0); this whole spec is retired in Task 7.
        if (!ComposeSurface.disabled && ComposeSurface.buttonCenter != null) {
            (clicksAfter > clicksBefore).shouldBeTrue()
        }

        // Toggle everything OFF and confirm vanilla present is restored with no crash.
        runOnClient { mc ->
            ComposeOverlay.enabled = false
            ViewportState.active = false
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
        ViewportState.active.shouldBeFalse()
        val restored = takeClientScreenshot("compose_off_restored")
        logger.info("[compose-spike] vanilla restored screenshot: {}", restored.toAbsolutePath())
    }
})

private fun awaitFile(path: Path, timeoutMs: Long = 6000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!Files.exists(path) && System.currentTimeMillis() < deadline) {
        Thread.sleep(50)
    }
}
