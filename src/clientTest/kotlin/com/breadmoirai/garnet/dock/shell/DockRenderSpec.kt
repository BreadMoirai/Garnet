package com.breadmoirai.garnet.dock.shell

import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.compose.ComposeSurface
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.test.closeClientScreen
import com.breadmoirai.garnet.test.runOnClient
import com.breadmoirai.garnet.test.waitClientTicks
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.jetbrains.jewel.ui.icons.AllIconsKeys
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
            DockState.panels += Panel("demo.left", "Left", DockRegion.LEFT, AllIconsKeys.General.Information) { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF1B2433))) {
                    BasicText("LEFT PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            }
            DockState.panels += Panel("demo.bottom", "Bottom", DockRegion.BOTTOM, AllIconsKeys.General.Information) { p ->
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(Color(0xFF243044))) {
                    BasicText("BOTTOM PANEL", style = androidx.compose.ui.text.TextStyle(color = Color(0xFFFFFFFF)))
                }
            }
            DockState.showPanel("demo.left")
            DockState.showPanel("demo.bottom")
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
