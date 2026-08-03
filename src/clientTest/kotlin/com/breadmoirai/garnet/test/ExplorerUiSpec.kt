package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.editor.ui.ExplorerActions
import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.ui.explorerPanel
import com.breadmoirai.garnet.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.ui.compose.ComposeSurface
import com.breadmoirai.garnet.ui.dock.DockRegion
import com.breadmoirai.garnet.ui.dock.DockState
import com.breadmoirai.garnet.ui.input.DockInputRouter
import com.breadmoirai.garnet.ui.viewport.ViewportState
import com.breadmoirai.garnet.ui.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.data.FileNode
import com.breadmoirai.garnet.editor.data.FolderNode
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
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
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
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

        // ...and the click lands on Rename, not on a stale flyout. The field seeds with the current
        // name and places the cursor at the end, so typing "2" commits "redstone2".
        click(110.0, 144.0)
        type("2")
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
})
