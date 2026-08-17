package com.breadmoirai.garnet.dock.shell

import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.input.glfwMouseButtonToPointerButton
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.syncDockViewport
import androidx.compose.ui.input.pointer.PointerButton
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.lwjgl.glfw.GLFW

/**
 * The two pieces of dock input handling that are pure lookups over state: the GLFW→Compose button
 * table, and the rule deciding when the dock takes over the viewport. Routing an actual event into
 * a live ComposeScene is `DockInputSpec` in `src/clientTest`.
 *
 * Focus is asserted here by writing `DockState.focusedRegion` directly rather than through
 * `DockInputRouter.focus`/`clearFocus` — those call `Minecraft.getInstance()` to release/grab the
 * mouse, which NPEs outside a live client. `DockState.focusedRegion` is a plain settable var, so
 * this drives the same `anyActive()` input `syncDockViewport` reads without needing a window.
 */
class DockViewportSyncTest : FunSpec({

    afterTest {
        DockState.focusedRegion = null
        DockState.reset()
        ViewportState.active = false
        ComposeOverlay.enabled = false
    }

    test("GLFW mouse buttons map to Compose pointer buttons") {
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_LEFT) shouldBe PointerButton.Primary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT) shouldBe PointerButton.Secondary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) shouldBe PointerButton.Tertiary
        glfwMouseButtonToPointerButton(7) shouldBe null
    }

    test("syncDockViewport derives active/enabled from DockState, no GLFW involved") {
        DockState.reset()
        ViewportState.active = false
        ComposeOverlay.enabled = false

        // Nothing visible/focused: vanilla stays vanilla.
        syncDockViewport()
        ViewportState.active.shouldBeFalse()
        ComposeOverlay.enabled.shouldBeFalse()

        // LEFT becomes visible: both flags flip on.
        DockState.panels += Panel(
            "probe.LEFT", "LEFT", DockRegion.LEFT, AllIconsKeys.General.Information,
        ) {}
        DockState.showPanel("probe.LEFT")
        syncDockViewport()
        ViewportState.active.shouldBeTrue()
        ComposeOverlay.enabled.shouldBeTrue()

        // LEFT hidden again: both flags revert to vanilla.
        DockState.closeRegion(DockRegion.LEFT)
        syncDockViewport()
        ViewportState.active.shouldBeFalse()
        ComposeOverlay.enabled.shouldBeFalse()

        // Focus alone (no visible region) also counts as "something to show".
        DockState.reset()
        DockState.focusedRegion = DockRegion.LEFT
        syncDockViewport()
        ViewportState.active.shouldBeTrue()
        ComposeOverlay.enabled.shouldBeTrue()
    }
})
