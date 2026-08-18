@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.breadmoirai.garnet.dock.compose

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndSelectAll
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.breadmoirai.garnet.dock.compose.ComposeSceneHost
import com.breadmoirai.garnet.dock.compose.GarnetTextField
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.TextField

/**
 * A focused text field in the dock's scene must show a caret.
 *
 * It does not on its own: `FocusableNode` never emits its `FocusInteraction.Focus` inside an
 * `ImageComposeScene`, so `BasicTextField` never learns it is focused and
 * `TextFieldCoreModifierNode.showCursor` stays false — see
 * [com.breadmoirai.garnet.dock.compose.focusInteractionBridge] for the full chain. These specs assert
 * the caret through the only signal a raster scene offers: a blinking caret is the one thing that
 * makes consecutive frames of an otherwise static field differ.
 *
 * Frames are spaced in *wall-clock* time on purpose. Compose's cursor blink is a plain
 * `delay(500)` loop, not a frame-clock animation, so rendering many frames back to back — however
 * the frame nanos are advanced — never crosses a blink boundary.
 */
class FocusInteractionBridgeTest : StringSpec({

    /** Frames of a focused text field, as raw pixel buffers, sampled across a blink cycle. */
    fun frames(wrapped: Boolean): List<ByteArray> {
        val state = TextFieldState()
        val focusRequester = FocusRequester()
        val host = ComposeSceneHost(240, 60) {
            IntUiTheme(isDark = true) {
                LaunchedEffect(Unit) {
                    state.setTextAndSelectAll("hello")
                    focusRequester.requestFocus()
                }
                val modifier = Modifier.fillMaxWidth().focusRequester(focusRequester)
                if (wrapped) GarnetTextField(state = state, modifier = modifier)
                else TextField(state = state, modifier = modifier)
            }
        }
        return try {
            (0 until 12).map {
                val image = host.render(System.nanoTime())
                val pixels = image.peekPixels()!!.buffer.bytes.copyOf()
                image.close()
                Thread.sleep(150)
                pixels
            }
        } finally {
            host.close()
        }
    }

    fun differingFrames(frames: List<ByteArray>): Int =
        frames.count { !it.contentEquals(frames.first()) }

    "a focused GarnetTextField shows a blinking caret" {
        (differingFrames(frames(wrapped = true)) > 0) shouldBe true
    }

    // The regression itself. If this ever starts failing, Compose has begun emitting the focus
    // interaction on its own in a raster scene and the wrapper is no longer load-bearing (the case
    // above would then pass either way, and stop proving anything).
    "guard — a raw Jewel TextField never paints one" {
        differingFrames(frames(wrapped = false)) shouldBe 0
    }
})
