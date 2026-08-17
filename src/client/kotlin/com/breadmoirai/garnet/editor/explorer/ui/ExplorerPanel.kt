// ExperimentalJewelApi: passing an explicit `style` to LazyTree selects its experimental overload.
@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalJewelApi::class)

package com.breadmoirai.garnet.editor.explorer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndSelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.breadmoirai.garnet.ui.compose.GarnetTextField
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.Panel
import com.breadmoirai.garnet.editor.network.LoadEditorFolderC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.explorer.data.NewNodeKind
import org.jetbrains.jewel.foundation.ExperimentalJewelApi
import org.jetbrains.jewel.foundation.lazy.tree.DefaultTreeViewKeyActions
import org.jetbrains.jewel.intui.standalone.styling.defaults
import org.jetbrains.jewel.intui.standalone.theme.IntUiTheme
import org.jetbrains.jewel.ui.Outline
import org.jetbrains.jewel.ui.component.Icon
import org.jetbrains.jewel.ui.component.LazyTree
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.styling.LazyTreeMetrics
import org.jetbrains.jewel.ui.component.styling.LazyTreeStyle
import org.jetbrains.jewel.ui.component.styling.LocalLazyTreeStyle
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/** Panel background, matching the IntelliJ dark tool-window colour. */
private val PANEL_BG = Color(0xFF1E1F22)

/** The Explorer panel, registered in DockState.panels for the LEFT region. */
fun explorerPanel(): Panel = Panel(
    "garnet.explorer", "Explorer", DockRegion.LEFT, AllIconsKeys.Toolwindows.ToolWindowProject,
) { ProjectExplorer() }

