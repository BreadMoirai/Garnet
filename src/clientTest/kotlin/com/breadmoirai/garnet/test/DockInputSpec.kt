package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.client.viewport.syncDockViewport
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.lwjgl.glfw.GLFW
import java.util.concurrent.atomic.AtomicInteger

class DockInputSpec : ClientSpec({
    test("focused Left panel receives routed pointer clicks") {
        closeClientScreen(); waitClientTicks(2)
        val clicks = AtomicInteger(0)

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("demo.left", "Left") {
                Box(Modifier.size(400.dp)) {
                    Box(Modifier.offset(40.dp, 40.dp).size(80.dp).background(Color(0xFF2D6DA3))
                        .clickable { clicks.incrementAndGet() })
                }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)

        // Focus the left region, then route a click at the button centre (window coords == 80,80).
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        val before = clicks.get()
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 80.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(3)

        if (!ComposeSurface.disabled) (clicks.get() > before).shouldBeTrue()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("ESC drops dock focus; other keys and uncaptured ESC are left alone") {
        runOnClient { DockState.reset() }
        waitClientTicks(2)

        // Uncaptured: ESC must be reported as not-consumed (vanilla untouched).
        runOnClient {
            DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS).shouldBeFalse()
        }

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        runOnClient { DockState.focusedRegion.shouldNotBeNull() }

        // A non-ESC key while captured must not drop focus and must report not-consumed.
        runOnClient {
            DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_A, GLFW.GLFW_PRESS).shouldBeFalse()
            DockState.focusedRegion.shouldNotBeNull()
        }

        // ESC-press while captured drops focus and reports consumed.
        runOnClient {
            DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS).shouldBeTrue()
            DockState.focusedRegion.shouldBeNull()
        }

        runOnClient { DockInputRouter.clearFocus(); DockState.reset() }
        waitClientTicks(2)
    }

    test("syncDockViewport derives active/enabled from DockState, no GLFW involved") {
        runOnClient {
            DockState.reset()
            ViewportState.active = false
            ComposeOverlay.enabled = false
        }
        waitClientTicks(2)

        // Nothing visible/focused: vanilla stays vanilla.
        runOnClient {
            syncDockViewport()
            ViewportState.active.shouldBeFalse()
            ComposeOverlay.enabled.shouldBeFalse()
        }

        // LEFT becomes visible: both flags flip on.
        runOnClient {
            DockState.setVisible(DockRegion.LEFT, true)
            syncDockViewport()
            ViewportState.active.shouldBeTrue()
            ComposeOverlay.enabled.shouldBeTrue()
        }

        // LEFT hidden again: both flags revert to vanilla.
        runOnClient {
            DockState.setVisible(DockRegion.LEFT, false)
            syncDockViewport()
            ViewportState.active.shouldBeFalse()
            ComposeOverlay.enabled.shouldBeFalse()
        }

        // Focus alone (no visible region) also counts as "something to show".
        runOnClient {
            DockState.reset()
            DockInputRouter.focus(DockRegion.LEFT)
            syncDockViewport()
            ViewportState.active.shouldBeTrue()
            ComposeOverlay.enabled.shouldBeTrue()
        }

        runOnClient {
            DockInputRouter.clearFocus()
            DockState.reset()
            ViewportState.active = false
            ComposeOverlay.enabled = false
        }
        waitClientTicks(2)
    }
})
