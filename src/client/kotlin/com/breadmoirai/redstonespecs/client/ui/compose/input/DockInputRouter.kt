package com.breadmoirai.redstonespecs.client.ui.compose.input

import androidx.compose.ui.geometry.Offset
import com.breadmoirai.redstonespecs.client.ui.compose.ComposeSurface
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockRegion
import com.breadmoirai.redstonespecs.client.ui.compose.dock.DockState
import net.minecraft.client.Minecraft

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
        if (mc.screen == null) mc.mouseHandler.releaseMouse()
    }

    fun clearFocus() {
        if (DockState.focusedRegion == null) return
        DockState.focusedRegion = null
        val mc = Minecraft.getInstance()
        if (mc.screen == null) {
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
}
