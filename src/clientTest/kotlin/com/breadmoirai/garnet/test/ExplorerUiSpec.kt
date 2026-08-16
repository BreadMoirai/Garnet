package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.ui.ExplorerActions
import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.ui.explorerPanel
import com.breadmoirai.garnet.editor.ui.localHistoryPanel
import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.compose.ComposeSurface
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.input.DockInputRouter
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.DeletePathC2S
import com.breadmoirai.garnet.editor.network.DuplicatePathC2S
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.MovePathC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.network.UndoStateS2C
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.editor.ui.UndoState
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

/**
 * Consolidated Explorer right-click (New/Rename) story: one mount, one continuous journey, folded
 * from ExplorerContextMenuSpec's six independent mounts (renamed from that file; the five payload-only
 * cases already moved to ExplorerActionsTest under src/test). The invalid-name rejection is exercised
 * as a step INSIDE the create flow rather than as a separate mount -- typing a rejected name first and
 * then correcting it in the SAME still-open field is what proves the field survives a rejected commit,
 * which is the whole point of keeping it in-line instead of a standalone case.
 */
class ExplorerUiSpec : ClientSpec({

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    afterTest { ExplorerActions.resetForTest() }

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue()
        return p
    }

    /** Mounts the Explorer with one root -> "redstone" -> "clock.nbt", focused and ready to click. */
    fun mountForContextMenu() {
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(EditorTreeSnapshotS2C(
                FolderNode("myproject", listOf(FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))))),
                null,
            ))
            DockState.panels += explorerPanel()
            DockState.showPanel("garnet.explorer")
            DockState.setSize(DockRegion.LEFT, 320)
            ViewportState.active = true
            ComposeOverlay.enabled = true
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
            DockInputRouter.focus(DockRegion.LEFT)
        }
        waitClientTicks(16)
    }

    fun unmountFromContextMenu() {
        runOnClient { mc ->
            DockInputRouter.clearFocus()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    /** Right-click at ([x], [y]) and open the menu there, leaving it up. */
    fun rightClick(x: Double, y: Double) {
        runOnClient {
            DockInputRouter.onGlfwMove(x, y)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)
    }

    /** Left-click at ([x], [y]). */
    fun click(x: Double, y: Double) {
        runOnClient {
            DockInputRouter.onGlfwMove(x, y)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(8)
    }

    /**
     * Right-click at ([x], [y]) and click "New Folder", the card's first row (offset +20/+12 from the
     * click point, measured in calib_1_menu_open.png). `FixedOffsetPositionProvider` puts the card's
     * top-left at the click point, so the same relative offset applies to any row.
     */
    fun rightClickThenNewFolder(x: Double, y: Double) {
        rightClick(x, y)
        click(x + 20.0, y + 12.0)
    }

    /**
     * Like [mountForContextMenu], but with "redstone" expanded so its "clock.nbt" child is a
     * clickable row.
     *
     * The duplicate/delete/move tests need a FILE target, not the folder: in this one-folder tree,
     * moving "redstone" itself would have no legal destination at all (its only candidate, the
     * project root, is excluded as its current parent), so the move dialog would come up empty.
     */
    fun mountWithRedstoneExpanded() {
        mountForContextMenu()
        runOnClient { ExplorerTreeState.treeState.openNodes += "redstone" }
        waitClientTicks(10)
    }

    /**
     * Menu-row geometry, measured from the hover probes in the nested-folder test above:
     * "New Folder" (row 0) sits at +19 from the click point and "Rename" (row 2, one separator
     * above it) at +76 — i.e. 19 + 2*[ROW_PITCH] + 1*[SEPARATOR_H].
     */
    val ROW_0_DY = 19.0
    val ROW_PITCH = 24.0
    val SEPARATOR_H = 9.0

    /** Click the menu row at [index], counting only selectable rows, with [separatorsAbove] above it. */
    fun clickMenuRow(x: Double, y: Double, index: Int, separatorsAbove: Int) {
        click(x + 20.0, y + ROW_0_DY + index * ROW_PITCH + separatorsAbove * SEPARATOR_H)
    }

    // Menu order (ExplorerContextMenu.kt): New Folder(0), New Structure(1), --, Rename(2),
    // Duplicate(3), Move to…(4), --, Local History(5), --, Delete(6).
    fun rightClickThenDuplicate(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 3, separatorsAbove = 1)
    }

    fun rightClickThenMove(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 4, separatorsAbove = 1)
    }

    fun rightClickThenLocalHistory(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 5, separatorsAbove = 2)
    }

    fun rightClickThenDelete(x: Double, y: Double) {
        rightClick(x, y); clickMenuRow(x, y, index = 6, separatorsAbove = 3)
    }

    /**
     * Both dialogs open at the SAME anchor the context menu used, so their rows sit at the same
     * offsets from the original right-click point.
     *
     * Delete dialog rows: the prompt/confirm row(0), Cancel(1).
     * Move dialog rows: one per destination in tree order, then Cancel. With "redstone/clock.nbt"
     * as the moved node, "redstone" is its current parent and is excluded, leaving the project root
     * alone at index 0.
     */
    fun clickDialogConfirm(x: Double, y: Double) = clickMenuRow(x, y, index = 0, separatorsAbove = 0)
    fun clickDialogCancel(x: Double, y: Double) = clickMenuRow(x, y, index = 1, separatorsAbove = 0)
    fun clickMoveDestinationRoot(x: Double, y: Double) = clickMenuRow(x, y, index = 0, separatorsAbove = 0)

    /**
     * The "clock.nbt" row, one tree row below "redstone" (which the nested-folder test above
     * right-clicks at 90/68). Tree rows are 28px apart, measured from the root row at y=40.
     */
    val CLOCK_X = 90.0
    val CLOCK_Y = 96.0

    fun type(text: String) {
        runOnClient { text.forEach { c -> DockInputRouter.onGlfwChar(c.code) } }
        waitClientTicks(6)
    }

    fun pressEnter() {
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS) }
        waitClientTicks(8)
    }

    fun pressEscape() {
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS) }
        waitClientTicks(8)
    }

    test("right-click a nested folder, create through the inline field, and the payload reaches the sender") {
        val sent = captureSends()
        mountForContextMenu()

        // 1. right-click a tree row -- the menu opens, painted at the click point. Row 1 of the tree
        // body, below the toolbar: the project root node.
        val closedShot = capture("explorer_context_menu_closed.png")
        val closedCount = PanelPixelProbe.contextMenuRegionDiffCount(closedShot)
        println("[context-menu] probe (closed): diffCount=$closedCount")
        rightClick(60.0, 40.0)
        val openShot = capture("explorer_context_menu_open.png")
        val openCount = PanelPixelProbe.contextMenuRegionDiffCount(openShot)
        println("[context-menu] probe (open): diffCount=$openCount")
        // Pixel-probe gate, mirroring the kebab-menu discrimination in JewelExplorerSpec: the capture
        // existing and the surface staying alive prove nothing about whether the menu actually
        // painted at the click point. Require the closed baseline well below the open reading.
        closedCount shouldBeLessThan PanelPixelProbe.CONTEXT_MENU_CLOSED_MAX
        openCount shouldBeGreaterThan PanelPixelProbe.CONTEXT_MENU_OPEN_MIN
        ComposeSurface.disabled.shouldBeFalse()
        sent.shouldBeEmpty() // opening a menu sends nothing
        pressEscape() // dismiss before the next step's right-click on a different row

        // 2. hover moves between menu rows, and the row under the cursor is the one that acts.
        // REGRESSION coverage for the nested-popup freeze (the menu used to be `New > (Folder |
        // Structure)` plus `Rename`, and Jewel's submenu flyout is a second focusable popup layer that
        // used to block all pointer input to the parent card below it). "redstone", not the project
        // root: the root's Rename is disabled, and a disabled Jewel menu item emits no hover
        // interactions at all, which would make the pixel half of this vacuous.
        rightClick(90.0, 68.0)
        runOnClient { DockInputRouter.onGlfwMove(110.0, 87.0) } // "New Folder", row 1
        waitClientTicks(10)
        val onNew = capture("context_menu_hover_new_folder.png")
        runOnClient { DockInputRouter.onGlfwMove(110.0, 144.0) } // "Rename", row 3
        waitClientTicks(10)
        val onRename = capture("context_menu_hover_rename.png")

        fun newFolderRow(png: Path) = PanelPixelProbe.selectionPixelCount(
            png, PanelPixelProbe.CONTEXT_MENU_ROW_XS, PanelPixelProbe.CONTEXT_MENU_NEW_FOLDER_YS,
        )
        fun renameRow(png: Path) = PanelPixelProbe.selectionPixelCount(
            png, PanelPixelProbe.CONTEXT_MENU_ROW_XS, PanelPixelProbe.CONTEXT_MENU_RENAME_YS,
        )
        println("[context-menu] hover New Folder: newRow=${newFolderRow(onNew)} renameRow=${renameRow(onNew)}")
        println("[context-menu] hover Rename:     newRow=${newFolderRow(onRename)} renameRow=${renameRow(onRename)}")

        newFolderRow(onNew) shouldBeGreaterThan PanelPixelProbe.MENU_ROW_HOVERED_MIN
        renameRow(onNew) shouldBe 0
        newFolderRow(onRename) shouldBe 0 // the whole bug: this used to stay lit
        renameRow(onRename) shouldBeGreaterThan PanelPixelProbe.MENU_ROW_HOVERED_MIN

        // ...and the click lands on Rename, not on a stale flyout. The field seeds fully SELECTED
        // (ProjectExplorerPanel.kt: `state.setTextAndSelectAll(initial)`, deliberate -- "the way a
        // rename behaves everywhere else"), so typing over it replaces the whole name rather than
        // appending after a trailing cursor.
        click(110.0, 144.0)
        type("redstone2")
        pressEnter()
        sent shouldBe listOf(RenamePathC2S("redstone", "redstone2"))

        // 3. New > Folder on the nested "redstone" folder auto-expands it and targets it, not the
        // root. The panel opens the field by adding the target folder's path to treeState.openNodes
        // (see ProjectExplorer's onNew callback), so the field is visible without a separate click to
        // expand.
        rightClickThenNewFolder(90.0, 68.0)
        ExplorerTreeState.treeState.openNodes.contains("redstone").shouldBeTrue()

        // 4. an invalid name keeps the inline field open for correction, and nothing new is sent.
        // This drives the rejection through the real mounted field to prove the field itself survives
        // it -- a second, valid commit in the SAME interaction must still reach the sender.
        type("   ") // trims to blank -- rejected
        pressEnter()
        sent shouldBe listOf(RenamePathC2S("redstone", "redstone2")) // unchanged: nothing new sent

        // 5. correct it, press Enter -- CreateFolderC2S with the nested parent reaches the sender. The
        // field's text isn't cleared on a rejected commit, so this appends onto the still-queued
        // "   " rather than replacing it -- "   clocks" trims to a perfectly valid "clocks".
        // Committing successfully here is only possible if the SAME field survived the rejection above.
        type("clocks")
        pressEnter()
        sent shouldBe listOf(RenamePathC2S("redstone", "redstone2"), CreateFolderC2S("redstone", "clocks"))

        // 6. reopen the field, press Escape -- nothing is sent and no stale text lingers.
        rightClickThenNewFolder(60.0, 40.0)
        type("abandoned")
        pressEscape()
        // If Escape had not actually cancelled the field (e.g. the "cancels on mount" class of bug
        // reappearing), the field would still be open with "abandoned" queued, and this bare Enter
        // would commit it. A genuinely cancelled field has nothing left to commit.
        pressEnter()
        sent shouldBe listOf(RenamePathC2S("redstone", "redstone2"), CreateFolderC2S("redstone", "clocks"))

        unmountFromContextMenu()
    }

    test("delete asks for confirmation first, and cancelling sends nothing") {
        val sent = captureSends()
        mountWithRedstoneExpanded()
        try {
            // Right-click "clock.nbt", choose Delete: the confirmation must appear and, until it is
            // confirmed, nothing may reach the wire.
            rightClickThenDelete(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()

            clickDialogCancel(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()
        } finally {
            unmountFromContextMenu()
        }
    }

    test("confirming the delete dialog sends DeletePathC2S for the clicked node") {
        val sent = captureSends()
        mountWithRedstoneExpanded()
        try {
            rightClickThenDelete(CLOCK_X, CLOCK_Y)
            clickDialogConfirm(CLOCK_X, CLOCK_Y)
            sent shouldBe listOf(DeletePathC2S("redstone/clock.nbt"))
        } finally {
            unmountFromContextMenu()
        }
    }

    test("duplicate sends immediately, with no dialog") {
        val sent = captureSends()
        mountWithRedstoneExpanded()
        try {
            rightClickThenDuplicate(CLOCK_X, CLOCK_Y)
            sent shouldBe listOf(DuplicatePathC2S("redstone/clock.nbt"))
        } finally {
            unmountFromContextMenu()
        }
    }

    test("Local History places the structure first when it isn't already open") {
        // The row this task's coordinate fix (Finding 3/4) revealed was never actually exercised:
        // rightClickThenDelete's old index/separatorsAbove landed exactly here instead, so this is
        // the only menu row that had no dedicated coverage at all.
        val sent = captureSends()
        mountWithRedstoneExpanded()
        runOnClient { DockState.panels += localHistoryPanel() }
        try {
            rightClickThenLocalHistory(CLOCK_X, CLOCK_Y)
            sent shouldBe listOf(PlaceStructureC2S("redstone/clock.nbt"))
            onClient { DockState.openPanelId(DockRegion.LEFT) } shouldBe "garnet.localHistory"
        } finally {
            unmountFromContextMenu()
        }
    }

    test("the move dialog sends MovePathC2S for the chosen destination") {
        val sent = captureSends()
        mountWithRedstoneExpanded()
        try {
            rightClickThenMove(CLOCK_X, CLOCK_Y)
            sent.shouldBeEmpty()                          // the dialog is open; nothing sent yet

            clickMoveDestinationRoot(CLOCK_X, CLOCK_Y)    // choose the project root
            sent shouldBe listOf(MovePathC2S("redstone/clock.nbt", ""))
        } finally {
            unmountFromContextMenu()
        }
    }

    test("UndoState mirrors the server's labels") {
        UndoState.reset()
        UndoState.onUndoState(UndoStateS2C("delete 'a.nbt'", null))
        UndoState.undoLabel shouldBe "delete 'a.nbt'"
        UndoState.redoLabel.shouldBeNull()
    }

    test("UndoState.reset clears both labels") {
        UndoState.onUndoState(UndoStateS2C("x", "y"))
        UndoState.reset()
        // Cleared on disconnect so a rejoin never shows an enabled button backed by a server
        // stack that was dropped with the connection.
        UndoState.undoLabel.shouldBeNull()
        UndoState.redoLabel.shouldBeNull()
    }
})
