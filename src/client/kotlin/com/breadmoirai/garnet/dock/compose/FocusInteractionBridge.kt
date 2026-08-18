package com.breadmoirai.garnet.dock.compose

import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged

/**
 * Feeds this node's focus state into [interactionSource] as [FocusInteraction] events.
 *
 * ## Why this exists (do not remove)
 * Inside the dock's `ImageComposeScene`, `FocusableNode` — the node `BasicTextField` (and therefore
 * every Jewel input) delegates its focus tracking to — never emits its `FocusInteraction.Focus`.
 * Focus itself works: the field's `FocusTargetNode` reaches `Active`, key events route to it, and
 * `Modifier.onFocusChanged` fires. What does not happen is `FocusableNode.onFocusStateChange`, the
 * focus-target *callback* path that turns a focus change into an interaction. With no interaction,
 * `BasicTextField`'s own `collectIsFocusedAsState()` stays false, so it never recomposes as focused
 * and never updates `TextFieldCoreModifierNode.isFocused`. That node's `showCursor` is
 * `writeable && (isFocused || isDragHovered) && cursorBrush.isSpecified`, so the caret is never
 * drawn and the cursor blink animation is never even started — a focused field with a live,
 * typeable caret position and no visible caret. Jewel's focused border is lost the same way, since
 * it too reads the interaction source.
 *
 * Bridging from `onFocusChanged` — the path that *does* work — restores both.
 *
 * The emit deliberately lives in a [LaunchedEffect] rather than inside the `onFocusChanged`
 * callback: `MutableInteractionSource` is a replay-less `SharedFlow`, and `onFocusChanged` fires
 * during `onEndApplyChanges`, which can precede the moment `collectIsFocusedAsState`'s own
 * `LaunchedEffect` subscribes. An emission made there is dropped on the floor with no subscriber.
 * Keying the effect on the focus flag defers the emit to the next dispatch, by which time the
 * collector is attached.
 *
 * Pass the same [interactionSource] to the widget itself, otherwise it is listening to a different
 * flow than the one being fed.
 */
@Composable
fun Modifier.focusInteractionBridge(interactionSource: MutableInteractionSource): Modifier {
    var focused by remember { mutableStateOf(false) }
    val emitted = remember { mutableStateOf<FocusInteraction.Focus?>(null) }
    LaunchedEffect(interactionSource, focused) {
        if (focused) {
            val focus = FocusInteraction.Focus()
            emitted.value = focus
            interactionSource.emit(focus)
        } else {
            // Unfocus carries the Focus it ends, so a collector can pair them up; nothing to send
            // if this node never reported focus in the first place (the initial false).
            emitted.value?.let { interactionSource.emit(FocusInteraction.Unfocus(it)) }
            emitted.value = null
        }
    }
    return onFocusChanged { focused = it.isFocused }
}
