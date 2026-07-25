package com.breadmoirai.redstonespecs.client.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Image

/**
 * Generic self-contained [ImageComposeScene] wrapper: composes [content] into its own raster surface
 * (no GL) at Density(1f) so dp == px, and hands back a snapshot [Image] each frame. [ComposeSurface]
 * uploads that image onto the Blaze3D FBO. See docs/ui/compose-in-mc-feasibility.md for why
 * ImageComposeScene (self-registers with GlobalSnapshotManager, avoids the scene<->snapshot race).
 *
 * Generalizes the retired `ComposeScenePanel` spike: the composed [content] is now a constructor
 * parameter (the dock), and pointer/scroll/key forwarders are exposed for Task 4's input routing.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ComposeSceneHost(
    val width: Int,
    val height: Int,
    content: @Composable () -> Unit,
) : AutoCloseable {

    private val scene = ImageComposeScene(width, height, Density(1f), content = content)

    /** Compose one frame at [nanos] and return the raster snapshot to blit. Caller owns/closes the image. */
    fun render(nanos: Long): Image = scene.render(nanos)

    fun pointerMove(pos: Offset) = scene.sendPointerEvent(PointerEventType.Move, pos)
    fun pointerPress(pos: Offset) = scene.sendPointerEvent(PointerEventType.Press, pos)
    fun pointerRelease(pos: Offset) = scene.sendPointerEvent(PointerEventType.Release, pos)
    fun scroll(pos: Offset, delta: Offset) = scene.sendPointerEvent(PointerEventType.Scroll, pos, scrollDelta = delta)
    fun sendKey(event: KeyEvent): Boolean = scene.sendKeyEvent(event)

    override fun close() = scene.close()
}
