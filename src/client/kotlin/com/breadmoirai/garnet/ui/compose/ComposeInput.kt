package com.breadmoirai.garnet.ui.compose

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton

// --- Input (Task 4): forward GLFW-derived pointer/scroll/key events into the live dock scene ----
// Scene-local coords == window-local screen coords (Compose draws top-down; the BOTTOM_LEFT surface
// + flipV blit presents it upright, so no Y flip is needed for hit-testing).

/**
 * Forwards GLFW-derived pointer/scroll/key events into [ComposeSurface]'s live dock scene, guarded
 * so a Compose-side exception disables the surface (see [ComposeSurface.kill]) instead of crashing
 * the client. Split out of [ComposeSurface] alongside the GL-state bookkeeping in [GlStateStash];
 * see that object's doc for why [ComposeSurface] is split this way.
 */
internal object ComposeInput {

    fun sendPointerMove(pos: Offset) = guardedInput { ComposeSurface.currentHost()?.pointerMove(pos) }
    fun sendPointerPress(pos: Offset, button: PointerButton? = null) =
        guardedInput { ComposeSurface.currentHost()?.pointerPress(pos, button) }

    fun sendPointerRelease(pos: Offset, button: PointerButton? = null) =
        guardedInput { ComposeSurface.currentHost()?.pointerRelease(pos, button) }
    fun sendScroll(pos: Offset, delta: Offset) = guardedInput { ComposeSurface.currentHost()?.scroll(pos, delta) }
    /**
     * Deliver a key event to the scene and report whether the scene **consumed** it. The return value
     * is what lets [com.breadmoirai.garnet.ui.input.DockInputRouter] give an open
     * in-scene popup (a Jewel `Dropdown` menu) first refusal on ESC before dropping dock focus.
     * Returns `false` when Compose is disabled or no scene exists, so callers fall back to their
     * pre-Compose behavior.
     */
    fun sendKey(event: androidx.compose.ui.input.key.KeyEvent): Boolean =
        guardedInput(false) { ComposeSurface.currentHost()?.sendKey(event) ?: false }

    private inline fun guardedInput(block: () -> Unit) {
        guardedInput(Unit) { block() }
    }

    private inline fun <T> guardedInput(fallback: T, block: () -> T): T {
        // A scene awaiting teardown must not receive input: its composition is frozen at whatever was
        // on screen when the dock was hidden, so a stale focused widget would happily consume keys
        // (including the ESC that has to drop dock focus). See [ComposeSurface.sceneStale].
        if (ComposeSurface.disabled || ComposeSurface.isSceneStale()) return fallback
        return try {
            block()
        } catch (t: Throwable) {
            ComposeSurface.kill("ComposeScene input dispatch failed", t)
            fallback
        }
    }
}
