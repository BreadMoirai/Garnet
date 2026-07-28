package com.breadmoirai.garnet.client.ui.compose.input

import androidx.compose.ui.input.key.Key
import org.lwjgl.glfw.GLFW

/**
 * GLFW key code -> Compose [Key]. MC hands us raw GLFW codes (via `KeyEvent#key()`); Compose's
 * desktop key pipeline wants its own [Key] constants, so the dock needs this table to deliver
 * keystrokes into the scene at all.
 *
 * Deliberately partial: it covers navigation, editing, modifiers, letters and digits — what the
 * Explorer's tree and text field actually consume. Unmapped keys return `null` and are dropped
 * rather than guessed at, because a wrong [Key] would fire the wrong action in a focused widget.
 * Printable text does NOT come through here — it arrives as a codepoint via the GLFW char callback
 * (see [DockInputRouter.onGlfwChar]); this table exists so control keys still work.
 */
fun glfwKeyToComposeKey(glfwKey: Int): Key? = KEY_TABLE[glfwKey]

/** Decodes the GLFW modifier bitfield handed to the key callback. */
object GlfwMods {
    fun shift(mods: Int) = (mods and GLFW.GLFW_MOD_SHIFT) != 0
    fun ctrl(mods: Int) = (mods and GLFW.GLFW_MOD_CONTROL) != 0
    fun alt(mods: Int) = (mods and GLFW.GLFW_MOD_ALT) != 0
    fun meta(mods: Int) = (mods and GLFW.GLFW_MOD_SUPER) != 0
}

private val KEY_TABLE: Map<Int, Key> = buildMap {
    // Navigation
    put(GLFW.GLFW_KEY_UP, Key.DirectionUp)
    put(GLFW.GLFW_KEY_DOWN, Key.DirectionDown)
    put(GLFW.GLFW_KEY_LEFT, Key.DirectionLeft)
    put(GLFW.GLFW_KEY_RIGHT, Key.DirectionRight)
    put(GLFW.GLFW_KEY_HOME, Key.MoveHome)
    put(GLFW.GLFW_KEY_END, Key.MoveEnd)
    put(GLFW.GLFW_KEY_PAGE_UP, Key.PageUp)
    put(GLFW.GLFW_KEY_PAGE_DOWN, Key.PageDown)

    // Editing / activation
    put(GLFW.GLFW_KEY_ENTER, Key.Enter)
    put(GLFW.GLFW_KEY_KP_ENTER, Key.NumPadEnter)
    put(GLFW.GLFW_KEY_TAB, Key.Tab)
    put(GLFW.GLFW_KEY_BACKSPACE, Key.Backspace)
    put(GLFW.GLFW_KEY_DELETE, Key.Delete)
    put(GLFW.GLFW_KEY_INSERT, Key.Insert)
    put(GLFW.GLFW_KEY_SPACE, Key.Spacebar)
    put(GLFW.GLFW_KEY_ESCAPE, Key.Escape)

    // Modifiers (delivered as their own key events, and widgets track them)
    put(GLFW.GLFW_KEY_LEFT_SHIFT, Key.ShiftLeft)
    put(GLFW.GLFW_KEY_RIGHT_SHIFT, Key.ShiftRight)
    put(GLFW.GLFW_KEY_LEFT_CONTROL, Key.CtrlLeft)
    put(GLFW.GLFW_KEY_RIGHT_CONTROL, Key.CtrlRight)
    put(GLFW.GLFW_KEY_LEFT_ALT, Key.AltLeft)
    put(GLFW.GLFW_KEY_RIGHT_ALT, Key.AltRight)
    put(GLFW.GLFW_KEY_LEFT_SUPER, Key.MetaLeft)
    put(GLFW.GLFW_KEY_RIGHT_SUPER, Key.MetaRight)

    // Letters: GLFW_KEY_A..GLFW_KEY_Z are contiguous and ASCII-aligned, as are Compose's Key.A..Key.Z.
    val letters = listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
    letters.forEachIndexed { i, key -> put(GLFW.GLFW_KEY_A + i, key) }

    // Digits, likewise contiguous.
    val digits = listOf(
        Key.Zero, Key.One, Key.Two, Key.Three, Key.Four,
        Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    )
    digits.forEachIndexed { i, key -> put(GLFW.GLFW_KEY_0 + i, key) }
}
