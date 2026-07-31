package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import net.minecraft.client.MouseHandler

/**
 * Regression for the cursor-mapping bug: with the viewport shrink active (a dock region
 * reserving an edge strip), a vanilla `Screen` (e.g. the pause / Game Menu) renders into the
 * shrunk content sub-rect, which is offset by the reserved insets `(frameX, frameY)`. But
 * `MouseHandler.getScaledXPos/getScaledYPos` map the raw cursor to GUI coords as
 * `raw * guiScaledWidth / screenWidth` — using the shrunk `screenWidth` but **never subtracting
 * the content-rect origin offset**. The result: reported GUI coordinates are shifted by
 * `frameX/scale` (and `frameY/scale`), so a button's hitbox sits at its *un-offset* on-screen
 * position and the user has to hover to the left of where the button is actually drawn to hit it.
 *
 * These are the exact instance-overloads MC uses for absolute screen coordinates
 * (`mouseMoved`/`mouseClicked`/`mouseScrolled`); the static delta overload used for
 * drag/camera-turn is intentionally left alone. The fix subtracts the content offset from the
 * stored raw cursor before scaling.
 */
class ViewportCursorMappingSpec : ClientSpec({

    test("scaled cursor position subtracts the dock content-rect offset while the viewport is shrunk") {
        closeClientScreen()
        waitClientTicks(2)

        // Arrange: a visible LEFT region reserves a left inset, so the shrunk content rect is
        // pushed right by frameX. (top is always 0 in the current dock model, so frameY stays 0.)
        runOnClient { mc ->
            DockState.reset()
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(4)

        val xposField = MouseHandler::class.java.getDeclaredField("xpos").apply { isAccessible = true }
        val yposField = MouseHandler::class.java.getDeclaredField("ypos").apply { isAccessible = true }

        runOnClient { mc ->
            val window = mc.window
            val rect = ViewportState.contentRect(ViewportState.realWidth, ViewportState.realHeight)
            val frameX = rect.frameX
            val frameY = rect.frameY

            // Guard: the scenario is only meaningful if the content is actually offset.
            frameX shouldBeGreaterThan 0

            // Place the raw OS cursor at a known point *inside* the content sub-rect.
            val contentX = 137.0
            val contentY = 91.0
            val rawX = frameX + contentX
            val rawY = frameY + contentY
            xposField.setDouble(mc.mouseHandler, rawX)
            yposField.setDouble(mc.mouseHandler, rawY)

            // The GUI coordinate MC hands to the focused screen must be the content-local
            // position scaled — i.e. computed from (raw - frameOffset), not the raw cursor.
            val expectedX = MouseHandler.getScaledXPos(window, rawX - frameX)
            val expectedY = MouseHandler.getScaledYPos(window, rawY - frameY)

            mc.mouseHandler.getScaledXPos(window) shouldBe expectedX
            mc.mouseHandler.getScaledYPos(window) shouldBe expectedY
        }

        // Restore vanilla state for later specs.
        runOnClient { mc ->
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(4)
    }
})
