package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.ui.input.key.KeyEvent
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListKey
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.SelectionMode
import org.jetbrains.jewel.foundation.lazy.tree.KeyActions

/**
 * Wraps the tree's [KeyActions] so that **while an in-tree name field is open, the tree consumes no
 * keys at all**.
 *
 * Jewel installs a `SelectableLazyColumn`'s keybindings with `Modifier.onPreviewKeyEvent` on the
 * *tree container*. Preview events dispatch root → leaf, so the tree gets first refusal on every
 * key before the `InlineNameField` nested inside one of its rows. The tree's default bindings cover
 * exactly the keys a text field needs — Left/Right/Up/Down, Home/End, PageUp/PageDown and Ctrl+A
 * (its own "select all rows") — so it ate all of them, and the field was left with no caret
 * movement, no keyboard selection, and no select-all. Typed characters still landed, because the
 * tree has no binding for them; that combination is what made the field look like a fake text box
 * rather than a dead one.
 *
 * A nested widget cannot out-rank an ancestor's preview handler, so the fix has to come from the
 * tree side: hand `LazyTree` a [KeyActions] whose handler never reports a key as handled while
 * [editing]. The tree has no use for keyboard navigation while the user is typing a filename, and
 * Escape/Enter are still handled by the field itself.
 *
 * Only [handleOnKeyEvent] is overridden — [keybindings] and [actions] delegate, so nothing else
 * about the tree's behavior changes and normal (non-editing) navigation is byte-for-byte the
 * default.
 */
class EditAwareKeyActions(
    private val delegate: KeyActions,
    private val editing: Boolean,
) : KeyActions {

    override val keybindings get() = delegate.keybindings
    override val actions get() = delegate.actions

    override fun handleOnKeyEvent(
        event: KeyEvent,
        keys: List<SelectableLazyListKey>,
        state: SelectableLazyListState,
        selectionMode: SelectionMode,
    ): (KeyEvent) -> Boolean =
        if (editing) NEVER_HANDLED else delegate.handleOnKeyEvent(event, keys, state, selectionMode)

    private companion object {
        val NEVER_HANDLED: (KeyEvent) -> Boolean = { false }
    }
}