@Composable
private fun ProjectExplorer() {
    IntUiTheme(isDark = true) {
        // Vertical-only padding: the tree below is deliberately full-bleed horizontally (see
        // [flushTreeStyle]), so a uniform inset here would reintroduce the very margin it removes.
        // The toolbar carries its own start inset instead.
        Column(Modifier.fillMaxSize().background(PANEL_BG).padding(vertical = 4.dp)) {
            ExplorerToolbar()
            var edit by remember { mutableStateOf<ExplorerEdit?>(null) }
            var editError by remember { mutableStateOf<String?>(null) }
            val menu = remember { ExplorerMenuState() }
            val dialogs = remember { ExplorerDialogState() }
            val snap = ExplorerTreeSnapshot.snapshot
            if (snap == null) {
                Text("(no project loaded — Refresh)", Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
            } else {
                // The root node carries the project name and is the "create at root" target, so it
                // is useless collapsed. Opening it here — synchronously inside this remember block —
                // rather than via LaunchedEffect is load-bearing: Jewel's LazyTree computes its own
                // remembered flatten list on first composition and, as part of that, intersects
                // TreeState.openNodes down to only the ids reachable from an already-OPEN root. A
                // LaunchedEffect's coroutine body runs strictly after that first composition commits,
                // so opening the root there is one frame too late — any pre-existing expand state
                // (e.g. a caller that expanded "adders" before this panel ever mounted) gets pruned
                // away in that same first pass because the root itself wasn't open yet when Jewel
                // computed reachability. Doing it here, before `buildTreeFrom` even runs, guarantees
                // the root is open by the time LazyTree's internal prune executes. Keyed on the root so
                // a genuinely new project re-opens it, while a user who collapses it during a session
                // keeps it collapsed (LazyTree's prune only runs once per (tree, treeState) identity).
                // remember(snap.root, edit): buildTreeFrom walks the WHOLE project tree recursively
                // and allocates a fresh Tree, which LazyTree then has to re-flatten, so this is kept
                // remembered rather than recomputed every recomposition. Keyed on the root so a
                // genuinely new snapshot still rebuilds, and on the edit so a pending create's
                // placeholder row appears and disappears.
                val tree = remember(snap.root, edit) {
                    ExplorerTreeState.treeState.openNodes += ExplorerTreeState.ROOT_PATH
                    ExplorerTreeState.buildTreeFrom(snap.root, edit)
                }
                // See [EditAwareKeyActions]: without this the tree's preview key handler swallows
                // every caret/selection key before the open InlineNameField can see it.
                val treeState = ExplorerTreeState.treeState
                val editing = edit != null
                val keyActions = remember(treeState, editing) {
                    EditAwareKeyActions(DefaultTreeViewKeyActions(treeState), editing)
                }
                LazyTree(
                    tree = tree,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    treeState = ExplorerTreeState.treeState,
                    keyActions = keyActions,
                    style = flushTreeStyle(),
                    onElementClick = { element -> onElementClick(element.data, ExplorerTreeState.pathOf(element)) },
                ) { element ->
                    TreeRow(
                        element.data,
                        ExplorerTreeState.pathOf(element),
                        snap.currentSubpath,
                        edit,
                        editError,
                        onCommit = { typed ->
                            val current = edit
                            val failure = when (current) {
                                is ExplorerEdit.Creating ->
                                    ExplorerActions.commitCreate(current.parentPath, current.kind, typed)
                                is ExplorerEdit.Renaming ->
                                    ExplorerActions.commitRename(current.path, typed)
                                null -> null
                            }
                            editError = failure
                            // Keep the field open on failure so the user can fix the name in place.
                            if (failure == null) edit = null
                        },
                        onCancel = { edit = null; editError = null },
                        onSecondaryClick = { path, local ->
                            ExplorerTreeState.select(path)
                            // Row-local → window coords: the scene is full-window at Density(1f),
                            // so a row's window position is its layout position. Using the raw local
                            // offset would anchor every menu near the panel's left edge.
                            menu.open(path, IntOffset(local.x.toInt(), local.y.toInt()))
                        },
                    )
                }
                ExplorerContextMenu(
                    state = menu,
                    onNew = { parent, kind ->
                        // The field is inside the folder, so the folder has to be open to see it.
                        ExplorerTreeState.treeState.openNodes += parent
                        edit = ExplorerEdit.Creating(parent, kind)
                    },
                    onRename = { path ->
                        edit = ExplorerEdit.Renaming(path, path.substringAfterLast('/'))
                    },
                    onDuplicate = { path -> editError = ExplorerActions.commitDuplicate(path) },
                    // menu.anchor survives close() (only `target` is nulled), so each dialog opens
                    // exactly where the menu item that triggered it was.
                    onDelete = { path -> dialogs.openDelete(path, menu.anchor) },
                    onMove = { path -> dialogs.openMove(path, menu.anchor) },
                    onLocalHistory = { path -> ExplorerActions.openLocalHistory(path) },
                )
                ExplorerDialogs(
                    state = dialogs,
                    onConfirmDelete = { path -> editError = ExplorerActions.commitDelete(path) },
                    onConfirmMove = { path, dest -> editError = ExplorerActions.commitMove(path, dest) },
                )
            }
        }
    }
}

/**
 * The ambient [LazyTreeStyle] with its horizontal *element padding* zeroed out.
 *
 * IntUi's default tree metrics wrap every row in `elementPadding = PaddingValues(horizontal = 12.dp)`
 * on top of `elementContentPadding = PaddingValues(4.dp)`. Because the scene runs at `Density(1f)`,
 * that is a flat 16 px of empty gutter on the left of every row before the folder icon — dead space
 * in a tool window that is only a couple hundred pixels wide — and it also insets the selection
 * highlight, so a selected row never reaches the panel edge the way IntelliJ's Project view does.
 *
 * Zeroing the *outer* padding (not the content padding) is what fixes both: the row background goes
 * edge-to-edge, and the 4.dp content padding survives as the icon's inset from the panel border.
 *
 * Only the first three parameters of `LazyTreeMetrics.defaults` are passed positionally — everything
 * after `elementPadding` (content padding, min height, gaps) keeps IntUi's own defaults.
 */
@Composable
private fun flushTreeStyle(): LazyTreeStyle {
    val base = LocalLazyTreeStyle.current
    return remember(base) {
        LazyTreeStyle(
            colors = base.colors,
            metrics = LazyTreeMetrics.defaults(
                base.metrics.indentSize,
                base.metrics.simpleListItemMetrics.selectionBackgroundCornerSize,
                PaddingValues(horizontal = 0.dp),
            ),
            icons = base.icons,
        )
    }
}

/**
 * Click behavior: a `.nbt` places its structure, and **any** folder toggles open/closed, with a
 * folder that directly contains `*.spec.kts` also loading as a project on that same click.
 *
 * Toggling here rather than leaving it to Jewel is the point: LazyTree only opens a node from its
 * chevron or a double-click, which makes the folder's own name — the largest target on the row —
 * inert. IntelliJ's Project view expands from anywhere on the row, and so does this.
 *
 * A spec-bearing folder deliberately does both. Loading is idempotent, so the alternative — some
 * folders expanding on click and others not — would buy nothing and cost a rule the player has to
 * learn from the contents of a folder they cannot see until it opens.
 *
 * Sends route through [ExplorerActions.sender] rather than `ClientPlayNetworking` directly, so this
 * policy is testable without a live connection, exactly like the create/rename commits.
 *
 * Deliberately does **not** call `ExplorerTreeState.select(path)`: LazyTree has already written the
 * clicked element's id into `TreeState.selectedKeys` before invoking this callback, and Jewel's
 * TreeState is the declared single source of truth for selection. A second writer here would be a
 * silent no-op today and a divergence the moment the two disagree (multi-select, drag-select).
 */
fun onElementClick(node: com.breadmoirai.garnet.editor.explorer.data.FileTreeNode, path: String) {
    when (node) {
        is FileNode -> if (node.extension == "nbt") ExplorerActions.sender(PlaceStructureC2S(path))
        is FolderNode -> {
            ExplorerTreeState.toggleExpanded(path)
            if (node.children.any { it is FileNode && it.name.endsWith(".spec.kts") }) {
                ExplorerActions.sender(LoadEditorFolderC2S(path))
            }
        }
    }
}

@Composable
private fun TreeRow(
    node: com.breadmoirai.garnet.editor.explorer.data.FileTreeNode,
    path: String,
    currentSubpath: String?,
    edit: ExplorerEdit?,
    editError: String?,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    onSecondaryClick: (String, Offset) -> Unit,
) {
    val creatingHere = edit is ExplorerEdit.Creating && ExplorerEdit.isPendingId(path)
    val renamingHere = edit is ExplorerEdit.Renaming && edit.path == path
    if (creatingHere || renamingHere) {
        val kindIcon = when {
            edit is ExplorerEdit.Creating && edit.kind == NewNodeKind.FOLDER -> AllIconsKeys.Nodes.Folder
            edit is ExplorerEdit.Creating -> AllIconsKeys.FileTypes.Archive
            node is FolderNode -> AllIconsKeys.Nodes.Folder
            else -> AllIconsKeys.FileTypes.Text
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(kindIcon, contentDescription = null)
            InlineNameField(
                initial = (edit as? ExplorerEdit.Renaming)?.original.orEmpty(),
                error = editError,
                onCommit = onCommit,
                onCancel = onCancel,
            )
        }
        return
    }
    var rowOrigin by remember { mutableStateOf(Offset.Zero) }
    Row(
        Modifier
            .onGloballyPositioned { rowOrigin = it.positionInWindow() }
            .pointerInput(path) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Press && event.button == PointerButton.Secondary) {
                            val position = event.changes.first().position
                            event.changes.forEach { it.consume() }
                            onSecondaryClick(path, rowOrigin + position)
                        }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (node) {
            is FolderNode -> Icon(AllIconsKeys.Nodes.Folder, contentDescription = null)
            is FileNode ->
                if (node.extension == "nbt") Icon(AllIconsKeys.FileTypes.Archive, contentDescription = null)
                else Icon(AllIconsKeys.FileTypes.Text, contentDescription = null)
        }
        val marker = if (path == currentSubpath) "● " else ""
        Text("  $marker${node.name}")
    }
}

