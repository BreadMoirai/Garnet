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
 * Feasibility-spike proof for Skia/Compose over the viewport composite
 * (`docs/ui/compose-in-mc-feasibility.md`).
 *
 * Turns the viewport on, enables [ComposeOverlay], lets several frames render so [ComposeSurface]
 * stands up a Skia [org.jetbrains.skia.DirectContext] on MC's live GL context and draws into the
 * reserved-left strip, and captures the composite. The deliverable PNGs are the GO/NO-GO evidence:
 * a Skia panel coexisting with the live world, stable across frames, and clean vanilla rendering once
 * the viewport is toggled back off.
 *
 * The spec never hard-fails on a Compose *disable*: if Skiko can't load or Skia can't coexist, that is
 * a legitimate NO-GO outcome, so we log [ComposeSurface.disabled]/reason and still assert the client
 * kept running and vanilla present was restored (the no-regression guarantee).
 */
class ComposeOverlaySpec : ClientSpec({
    val logger = LoggerFactory.getLogger("Redstone Specs")

    test("skia/compose panel coexists with the live world across frames, off restores vanilla") {
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
        // Several frames: DirectContext/surface init on frame 1, steady-state after.
        waitClientTicks(12)

        ViewportState.active.shouldBeTrue()

        val step2 = Path.of("screenshots", "compose_in_mc_step2.png").toAbsolutePath()
        Files.deleteIfExists(step2)
        runOnClient { ViewportState.compositeCaptureRequest = step2 }
        awaitFile(step2)
        Files.exists(step2).shouldBeTrue()
        logger.info(
            "[compose-spike] step2 composite proof: {} (ComposeSurface.disabled={}, reason={})",
            step2, ComposeSurface.disabled, ComposeSurface.disabledReason,
        )

        // Keep rendering, then capture again — proves multi-frame stability (no GL-state drift/crash).
        waitClientTicks(20)
        val stable = Path.of("screenshots", "compose_in_mc_stable.png").toAbsolutePath()
        Files.deleteIfExists(stable)
        runOnClient { ViewportState.compositeCaptureRequest = stable }
        awaitFile(stable)
        Files.exists(stable).shouldBeTrue()
        logger.info("[compose-spike] stability proof after 20 more frames: {}", stable)

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
