package com.breadmoirai.garnet.dock.compose

/**
 * How many dock text inputs currently hold Compose focus.
 *
 * Read by `DockInputRouter.onGlfwKey` to decide whether a bare `G` means "give the game back the
 * cursor" or "the letter g". Compose cannot answer that for us: typed characters reach a text field
 * through the separate `charTyped`/`nativeEvent` path (see `DockInputRouter.onGlfwChar`), so the
 * *key* event for `G` is left unconsumed by a focused field and looks exactly like a keybind press.
 *
 * Maintained by [GarnetTextField], which is mechanically the only text input in the client
 * (`GarnetTextFieldUsageTest`) — so "every text input reports its focus" is a property of one file
 * rather than a convention every call site must remember.
 *
 * A count, not a boolean: moving focus from one field to another fires *focus gained* and *focus
 * lost* in an order Compose does not promise, and a boolean would flicker to `false` between them.
 * [release] floors at zero so an unbalanced release — a panel unmounted while a field held focus —
 * cannot drive the count negative and hide the *next* focus.
 *
 * Single-threaded in practice (focus changes and GLFW callbacks both run on the render thread);
 * `@Volatile` is belt-and-braces for the read, which happens on every keystroke.
 */
object DockTextInputFocus {

    @Volatile
    private var focusedCount = 0

    /** True while at least one dock text input has focus. */
    val anyFocused: Boolean get() = focusedCount > 0

    fun acquire() {
        focusedCount++
    }

    fun release() {
        if (focusedCount > 0) focusedCount--
    }

    /** Test hook, and the reset a torn-down dock session gets for free. */
    fun reset() {
        focusedCount = 0
    }
}
