package com.breadmoirai.redstonespecs.client.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.skia.Image

/**
 * The **real Compose** content of the Compose-in-MC spike (Step 3 / Task 2).
 *
 * Owns an [ImageComposeScene] — Compose Multiplatform's self-contained scene that composes/measures/
 * draws content into its *own* raster [org.jetbrains.skia.Surface] and hands back a snapshot [Image]
 * from [render]. [ComposeSurface] then draws that image onto the Blaze3D-owned GL FBO. Rendering the
 * Compose tree on a CPU raster surface (no GL) keeps it entirely off Minecraft's live GL context;
 * only the final one-image upload touches GL, under [ComposeSurface]'s snapshot/restore.
 *
 * ## Why [ImageComposeScene] and not a raw `CanvasLayersComposeScene`
 * In Compose 1.12 the low-level scene factory takes a `FrameRecomposer` + `PlatformContext` the caller
 * must build and drive. [ImageComposeScene] wraps all of that: it constructs the recomposer, the
 * [androidx.compose.runtime.BroadcastFrameClock], and — crucially — registers with the
 * `GlobalSnapshotManager` itself, so the `ComposeScene`↔snapshot race the `VexorMC/compose` fork had
 * to patch in a hand-rolled loop does not arise here (we keep all interaction on the render thread and
 * let [render] apply pending snapshot changes synchronously).
 *
 * ## The Button is real Compose interaction plumbing
 * The button reacts to pointer input entirely through Compose: a [MutableInteractionSource] fed by
 * [hoverable] + [clickable], read back via [collectIsHoveredAsState] / [collectIsPressedAsState].
 * A [sendPointerEvent] Move/Press/Release routed here flips those states and recomposes — nothing in
 * this file manually toggles the visual state, so a colour/label change is proof Compose consumed the
 * event. (We avoid material3's `Button` only because its artifact version diverged from the Compose
 * BOM and would drag in a mismatched skiko.)
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeScenePanel(val width: Int, val height: Int) : AutoCloseable {

    /** Number of Compose-registered clicks on the button; read by tests to prove input reached Compose. */
    @Volatile
    var clickCount: Int = 0
        private set

    // Deterministic button rect (density = 1 ⇒ dp == px) so tests can aim pointer events at its centre.
    private val btnX = 16
    private val btnY = 64
    private val btnW = 210
    private val btnH = 40

    /** Screen-space (panel-local) centre of the button, for [sendPointerEvent] targeting. */
    val buttonCenter: Offset get() = Offset((btnX + btnW / 2).toFloat(), (btnY + btnH / 2).toFloat())

    private val scene = ImageComposeScene(width, height, Density(1f), content = { Content() })

    @Composable
    private fun Content() {
        Box(Modifier.fillMaxSize().background(Color(0x00000000))) {
            BasicText(
                "Compose in MC",
                modifier = Modifier.offset(16.dp, 20.dp),
                style = TextStyle(color = Color(0xFFFFFFFF), fontSize = 20.sp),
            )

            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            val pressed by interaction.collectIsPressedAsState()

            val bg = when {
                pressed -> Color(0xFF1E88E5)
                hovered -> Color(0xFF4CC2FF)
                else -> Color(0xFF2D6DA3)
            }
            Box(
                Modifier
                    .offset(btnX.dp, btnY.dp)
                    .size(btnW.dp, btnH.dp)
                    .background(bg)
                    .hoverable(interaction)
                    .clickable(interactionSource = interaction, indication = null) { clickCount++ },
                contentAlignment = Alignment.Center,
            ) {
                val label = when {
                    pressed -> "Pressed!"
                    hovered -> "Hover • clicks=$clickCount"
                    else -> "Click me • clicks=$clickCount"
                }
                BasicText(
                    label,
                    style = TextStyle(color = Color(0xFFFFFFFF), fontSize = 15.sp, textAlign = TextAlign.Center),
                )
            }
        }
    }

    /** Compose one frame at [nanos] and return the raster snapshot to blit. Caller owns/closes the image. */
    fun render(nanos: Long): Image = scene.render(nanos)

    fun pointerMove(pos: Offset) = scene.sendPointerEvent(PointerEventType.Move, pos)
    fun pointerPress(pos: Offset) = scene.sendPointerEvent(PointerEventType.Press, pos)
    fun pointerRelease(pos: Offset) = scene.sendPointerEvent(PointerEventType.Release, pos)

    override fun close() = scene.close()
}
