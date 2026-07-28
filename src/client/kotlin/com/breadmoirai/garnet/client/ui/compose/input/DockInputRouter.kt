package com.breadmoirai.garnet.client.ui.compose.input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW

/**
 * Bridges raw GLFW input (forwarded by MouseHandlerMixin/KeyboardHandlerMixin) into the full-window
 * ComposeScene, but only while a panel is [captured]. On focus we release the cursor so the OS
 * pointer is free over the panel; on unfocus we re-grab (with setIgnoreFirstMove to avoid a camera
 * jump, mirroring CursorFocusToggle). Window coords == scene coords (the scene is full-window).
 */
object DockInputRouter {

    @Volatile private var lastX = 0.0
    @Volatile private var lastY = 0.0

    /** True while the dock is eating input; the mixins consult this to cancel vanilla handling. */
    val captured: Boolean get() = DockState.focusedRegion != null

    fun focus(region: DockRegion) {
        if (DockState.focusedRegion == region) return
        DockState.focusedRegion = region
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() == null) mc.mouseHandler.releaseMouse()
    }

    fun clearFocus() {
        if (DockState.focusedRegion == null) return
        DockState.focusedRegion = null
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() == null) {
            mc.mouseHandler.setIgnoreFirstMove()
            mc.mouseHandler.grabMouse()
        }
    }

    fun onGlfwMove(x: Double, y: Double) {
        lastX = x; lastY = y
        if (captured) ComposeSurface.sendPointerMove(Offset(x.toFloat(), y.toFloat()))
    }

    fun onGlfwPress(button: Int) {
        if (captured) ComposeSurface.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()))
    }

    fun onGlfwRelease(button: Int) {
        if (captured) ComposeSurface.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()))
    }

    fun onGlfwScroll(dx: Double, dy: Double) {
        if (captured) ComposeSurface.sendScroll(Offset(lastX.toFloat(), lastY.toFloat()), Offset(dx.toFloat(), dy.toFloat()))
    }

    /**
     * Key policy for the dock's key mixin, called for every key while [captured].
     *
     * ESC keeps its original contract: a plain key-**press** of ESC drops focus and returns `true`
     * ("consumed") so the mixin cancels vanilla handling and the pause menu never opens on top of a
     * dropped dock focus. Every other key returns `false` — the mixin cancels it anyway so keystrokes
     * cannot leak into the game, but "not consumed" keeps that behavior exactly as it was.
     *
     * Additively, non-ESC keys are now *delivered* into the Compose scene, which is what makes tree
     * navigation and text fields work at all. Unmapped keys ([glfwKeyToComposeKey] returns null) are
     * dropped rather than guessed at.
     */
    @OptIn(InternalComposeUiApi::class)
    fun onGlfwKey(key: Int, action: Int, mods: Int = 0): Boolean {
        if (!captured) return false
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            clearFocus()
            return true
        }
        val composeKey = glfwKeyToComposeKey(key) ?: return false
        val type = when (action) {
            GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT -> KeyEventType.KeyDown
            GLFW.GLFW_RELEASE -> KeyEventType.KeyUp
            else -> return false
        }
        ComposeSurface.sendKey(
            KeyEvent(
                key = composeKey,
                type = type,
                codePoint = 0,
                isCtrlPressed = GlfwMods.ctrl(mods),
                isMetaPressed = GlfwMods.meta(mods),
                isAltPressed = GlfwMods.alt(mods),
                isShiftPressed = GlfwMods.shift(mods),
            ),
        )
        return false
    }

    /**
     * Printable text from the GLFW character callback. Control keys arrive via [onGlfwKey]; actual
     * typed characters only exist here, so a Compose text field needs both paths wired to be usable.
     */
    @OptIn(InternalComposeUiApi::class)
    fun onGlfwChar(codePoint: Int) {
        if (!captured) return
        ComposeSurface.sendKey(
            KeyEvent(key = Key.Unknown, type = KeyEventType.KeyDown, codePoint = codePoint),
        )
    }
}
