package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import java.nio.file.Files
import java.nio.file.Path

class DockRenderSpec : ClientSpec({

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue()
        return p
    }

    test("dock renders Left + Bottom regions with the world composited in the center") {
        closeClientScreen()
        waitClientTicks(2)

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("demo.left", "Left") { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF1B2433))) {
                    BasicText("LEFT PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            })
            DockState.bottomPanels.add(Panel("demo.bottom", "Bottom") { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF243044))) {
                    BasicText("BOTTOM PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setVisible(DockRegion.BOTTOM, true)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)

        val shot = capture("dock_left_bottom.png")
        // Controller verifies: LEFT strip + BOTTOM strip painted by Compose, world composited in
        // the inset center, transparent gaps show the world. (disabled={} logged for NO-GO safety.)

        runOnClient { mc ->
            ComposeOverlay.enabled = false
            ViewportState.active = false
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
        ViewportState.active.shouldBeFalse()
    }
})
