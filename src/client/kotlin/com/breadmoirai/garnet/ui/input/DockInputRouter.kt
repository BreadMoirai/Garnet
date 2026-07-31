package com.breadmoirai.garnet.ui.input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import com.breadmoirai.garnet.ui.compose.ComposeSurface
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent

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
        if (!captured) return
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeSurface.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    fun onGlfwRelease(button: Int) {
        if (!captured) return
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeSurface.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    fun onGlfwScroll(dx: Double, dy: Double) {
        if (captured) ComposeSurface.sendScroll(Offset(lastX.toFloat(), lastY.toFloat()), Offset(dx.toFloat(), dy.toFloat()))
    }

    /**
     * Key policy for the dock's key mixin, called for every key while [captured].
     *
     * ESC keeps its original contract *to the mixin*: a key-**press** of ESC while captured always
     * returns `true` ("consumed") so the mixin cancels vanilla handling and the pause menu never
     * opens on top of the dock. Every other key returns `false` — the mixin cancels it anyway so
     * keystrokes cannot leak into the game, but "not consumed" keeps that behavior exactly as it was.
     *
     * What changed: ESC is now *offered to the scene first* and only drops dock focus if the scene
     * did **not** consume it. Without this an in-scene popup (a Jewel `Dropdown` menu) can never be
     * closed with ESC — focus was dropped before the key could reach it, leaving a menu painted over
     * the panel with no keyboard way out. The mixin-facing return value is unchanged in both
     * branches, so `DockInputSpec`'s ESC contract (consumed while captured, not consumed when
     * uncaptured, and focus ultimately dropped once nothing else wants the key) still holds: with no
     * popup open nothing consumes ESC and the old behavior runs verbatim.
     *
     * Additively, non-ESC keys are now *delivered* into the Compose scene, which is what makes tree
     * navigation (arrow keys, tab, etc.) work. Unmapped keys ([glfwKeyToComposeKey] returns null) are
     * dropped rather than guessed at. This is fine for non-typed keys: nothing downstream (focus
     * traversal, `onKeyEvent` handlers, scrollable-list navigation) needs the AWT-derived typed-char
     * recognition that [onGlfwChar] requires — see that function's doc for why *it* can't use this
     * same synthetic-factory path.
     *
     * Uses the Compose-desktop synthetic `KeyEvent(...)` factory, which is annotated
     * `@InternalComposeUiApi`: an internal, cross-module API with no compatibility guarantee across
     * Compose releases. If a future Compose bump breaks compilation here, that annotation is why —
     * check the factory's signature/behavior against the new version rather than assuming it's stable.
     */
    @OptIn(InternalComposeUiApi::class)
    fun onGlfwKey(key: Int, action: Int, mods: Int = 0): Boolean {
        if (!captured) return false
        if (key == GLFW.GLFW_KEY_ESCAPE && action == GLFW.GLFW_PRESS) {
            val consumedByScene = ComposeSurface.sendKey(
                KeyEvent(
                    key = Key.Escape,
                    type = KeyEventType.KeyDown,
                    codePoint = 0,
                    isCtrlPressed = GlfwMods.ctrl(mods),
                    isMetaPressed = GlfwMods.meta(mods),
                    isAltPressed = GlfwMods.alt(mods),
                    isShiftPressed = GlfwMods.shift(mods),
                ),
            )
            if (!consumedByScene) clearFocus()
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
     * A throwaway AWT `Component` to satisfy `java.awt.event.KeyEvent`'s constructor, which throws
     * `IllegalArgumentException` on a `null` source. Never added to any UI; exists only to be a
     * legal event source. Cached because a `Canvas` isn't free to construct and this fires per
     * keystroke.
     *
     * **Must stay `by lazy`, not an eager initializer.** `DockInputRouter` is an `object`, and
     * `KeyboardHandlerMixin` reads [captured] on *every* keystroke of ordinary, uncaptured play —
     * which class-initializes this object. An eager `Canvas()` would therefore drag
     * `java.awt.Component`'s static init (`Toolkit.loadLibraries()`) and `AppContext.getAppContext()`
     * into normal gameplay: precisely the AWT-init surface behind the Windows non-daemon
     * "AWT-Windows" thread hang documented on [onGlfwChar], and the one escape from the dock's
     * OFF-by-default invariant (uncaptured input must be byte-for-byte vanilla). `by lazy` defers it
     * to the first *typed character while the dock has captured input*, i.e. never during plain play.
     */
    private val awtEventSource by lazy { Canvas() }

    /**
     * Printable text from the GLFW character callback. Control keys arrive via [onGlfwKey]; actual
     * typed characters only exist here, so a Compose text field needs both paths wired to be usable.
     *
     * Reuses [onGlfwKey]'s synthetic `KeyEvent(...)` factory (same `@InternalComposeUiApi` opt-in),
     * but — unlike that function — also passes `nativeEvent`. Compose desktop's text-input pipeline
     * (`TextFieldKeyInput_desktopKt.isTypedEvent`, via `AwtEvents_desktopKt.getAwtEventOrNull`)
     * recognizes a "typed character" *only* by unwrapping a real `java.awt.event.KeyEvent` off that
     * `nativeEvent` field and reading `getKeyChar()`; a `KeyEvent` built with `nativeEvent = null`
     * (`onGlfwKey`'s case — it never needs this) reaches a widget's `Modifier.onKeyEvent` handler fine
     * but a `BasicTextField`'s internal typed-text handling silently ignores it, since
     * `getAwtEventOrNull` returns `null` and `isTypedEvent` short-circuits to `false`. So this
     * constructs a real AWT `KEY_TYPED` event purely to serve as that `nativeEvent` payload.
     *
     * Deliberately does **not** call `KeyEvent_desktopKt.toComposeEvent(awtEvent)` (the documented
     * AWT-conversion fallback) to get there: that function's modifier computation
     * (`getLockingKeyStateSafe`) calls `Toolkit.getDefaultToolkit()`, which on Windows lazily spins up
     * a real, non-daemon native "AWT-Windows" message-pump thread the very first time any code in the
     * JVM touches `Toolkit`. That thread does not reliably dispose itself once Minecraft has otherwise
     * finished shutting down, which hung the whole client process after test completion during manual
     * verification of this function (confirmed via `Get-Process -Id <pid>` reporting `Responding:
     * False` with the process still resident many minutes after the last log line, in a dev + gametest
     * environment where nothing else in this codebase ever touches `java.awt.Toolkit`). The synthetic
     * factory takes modifiers as plain booleans (computed by [GlfwMods], not by inspecting AWT/Toolkit
     * lock-key state) and never calls `Toolkit`, so building the AWT event only to carry it as
     * `nativeEvent` — never handing it to `toComposeEvent` — avoids that hazard entirely while still
     * satisfying `isTypedEvent`'s `instanceof java.awt.event.KeyEvent` check.
     *
     * `KEY_TYPED` events require `keyCode == VK_UNDEFINED` (AWT contract); `keyChar` is a UTF-16
     * `Char`, so a `codePoint` outside the BMP (a surrogate pair) truncates to its low 16 bits here —
     * out of scope for GLFW's char callback in practice (English/Latin dock UI text), but a real
     * limitation if this is ever asked to carry astral-plane characters.
     */
    @OptIn(InternalComposeUiApi::class)
    fun onGlfwChar(codePoint: Int) {
        if (!captured) return
        val awtEvent = AwtKeyEvent(
            awtEventSource,
            AwtKeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            AwtKeyEvent.VK_UNDEFINED,
            codePoint.toChar(),
        )
        ComposeSurface.sendKey(
            KeyEvent(
                key = Key.Unknown,
                type = KeyEventType.KeyDown,
                codePoint = codePoint,
                nativeEvent = awtEvent,
            ),
        )
    }
}

/**
 * GLFW mouse-button index → Compose [PointerButton]. Returns null for buttons Compose has no
 * concept of (GLFW exposes 8), so callers drop them rather than mislabelling them as Primary.
 */
fun glfwMouseButtonToPointerButton(button: Int): PointerButton? = when (button) {
    GLFW.GLFW_MOUSE_BUTTON_LEFT -> PointerButton.Primary
    GLFW.GLFW_MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
    GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
    else -> null
}
