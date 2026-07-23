package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.viewport.ViewportState
import com.breadmoirai.redstonespecs.client.viewport.WindowViewportExt
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Visual-proof spec for the viewport-shrink composite (spike Task 3).
 *
 * Toggles [ViewportState.active] on, drives the [WindowViewportExt] recompute so the game render
 * target shrinks, lets a few frames render through [MinecraftPresentMixin][
 * com.breadmoirai.redstonespecs.mixin.client.MinecraftPresentMixin], and captures screenshots
 * before/after. The "after" screenshot is the deliverable: the live world in a centered sub-rect
 * with solid reserved edges on the left and bottom. Inspect it under
 * `versions/26.1/run/screenshots/`.
 */
class ViewportCompositeSpec : ClientSpec({
    val logger = LoggerFactory.getLogger("Redstone Specs")

    test("viewport composite renders world into a centered sub-rect with solid edges") {
        // Make sure nothing (pause menu etc.) is covering the world before we capture.
        closeClientScreen()
        waitClientTicks(2)

        // Baseline: effect OFF — full-screen vanilla present.
        ViewportState.active.shouldBeFalse()
        val baseline = takeClientScreenshot("viewport_shrink_off")
        logger.info("Baseline (effect off) screenshot: {}", baseline.toAbsolutePath())

        // Toggle the effect on, on the render thread, and fire the window recompute so the shrink
        // takes effect immediately (mirrors what the keybind does via registerViewportToggle).
        runOnClient { mc ->
            ViewportState.active = true
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`redstonespecs$updateScaledFramebuffer`(true)
        }

        // Let several frames render so GameRenderer resizes the main target and the composite
        // mixin presents at least one composited frame.
        waitClientTicks(10)

        ViewportState.active.shouldBeTrue()

        // The normal screenshot path captures the (upstream) main render target, so it only shows
        // the shrunk world full-frame — it can NOT show our composite. Capture that separately by
        // asking the present mixin to dump the composite target itself to a stable path. This PNG
        // is the actual Task-3 proof: world in a centered sub-rect + solid reserved edges.
        // The client's working directory is versions/26.1/run/, so this lands beside the other
        // screenshots in versions/26.1/run/screenshots/.
        val compositeProof = Path.of("screenshots", "viewport_shrink_composite_proof.png")
            .toAbsolutePath()
        Files.deleteIfExists(compositeProof)
        runOnClient { ViewportState.compositeCaptureRequest = compositeProof }

        // The composite readback is async (GPU download callback); poll for the file.
        val deadline = System.currentTimeMillis() + 5000
        while (!Files.exists(compositeProof) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        Files.exists(compositeProof).shouldBeTrue()
        logger.info("Composite proof (effect on, composite target) written to: {}", compositeProof)

        // Also capture the vanilla-path screenshot (main target) for reference; it shows the shrunk
        // world at content resolution but not the composited edges.
        val composited = takeClientScreenshot("viewport_shrink_composited")
        logger.info("Composited (main-target screenshot) written to: {}", composited.toAbsolutePath())

        // Toggle back off and confirm vanilla present is restored (no crash, state clears).
        runOnClient { mc ->
            ViewportState.active = false
            val windowExt = mc.window as Any as WindowViewportExt
            windowExt.`redstonespecs$updateScaledFramebuffer`(true)
        }
        waitClientTicks(4)
        ViewportState.active.shouldBeFalse()
        val restored = takeClientScreenshot("viewport_shrink_restored")
        logger.info("Restored (effect off again) screenshot: {}", restored.toAbsolutePath())
    }
})