/**
 * The in-tree name field. Enter commits, Escape cancels, and losing focus cancels — an abandoned
 * field must never linger as a phantom row after the user clicks elsewhere in the tree.
 *
 * [initial] arrives fully selected, the way a rename behaves everywhere else: the common case is
 * replacing the name outright, and the rarer "tweak it" case is one arrow key away.
 *
 * EditBox-style note does not apply here: this is a Jewel TextField over a Compose TextFieldState,
 * and `setTextAndSelectAll` does not fire a responder, so seeding [initial] is safe.
 *
 * Caret movement, keyboard selection and Ctrl+A only reach this field because the enclosing
 * LazyTree is handed an [EditAwareKeyActions] while an edit is open — see that class for why a
 * nested field otherwise loses those keys to the tree's preview handler.
 *
 * [GarnetTextField] rather than Jewel's `TextField` because in this scene only the wrapper's bridged
 * focus makes the caret and the focused border appear at all — see that component for why.
 *
 * [error] renders beneath the field rather than in a panel-wide status line: the panel that now owns
 * status (Structure Info) may be closed, and a rename that failed would otherwise leave the user with
 * a red border and no reason for it anywhere on screen.
 */
@Composable
private fun RowScope.InlineNameField(
    initial: String,
    error: String?,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val state = rememberTextFieldState()
    val focusRequester = remember { FocusRequester() }
    // See the "first frame" note on [onFocusChanged] below.
    var everFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        state.setTextAndSelectAll(initial)
        focusRequester.requestFocus()
    }
    Column(Modifier.weight(1f)) {
        GarnetTextField(
            state = state,
            outline = if (error != null) Outline.Error else Outline.None,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                // Cancel only on a focused -> unfocused TRANSITION, never on the first event.
                //
                // Compose's FocusChangedNode starts with null stored state, so the very first focus event
                // a freshly attached node sees (Inactive) counts as a "change" and fires this callback
                // with isFocused == false. That dispatch happens in onEndApplyChanges, synchronously after
                // the composition's changes are applied — strictly BEFORE the LaunchedEffect above gets a
                // chance to run requestFocus(), because a LaunchedEffect body is only *scheduled* on the
                // frame dispatcher during applyChanges. A naive `if (!it.isFocused) onCancel()` therefore
                // tears the field down on the frame it appears, killing both New and Rename outright.
                // Gating on everFocused keeps the intended behaviour — an abandoned field still cancels
                // when the user clicks away — while ignoring that synthetic initial Inactive.
                .onFocusChanged {
                    if (it.isFocused) everFocused = true
                    else if (everFocused) onCancel()
                }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onCommit(state.text.toString()); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
        )
        // The message lives at the field, not in a panel-wide status line, because the panel that
        // now owns status (Structure Info) may be closed — a rename that failed would otherwise
        // leave the user with a red border and no reason for it anywhere on screen.
        if (error != null) Text(error, Modifier.padding(start = 2.dp, top = 1.dp))
    }
}
