@file:OptIn(
    androidx.compose.ui.InternalComposeUiApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    org.jetbrains.jewel.foundation.ExperimentalJewelApi::class,
)

package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextRange
import com.breadmoirai.garnet.dock.compose.ComposeSceneHost
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.jewel.foundation.lazy.SelectableLazyListState
import org.jetbrains.jewel.foundation.lazy.SelectionMode
import org.jetbrains.jewel.foundation.lazy.tree.DefaultTreeViewKeyActions
import org.jetbrains.jewel.foundation.lazy.tree.KeyActions
import org.jetbrains.jewel.foundation.lazy.tree.TreeState
import org.jetbrains.jewel.foundation.lazy.tree.buildTree
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField

/**
 * The Explorer's in-tree name field must behave like a real text field: caret movement, keyboard
 * selection and Ctrl+A have to reach it even though it lives inside a Jewel `LazyTree` row.
 *
 * The tree installs its keybindings with `Modifier.onPreviewKeyEvent` on the tree *container*, and
 * preview events dispatch root → leaf, so without [EditAwareKeyActions] the tree consumes all of
 * those keys first and the field can only accept typed characters. See that class for the full
 * mechanism; this spec pins both halves — the default actions swallow, the edit-aware ones don't.
 *
 * Runs headless: a raster [ComposeSceneHost] plus synthetic key events built exactly the way
 * `DockInputRouter.onGlfwKey` builds them, so no Minecraft client is needed.
 */
class InlineNameFieldKeyRoutingTest : StringSpec({

    /** Drives a name field nested in a tree row and reports the resulting selection. */
    fun selectionAfterKeys(
        keyActionsFor: (TreeState) -> KeyActions,
        keys: List<KeyEvent>,
    ): TextRange {
        val state = TextFieldState()
        val focusRequester = FocusRequester()

        @Composable
        fun NameField() {
            LaunchedEffect(Unit) {
                state.setTextAndPlaceCursorAtEnd("hello")
                focusRequester.requestFocus()
            }
            TextField(state = state, modifier = Modifier.fillMaxWidth().focusRequester(focusRequester))
        }

        val host = ComposeSceneHost(240, 120) {
            IntUiTheme(isDark = true) {
                val tree = buildTree<String> {
                    addNode("root", id = "root") { addLeaf("editing", id = "editing") }
                }
                val treeState = TreeState(SelectableLazyListState(LazyListState(), SelectionMode.Single))
                    .also { it.openNodes = setOf("root") }
                LazyTree(
                    tree = tree,
                    modifier = Modifier.fillMaxSize(),
                    treeState = treeState,
                    onElementClick = {},
                    keyActions = keyActionsFor(treeState),
                ) { element ->
                    if (element.data == "editing") Row { NameField() } else Text(element.data)
                }
            }
        }
        try {
            // Three frames: compose, lay the lazy row out, then let the focus request land.
            repeat(3) { host.render(System.nanoTime()).close() }
            for (key in keys) {
                host.sendKey(key)
                host.render(System.nanoTime()).close()
            }
            return state.selection
        } finally {
            host.close()
        }
    }

    fun keyDown(key: Key, ctrl: Boolean = false, shift: Boolean = false) =
        KeyEvent(key = key, type = KeyEventType.KeyDown, codePoint = 0, isCtrlPressed = ctrl, isShiftPressed = shift)

    val editAware = { treeState: TreeState ->
        EditAwareKeyActions(DefaultTreeViewKeyActions(treeState), editing = true)
    }

    "ctrl+A selects the whole name" {
        selectionAfterKeys(editAware, listOf(keyDown(Key.A, ctrl = true))) shouldBe TextRange(0, 5)
    }

    "Home moves the caret and shift+Right extends a selection" {
        val keys = listOf(keyDown(Key.MoveHome), keyDown(Key.DirectionRight, shift = true))
        selectionAfterKeys(editAware, keys) shouldBe TextRange(0, 1)
    }

    "arrow keys move the caret" {
        selectionAfterKeys(editAware, listOf(keyDown(Key.MoveHome), keyDown(Key.DirectionRight))) shouldBe
            TextRange(1, 1)
    }

    // The regression itself: with Jewel's stock actions the tree eats these keys, so if this ever
    // starts matching the assertions above, the wrapper has stopped being necessary (or stopped
    // being applied) and the cases above are no longer proving anything.
    "guard — the stock tree key actions swallow all of them" {
        val stock = { treeState: TreeState -> DefaultTreeViewKeyActions(treeState) }
        val keys = listOf(keyDown(Key.A, ctrl = true), keyDown(Key.MoveHome), keyDown(Key.DirectionRight, shift = true))
        selectionAfterKeys(stock, keys) shouldBe TextRange(5, 5)
    }
})
