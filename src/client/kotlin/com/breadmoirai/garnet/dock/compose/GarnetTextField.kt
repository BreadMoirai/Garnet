package com.breadmoirai.garnet.dock.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.TextField

/**
 * The dock's text field: Jewel's [TextField] with the focus plumbing the dock's scene needs already
 * wired in.
 *
 * ## Why this wrapper exists
 * A Jewel/Compose input inside the dock's `ImageComposeScene` shows **no caret and no focused
 * border** unless its focus is bridged into the interaction source it reads —
 * [focusInteractionBridge] documents the failing gate in full. That bridge only works when the
 * *same* interaction source is handed to both the bridge and the widget, which is exactly the kind
 * of two-part wiring a call site quietly gets wrong (or simply never learns about). Owning the
 * interaction source here makes the fix impossible to forget: there is no correct way to use this
 * component that leaves the caret invisible.
 *
 * ## It also reports "someone is typing"
 * Focus changes are mirrored into [DockTextInputFocus], which the input router consults so a bare
 * `G` typed into a field is a letter rather than the dock-focus keybind kicking the player back to
 * the game. That is the second reason this wrapper is the only permitted text input: the report has
 * to come from *every* field or the gate is only sometimes right.
 *
 * So use this rather than Jewel's `TextField` directly anywhere in the dock —
 * `GarnetTextFieldUsageTest` pins that as a rule rather than a habit. The parameter list is
 * deliberately the subset actually in use; widen it from Jewel's signature when something needs
 * more, rather than exposing the interaction source and re-opening the hole this closes.
 */
@Composable
fun GarnetTextField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    outline: Outline = Outline.None,
    placeholder: @Composable (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    // Held here rather than read back from DockTextInputFocus so this field only ever releases what
    // it acquired, including when it is unmounted while focused (a panel switch, a closing dialog) —
    // Compose does not deliver a focus-lost event for a node that simply goes away.
    val focused = remember { BooleanRef() }
    DisposableEffect(Unit) {
        onDispose {
            if (focused.value) {
                focused.value = false
                DockTextInputFocus.release()
            }
        }
    }
    TextField(
        state = state,
        modifier = modifier
            // hasFocus, not isFocused: Jewel's TextField owns the actual focus target as a child
            // node, so this outer modifier never sees isFocused == true and the gate would never arm.
            .onFocusChanged { focusState ->
                if (focusState.hasFocus == focused.value) return@onFocusChanged
                focused.value = focusState.hasFocus
                if (focusState.hasFocus) DockTextInputFocus.acquire() else DockTextInputFocus.release()
            }
            .focusInteractionBridge(interactionSource),
        enabled = enabled,
        readOnly = readOnly,
        outline = outline,
        placeholder = placeholder,
        interactionSource = interactionSource,
    )
}

/** Plain mutable holder — this flag is never read during composition, so snapshot state would only
 *  add recompositions for a value nothing draws. */
private class BooleanRef(var value: Boolean = false)
