package com.breadmoirai.garnet.dock.compose

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    TextField(
        state = state,
        modifier = modifier.focusInteractionBridge(interactionSource),
        enabled = enabled,
        readOnly = readOnly,
        outline = outline,
        placeholder = placeholder,
        interactionSource = interactionSource,
    )
}
