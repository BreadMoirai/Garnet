package com.breadmoirai.garnet.dock.input

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import com.breadmoirai.garnet.camera.input.OrbitCameraController
import com.breadmoirai.garnet.dock.compose.ComposeInput
import com.breadmoirai.garnet.dock.compose.DockTextInputFocus
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.regionAt
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.commitDockVisibilityChange
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import java.awt.Canvas
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * Bridges raw GLFW input (forwarded by MouseHandlerMixin/KeyboardHandlerMixin) into the full-window
 * ComposeScene, but only while a panel is [captured]. On focus we release the cursor so the OS
 * pointer is free over the panel; on unfocus we re-grab (with setIgnoreFirstMove to avoid a camera
 * jump). Window coords == scene coords (the scene is full-window).
 *
 * Focus is taken by the `G` keybind ([registerDockFocusKeybind]), by Alt+1, and by clicking a dock
 * region; it is dropped by any of those plus ESC.
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

    /** Which world gesture a held button is currently driving, or null when none is. */
    private enum class WorldDrag { ORBIT, PAN }
    @Volatile private var worldDrag: WorldDrag? = null

    fun onGlfwMove(x: Double, y: Double) {
        val dx = x - lastX
        val dy = y - lastY
        lastX = x; lastY = y
        if (!captured) return
        when (worldDrag) {
            WorldDrag.ORBIT -> OrbitCameraController.orbitBy(dx, dy)
            WorldDrag.PAN -> OrbitCameraController.panBy(dx, dy)
            null -> ComposeInput.sendPointerMove(Offset(x.toFloat(), y.toFloat()))
        }
    }

    /**
     * Which dock region the cursor is currently over, or `null` for the bare world viewport —
     * **and also `null` when the answer is unknowable**, i.e. before `WindowMixin` has cached a real
     * framebuffer size. Callers must therefore not treat a `null` as "definitely the world" without
     * first checking [geometryKnown]; guessing the layout from a zero-sized window would drop dock
     * focus on the very first click after a resize/startup race.
     */
    private fun regionUnderCursor(): DockRegion? =
        DockState.regionAt(lastX.toInt(), lastY.toInt(), ViewportState.realWidth, ViewportState.realHeight)

    /** True once `WindowMixin` has cached a real framebuffer size, so [regionUnderCursor] means something. */
    private val geometryKnown: Boolean
        get() = ViewportState.realWidth > 0 && ViewportState.realHeight > 0

    /**
     * Press while a panel is focused.
     *
     * A press over the **bare world viewport** begins a camera gesture — left orbits, middle pans —
     * and never reaches Compose. It does *not* drop dock focus: with the cursor freed, the world is
     * a viewport to fly around, and `G` is the way back to playing.
     *
     * This replaces click-to-return-to-game. That gesture had to go: it and orbit want the same
     * press, and a drag that sometimes ends in "you are now back in the game holding a pickaxe" is
     * worse than a single unambiguous exit key.
     *
     * Everything else routes into the scene as before.
     */
    fun onGlfwPress(button: Int) {
        if (!captured) return
        if (geometryKnown && regionUnderCursor() == null) {
            worldDrag = when (button) {
                GLFW.GLFW_MOUSE_BUTTON_LEFT -> WorldDrag.ORBIT
                GLFW.GLFW_MOUSE_BUTTON_MIDDLE -> WorldDrag.PAN
                else -> null
            }
            return
        }
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeInput.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    fun onGlfwRelease(button: Int) {
        if (!captured) return
        if (worldDrag != null) {
            // Ends the gesture but does NOT leave camera mode: the camera keeps its pivot and
            // angle between drags, which is the whole point of orbiting a fixed subject.
            worldDrag = null
            return
        }
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return
        ComposeInput.sendPointerRelease(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
    }

    /**
     * Press while the dock is **not** captured, called from `MouseHandlerMixin`'s uncaptured branch.
     * Returns `true` when the press was handled here and the mixin should cancel vanilla.
     *
     * This is the inbound half of click-to-focus: with a vanilla [net.minecraft.client.gui.screens.Screen]
     * open the cursor is free, so a click on a dock region is a real gesture — it focuses that region
     * *and* is delivered into the scene, so the click lands on the widget it was aimed at instead of
     * costing the user a click. Without this the click reaches the `Screen` instead, and
     * `MouseHandlerViewportMixin` remaps `Screen` cursor coordinates through the content-rect offset,
     * so a click on the dock strip lands on a bogus screen coordinate rather than being ignored.
     *
     * Gated so ordinary play is untouched:
     * - `DockState.anyActive()` — nothing on screen, nothing to click. This guard is what keeps the
     *   dock's OFF-by-default invariant: with the dock closed this returns on a plain field read,
     *   allocating nothing and touching no GLFW/AWT.
     * - a `Screen` must be open. With the cursor **grabbed** for play there is no meaningful cursor
     *   position (GLFW reports an ever-accumulating raw delta, not a pointer), so that state is
     *   deliberately left alone — the keybinds remain the way in.
     * - the cursor must be over a region, not the world.
     *
     * [focus] only calls `releaseMouse()` when no `Screen` is open, so nothing here changes the
     * cursor. The matching release arrives *captured* and routes through [onGlfwRelease] normally.
     */
    fun onGlfwPressUncaptured(button: Int): Boolean {
        if (captured) return false
        if (!DockState.anyActive()) return false
        if (!geometryKnown) return false
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() == null) return false
        val region = regionUnderCursor() ?: return false
        val composeButton = glfwMouseButtonToPointerButton(button) ?: return false
        focus(region)
        ComposeInput.sendPointerPress(Offset(lastX.toFloat(), lastY.toFloat()), composeButton)
        return true
    }

    /** Scroll dollies over the bare world and scrolls the panel over a dock region. */
    fun onGlfwScroll(dx: Double, dy: Double) {
        if (!captured) return
        if (geometryKnown && regionUnderCursor() == null) {
            OrbitCameraController.dollyBy(dy)
            return
        }
        ComposeInput.sendScroll(Offset(lastX.toFloat(), lastY.toFloat()), Offset(dx.toFloat(), dy.toFloat()))
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
            val consumedByScene = ComposeInput.sendKey(
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
        // The dock-focus keybind's exit half. It cannot come through its KeyMapping: the mixin
        // cancels every key while captured, so vanilla never ticks the mapping. Modifier-free
        // presses only — Ctrl+G/Alt+G belong to whatever widget wants them — and never while a text
        // field has focus, where `g` is a letter: a focused field does *not* consume the key event
        // (typed text arrives via onGlfwChar), so it cannot decline this for us the way an open
        // dropdown declines ESC above.
        if (action == GLFW.GLFW_PRESS && mods == 0 && !DockTextInputFocus.anyFocused && isDockFocusKey(key)) {
            clearFocus()
            commitDockVisibilityChange(persist = false)
            return true
        }
        val composeKey = glfwKeyToComposeKey(key) ?: return false
        val type = when (action) {
            GLFW.GLFW_PRESS, GLFW.GLFW_REPEAT -> KeyEventType.KeyDown
            GLFW.GLFW_RELEASE -> KeyEventType.KeyUp
            else -> return false
        }
        ComposeInput.sendKey(
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
        ComposeInput.sendKey(
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
