@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.dock.Panel
import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter
import com.breadmoirai.garnet.client.ui.compose.input.glfwMouseButtonToPointerButton
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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.lwjgl.glfw.GLFW
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DockInputSpec : ClientSpec({
    test("GLFW mouse buttons map to Compose pointer buttons") {
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_LEFT) shouldBe PointerButton.Primary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_RIGHT) shouldBe PointerButton.Secondary
        glfwMouseButtonToPointerButton(GLFW.GLFW_MOUSE_BUTTON_MIDDLE) shouldBe PointerButton.Tertiary
        glfwMouseButtonToPointerButton(7) shouldBe null
    }

    test("a secondary press reaches the scene as Secondary") {
        val seen = mutableListOf<PointerButton?>()
        val panel = Panel("garnet.test.buttonprobe", "ButtonProbe") {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.type == PointerEventType.Press) seen += e.button
                        }
                    }
                },
            )
        }
        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(panel)
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(4)
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 200.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(4)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(2)

        seen shouldBe listOf(PointerButton.Secondary)
        ComposeSurface.disabled.shouldBeFalse()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus()
            DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(2)
    }

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

    test("a non-ESC key press reaches a focused Compose widget in the scene") {
        closeClientScreen(); waitClientTicks(2)
        val seen = AtomicReference<String?>(null)
        val focusRequester = FocusRequester()

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("test.keys", "Keys") {
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) seen.set(e.key.toString())
                            true
                        },
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(8)

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) }
        waitClientTicks(4)

        seen.get() shouldBe Key.DirectionDown.toString()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("onGlfwKey still reports only ESC-press as consumed, and drops nothing when uncaptured") {
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        // Forwarding is additive: a non-ESC key is delivered to the scene but NOT reported consumed,
        // so the mixin's existing cancel-everything-while-captured behavior is unchanged.
        // Note: runOnClient's action is `(Minecraft) -> Unit`, so it cannot round-trip a Boolean;
        // onClient<T> is the value-returning variant (see ClientTestSupport.kt).
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) } shouldBe false
        onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        onClient { DockState.focusedRegion } shouldBe null
        // Uncaptured: never consumed, so vanilla ESC still opens the pause menu.
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe false
    }

    test("onGlfwChar delivers typed characters into a focused Compose text field") {
        // Regression coverage for the Task-3 review finding: onGlfwChar must land on a real
        // BasicTextField's committed text, not merely arrive at an onKeyEvent handler. A widget-level
        // onKeyEvent assertion (as in the test above) would NOT catch this -- Compose's own
        // typed-character recognition (TextFieldKeyInput_desktopKt.isTypedEvent) requires unwrapping a
        // real java.awt.event.KeyEvent off the Compose KeyEvent's nativeEvent field; a KeyEvent built
        // without one (as onGlfwKey builds for non-typed keys) is invisible to it. See
        // DockInputRouter.onGlfwChar's doc for how nativeEvent is populated and why.
        closeClientScreen(); waitClientTicks(2)
        val text = AtomicReference("")
        val focusRequester = FocusRequester()

        runOnClient { mc ->
            DockState.reset()
            DockState.leftPanels.add(Panel("test.textfield", "Text") {
                var value by remember { mutableStateOf(TextFieldValue("")) }
                BasicTextField(
                    value = value,
                    onValueChange = { new -> value = new; text.set(new.text) },
                    modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            })
            DockState.setVisible(DockRegion.LEFT, true)
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(8)

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(2)
        runOnClient {
            "hi".forEach { c -> DockInputRouter.onGlfwChar(c.code) }
        }
        waitClientTicks(6)

        text.get() shouldBe "hi"

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }
})
