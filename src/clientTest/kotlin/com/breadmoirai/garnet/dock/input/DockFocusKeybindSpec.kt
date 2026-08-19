package com.breadmoirai.garnet.dock.input

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.compose.DockTextInputFocus
import com.breadmoirai.garnet.dock.compose.GarnetTextField
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import com.breadmoirai.garnet.harness.client.FabricTestThreadPump
import com.breadmoirai.garnet.test.closeClientScreen
import com.breadmoirai.garnet.test.onClient
import com.breadmoirai.garnet.test.runOnClient
import com.breadmoirai.garnet.test.waitClientTicks
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.lwjgl.glfw.GLFW

/**
 * `G` — the one key that takes the player from playing the game to using the Garnet UI and back.
 *
 * The two halves of the toggle travel different paths, and both are driven here the way production
 * drives them:
 *
 * - **Entering** goes through the real [net.minecraft.client.KeyMapping], pressed on the Fabric test
 *   thread with `ctx.getInput().pressKey` (the same mechanism `closeClientScreen` uses for ESCAPE),
 *   so this covers `registerDockFocusKeybind`'s actual `END_CLIENT_TICK` consumption rather than
 *   just [DockInputRouter.focus] underneath it.
 * - **Leaving** cannot: `KeyboardHandlerMixin` cancels every key while the dock is captured, so
 *   vanilla never ticks the mapping. It is [DockInputRouter.onGlfwKey] that handles it, called here
 *   directly — exactly as that mixin calls it — the same way `DockInputSpec` drives its ESC steps.
 *
 * The hard assertions are on [DockState.focusedRegion] (what the input mixins read to decide whether
 * GLFW events go to Compose or to the game) and on
 * [net.minecraft.client.MouseHandler.isMouseGrabbed] (the MC-level state gating look input). A raw
 * `GLFW.glfwGetInputMode` query is deliberately *not* asserted: in this harness the OS-level cursor
 * mode does not reliably follow `isMouseGrabbed`, a harness/window-focus quirk rather than a defect.
 *
 * *Which* region G lands on is a pure function of [DockState], pinned exhaustively by
 * `DockFocusTargetTest` in `src/test`; only the two cases that also prove something client-side are
 * repeated here.
 */
class DockFocusKeybindSpec : ClientSpec({

    fun pressGThroughKeyMapping() {
        FabricTestThreadPump.runOnTestThread { ctx -> ctx.getInput().pressKey(GLFW.GLFW_KEY_G) }
        waitClientTicks(2)
    }

    /** What `KeyboardHandlerMixin` does with the key while the dock holds focus. */
    fun pressGThroughRouter(): Boolean =
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_G, GLFW.GLFW_PRESS, 0) }

    fun resetDock() {
        runOnClient { mc ->
            DockState.reset()
            DockTextInputFocus.reset()
            ViewportState.active = false
            ComposeOverlay.enabled = false
            mc.mouseHandler.grabMouse()
        }
        waitClientTicks(1)
    }

    test("G frees the cursor and focuses the dock, and G again returns to the game") {
        closeClientScreen()
        waitClientTicks(2)
        resetDock()
        onClient { DockState.focusedRegion } shouldBe null
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeTrue()

        pressGThroughKeyMapping()

        // Nothing is open, so this is the stripe-only state: LEFT owns the stripe's column.
        onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeFalse()
        // Focus alone is what puts the dock on screen — without this the freed cursor would have
        // nothing to click. It is also the case `dropStaleFocus = false` exists for: the focused
        // region has no open panel, and the default guard would have undone the keypress.
        onClient { DockState.anyActive() }.shouldBeTrue()

        // Consumed, so the game never sees the key.
        pressGThroughRouter().shouldBeTrue()

        onClient { DockState.focusedRegion } shouldBe null
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeTrue()

        resetDock()
    }

    test("G returns to the region focus was last in") {
        closeClientScreen()
        waitClientTicks(2)
        resetDock()
        runOnClient {
            DockState.panels += Panel(
                "garnet.test.focus.left", "FocusProbeLeft", DockRegion.LEFT,
                AllIconsKeys.General.Information,
            ) {}
            DockState.panels += Panel(
                "garnet.test.focus.bottom", "FocusProbeBottom", DockRegion.BOTTOM,
                AllIconsKeys.General.Information,
            ) {}
            DockState.showPanel("garnet.test.focus.left")
            DockState.showPanel("garnet.test.focus.bottom")
            DockInputRouter.focus(DockRegion.BOTTOM)
            DockInputRouter.clearFocus()
        }
        waitClientTicks(2)

        pressGThroughKeyMapping()

        // LEFT is open and comes first in region order; the remembered region still wins.
        onClient { DockState.focusedRegion } shouldBe DockRegion.BOTTOM
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeFalse()

        pressGThroughRouter()
        resetDock()
    }

    test("G stays a letter while a dock text field has focus") {
        closeClientScreen()
        waitClientTicks(2)
        resetDock()

        val fieldFocus = FocusRequester()
        runOnClient { mc ->
            DockState.panels += Panel(
                "garnet.test.focus.field", "FocusProbeField", DockRegion.LEFT,
                AllIconsKeys.General.Information,
            ) {
                val state = rememberTextFieldState("")
                // Every real panel wraps its body in IntUiTheme; a Jewel widget composed without it
                // throws "No TextStyle provided" *at scene creation*, which disables ComposeSurface
                // for the rest of the client run and takes every later spec down with it.
                IntUiTheme(isDark = true) {
                    Box(Modifier.fillMaxSize()) {
                        GarnetTextField(
                            state = state,
                            modifier = Modifier.focusRequester(remember { fieldFocus }),
                        )
                    }
                }
            }
            DockState.showPanel("garnet.test.focus.field")
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        runOnClient { fieldFocus.requestFocus() }
        waitClientTicks(4)
        onClient { DockTextInputFocus.anyFocused }.shouldBeTrue()

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)

        // Not consumed as a keybind: it is forwarded into the scene like any other key, and focus
        // stays put. Without the DockTextInputFocus gate this ejects the player to the game
        // mid-word, because a focused field does not consume the *key* event for a letter.
        pressGThroughRouter().shouldBeFalse()

        onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        onClient { mc -> mc.mouseHandler.isMouseGrabbed }.shouldBeFalse()

        resetDock()
    }
})
