@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.compose.ComposeSurface
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.input.DockInputRouter
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.WindowViewportExt
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.jetbrains.jewel.ui.icons.AllIconsKeys
import org.lwjgl.glfw.GLFW
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Consolidated dock-input routing story: one probe panel, mounted once, exercised in sequence for
 * every event path [DockInputRouter] forwards from raw GLFW callbacks into the Compose scene.
 *
 * The panel carries four non-overlapping regions so that a click meant for one region can never be
 * mistaken (by Compose's hit-testing) for a click on another, and so that programmatic keyboard
 * focus is always moved *explicitly* immediately before the step that depends on it, rather than
 * relying on focus surviving unchanged across the earlier pointer/click steps (a `Modifier.clickable`
 * target is focusable by default in Compose Desktop, so leaving that implicit was the bug in the
 * first version of this merge — a click during step 2 silently stole focus from whichever probe
 * widget had it, breaking the key/char/ESC steps that ran after it):
 *
 * - a fillMaxSize `Box` that only collects raw pointer presses (step 1) and doubles as a neutral,
 *   never-consumes-anything focus target for the ESC steps (5, 6) — mirrors the original standalone
 *   ESC tests, which never gave any Compose widget focus at all.
 * - a small clickable region at a fixed offset (step 2), matching the original standalone test's
 *   absolute window-coordinate geometry exactly.
 * - a plain focusable `Box` with `onKeyEvent` (step 3) — kept as a plain `Box`, *not* merged onto the
 *   text field below, because `BasicTextField` has its own internal key handling (e.g. arrow-key
 *   cursor movement) that can consume a key event before an `onKeyEvent` modifier attached directly
 *   to it would ever see it (bubbling goes leaf-to-root). A plain `Box` preserves the original
 *   test's bubbling-path assertion exactly.
 * - a `BasicTextField` (step 4) for the typed-character path.
 */
class DockInputSpec : ClientSpec({
    test("pointer, key and char events route into a focused dock panel and back out") {
        closeClientScreen(); waitClientTicks(2)

        val secondaryPresses = mutableListOf<PointerButton?>()
        val clicks = AtomicInteger(0)
        val keySeen = AtomicReference<String?>(null)
        val typedText = AtomicReference("")
        val sceneFocusRequester = FocusRequester()
        val keyFocusRequester = FocusRequester()
        val textFocusRequester = FocusRequester()

        runOnClient { mc ->
            DockState.reset()
            DockState.panels += (
                Panel(
                    "garnet.test.dockinput",
                    "DockInputProbe",
                    DockRegion.LEFT,
                    AllIconsKeys.General.Information,
                ) {
                var value by remember { mutableStateOf(TextFieldValue("")) }
                Box(
                    Modifier.fillMaxSize()
                        .focusRequester(sceneFocusRequester)
                        .focusable()
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Press) secondaryPresses += e.button
                                }
                            }
                        },
                ) {
                    // Step 2 target: window coords (80,80) hit this box's centre — same absolute
                    // geometry the original standalone test used.
                    Box(
                        Modifier.offset(40.dp, 40.dp).size(80.dp).background(Color(0xFF2D6DA3))
                            .clickable { clicks.incrementAndGet() },
                    )
                    // Step 3 target. Deliberately non-overlapping with the click box above and the
                    // text field below.
                    Box(
                        Modifier.offset(0.dp, 140.dp).size(280.dp, 40.dp)
                            .focusRequester(keyFocusRequester)
                            .focusable()
                            .onKeyEvent { e ->
                                if (e.type == KeyEventType.KeyDown) keySeen.set(e.key.toString())
                                true
                            },
                    )
                    // Step 4 target. Non-overlapping with everything above, including the (60,200)
                    // point step 1 uses for its secondary press.
                    BasicTextField(
                        value = value,
                        onValueChange = { new -> value = new; typedText.set(new.text) },
                        modifier = Modifier.offset(0.dp, 220.dp).size(280.dp, 40.dp)
                            .focusRequester(textFocusRequester),
                    )
                }
            })
            DockState.showPanel("garnet.test.dockinput")
            DockState.setSize(DockRegion.LEFT, 300)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(12)
        runOnClient { sceneFocusRequester.requestFocus() }
        waitClientTicks(2)

        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(4)

        // 1. a secondary press reaches the scene as PointerButton.Secondary.
        // [orig: DockInputSpec "a secondary press reaches the scene as Secondary"]
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 200.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(4)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(2)
        withClue("step 1: secondary press should reach the scene collector") {
            secondaryPresses shouldBe listOf(PointerButton.Secondary)
        }
        ComposeSurface.disabled.shouldBeFalse()

        // 2. a routed primary click reaches the focused LEFT panel.
        // [orig: DockInputSpec "focused Left panel receives routed pointer clicks"]
        val before = clicks.get()
        runOnClient {
            DockInputRouter.onGlfwMove(80.0, 80.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(3)
        if (!ComposeSurface.disabled) {
            withClue("step 2: routed click should reach the clickable box") {
                (clicks.get() > before).shouldBeTrue()
            }
        }

        // 3. a non-ESC key press reaches the focused widget.
        // [orig: DockInputSpec "a non-ESC key press reaches a focused Compose widget in the scene"]
        // Focus is moved to the key-probe box explicitly, rather than relying on it having kept
        // focus since mount — step 2's click lands on a `clickable` target, which is focusable by
        // default in Compose Desktop and would otherwise have silently taken focus away.
        runOnClient { keyFocusRequester.requestFocus() }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) }
        waitClientTicks(4)
        withClue("step 3: DirectionDown should reach the focused key-probe box") {
            keySeen.get() shouldBe Key.DirectionDown.toString()
        }

        // 4. a typed char lands in the text field via onGlfwChar.
        // [orig: DockInputSpec "onGlfwChar delivers typed characters into a focused Compose text field"]
        // Regression coverage for the Task-3 review finding: onGlfwChar must land on a real
        // BasicTextField's committed text, not merely arrive at a key-event handler. Compose's own
        // typed-character recognition (TextFieldKeyInput_desktopKt.isTypedEvent) requires unwrapping a
        // real java.awt.event.KeyEvent off the Compose KeyEvent's nativeEvent field; a KeyEvent built
        // without one (as onGlfwKey builds for non-typed keys) is invisible to it. See
        // DockInputRouter.onGlfwChar's doc for how nativeEvent is populated and why.
        runOnClient { textFocusRequester.requestFocus() }
        waitClientTicks(2)
        runOnClient { "hi".forEach { c -> DockInputRouter.onGlfwChar(c.code) } }
        waitClientTicks(6)
        withClue("step 4: typed chars should land in the focused text field") {
            typedText.get() shouldBe "hi"
        }

        // 5. ESC drops dock focus, and onGlfwKey reports only ESC-press as consumed.
        // [orig: DockInputSpec "onGlfwKey still reports only ESC-press as consumed, and drops
        // nothing when uncaptured"]
        // Forwarding is additive: a non-ESC key is delivered to the scene but NOT reported consumed,
        // so the mixin's existing cancel-everything-while-captured behavior is unchanged.
        // Note: runOnClient's action is `(Minecraft) -> Unit`, so it cannot round-trip a Boolean;
        // onClient<T> is the value-returning variant (see ClientTestSupport.kt).
        //
        // Focus is returned to the neutral scene box first: both probe widgets above answer every
        // KeyDown with `true` (the key-probe box unconditionally, the text field via its own internal
        // handling), which would consume the ESC key too and prevent clearFocus() from running. The
        // original standalone ESC tests never gave any Compose widget focus at all, so this restores
        // that same "nothing consumes it" precondition.
        runOnClient { sceneFocusRequester.requestFocus() }
        waitClientTicks(2)
        onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_DOWN, GLFW.GLFW_PRESS, 0) } shouldBe false
        onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        withClue("step 5: ESC-press while captured should report consumed") {
            onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe true
        }
        withClue("step 5: ESC-press should have dropped dock focus") {
            onClient { DockState.focusedRegion } shouldBe null
        }

        // 6. an uncaptured ESC drops nothing.
        // [orig: DockInputSpec "ESC drops dock focus; other keys and uncaptured ESC are left alone"
        // and the tail of "onGlfwKey still reports..."] Uncaptured: never consumed, so vanilla ESC
        // still opens the pause menu.
        withClue("step 6: an uncaptured ESC should report not-consumed") {
            onClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS, 0) } shouldBe false
        }
        onClient { DockState.focusedRegion } shouldBe null

        // Both click-to-focus steps below hit-test against ViewportState's cached framebuffer size, so
        // a client that never populated it would make them vacuously pass (DockInputRouter skips the
        // gesture when the geometry is unknown rather than guessing). Assert it is real first.
        withClue("steps 7-8 need a cached framebuffer size to hit-test against") {
            onClient { ViewportState.realWidth > 0 && ViewportState.realHeight > 0 }.shouldBeTrue()
        }

        // 7. a press on the bare world viewport drops dock focus and delivers nothing to the panel.
        // LEFT is 300px wide with no other region visible, so x=600 is unambiguously world. The click
        // count must not move: leaving a panel is click-to-focus, never click-through (a stray world
        // click must not mine a block), and the mixin cancels the press either way.
        runOnClient { DockInputRouter.focus(DockRegion.LEFT) }
        waitClientTicks(3)
        onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        val beforeWorldClick = clicks.get()
        runOnClient {
            DockInputRouter.onGlfwMove(600.0, 100.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        }
        waitClientTicks(4)
        withClue("step 7: a world click should return focus to the game") {
            onClient { DockState.focusedRegion } shouldBe null
        }
        withClue("step 7: a world click must not reach the panel") {
            clicks.get() shouldBe beforeWorldClick
        }
        // ...and its release is swallowed too, so vanilla never sees an unmatched button-up.
        withClue("step 7: the matching release should be swallowed exactly once") {
            onClient { DockInputRouter.consumeSwallowedRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }.shouldBeTrue()
            onClient { DockInputRouter.consumeSwallowedRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }.shouldBeFalse()
        }

        // 8. uncaptured, with a vanilla Screen open, a press on the dock focuses it AND lands on the
        // widget. Without an open Screen the cursor is grabbed for play and there is no meaningful
        // pointer position, so onGlfwPressUncaptured declines — asserted first.
        withClue("step 8: with no Screen open a dock click is not a gesture") {
            onClient {
                DockInputRouter.onGlfwMove(80.0, 80.0)
                DockInputRouter.onGlfwPressUncaptured(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            }.shouldBeFalse()
        }
        onClient { DockState.focusedRegion } shouldBe null

        runOnClient { mc -> mc.gui.setScreen(object : Screen(Component.literal("garnet probe")) {}) }
        waitClientTicks(3)
        val beforeDockClick = clicks.get()
        withClue("step 8: a dock click with a Screen open should be handled by the router") {
            onClient {
                DockInputRouter.onGlfwMove(80.0, 80.0)
                DockInputRouter.onGlfwPressUncaptured(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            }.shouldBeTrue()
        }
        waitClientTicks(3)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_LEFT) }
        waitClientTicks(3)
        withClue("step 8: the dock click should have taken focus") {
            onClient { DockState.focusedRegion } shouldBe DockRegion.LEFT
        }
        if (!ComposeSurface.disabled) {
            withClue("step 8: the dock click should also land on the clickable box") {
                (clicks.get() > beforeDockClick).shouldBeTrue()
            }
        }
        closeClientScreen()

        runOnClient { mc ->
            ComposeOverlay.enabled = false; ViewportState.active = false
            DockInputRouter.clearFocus(); DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(2)
    }
})
