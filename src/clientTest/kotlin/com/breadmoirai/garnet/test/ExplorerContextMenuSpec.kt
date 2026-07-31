package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerActions
import com.breadmoirai.garnet.client.ide.ExplorerTreeState
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.client.ide.explorerPanel
import com.breadmoirai.garnet.client.ui.compose.ComposeOverlay
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter
import com.breadmoirai.garnet.client.viewport.ViewportState
import com.breadmoirai.garnet.client.viewport.WindowViewportExt
import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.lwjgl.glfw.GLFW
import java.nio.file.Files
import java.nio.file.Path

class ExplorerContextMenuSpec : ClientSpec({

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    afterTest { ExplorerActions.resetForTest() }

    test("creating a folder sends CreateFolderC2S with the target parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "clocks") shouldBe null
        sent shouldBe listOf(CreateFolderC2S("redstone", "clocks"))
    }

    test("creating a structure appends .nbt and targets the parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("", NewNodeKind.STRUCTURE, "gadget") shouldBe null
        sent shouldBe listOf(NewStructureC2S("", "gadget.nbt"))
    }

    test("an invalid name sends nothing and reports why") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "  ").shouldNotBeNull()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "a/b").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("renaming sends RenamePathC2S with a bare new name") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "ring-clock.nbt") shouldBe null
        sent shouldBe listOf(RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt"))
    }

    test("renaming to a path is rejected") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "a/b.nbt").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    fun capture(name: String): Path {
        val p = Path.of("screenshots", name).toAbsolutePath()
        Files.deleteIfExists(p)
        runOnClient { ViewportState.compositeCaptureRequest = p }
        val deadline = System.currentTimeMillis() + 6000
        while (!Files.exists(p) && System.currentTimeMillis() < deadline) Thread.sleep(50)
        Files.exists(p).shouldBeTrue()
        return p
    }

    test("a right-click on a tree row opens the menu, painted at the click point") {
        val sent = captureSends()
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
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

        // Baseline BEFORE the right-click: same mount, same region, menu absent.
        val closedShot = capture("explorer_context_menu_closed.png")
        val closedCount = PanelPixelProbe.contextMenuRegionDiffCount(closedShot)
        println("[context-menu] probe (closed): diffCount=$closedCount")

        // Row 1 of the tree body, below the toolbar: the project root node.
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 40.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)

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

        runOnClient { mc ->
            DockInputRouter.clearFocus()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    test("New > Folder end-to-end: right-click, hover New, click Folder, type a name, press Enter -- the payload reaches ExplorerActions.sender") {
        // REGRESSION coverage for the "everFocused" fix on InlineNameField. Before that fix,
        // FocusChangedNode's synthetic first Inactive event fired onCancel() the instant the field
        // mounted -- before LaunchedEffect ever got to call requestFocus() -- so `edit` was reset to
        // null on the very frame New/Rename opened. New was completely dead: the field would open and
        // instantly vanish, and no keystroke could ever reach it. This test drives the whole real path
        // (right-click -> hover New -> click Folder -> type -> Enter) so it can only pass if a real,
        // live, focused InlineNameField survives from the moment it mounts through to commit.
        val sent = captureSends()
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
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

        // Right-click the project root row (same point ExplorerContextMenuSpec's other test uses,
        // already verified above to paint the New/Rename card there).
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 40.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)

        // Hover "New" (measured at x[62,138] y[42,66] in calib_1_menu_open.png) to fly out its
        // submenu -- Jewel opens it on hover, not click (calib_2_hover_new.png).
        runOnClient { DockInputRouter.onGlfwMove(80.0, 52.0) }
        waitClientTicks(10)

        // Click "Folder" in the flown-out submenu (measured at x[142,215] y[48,70]).
        runOnClient {
            DockInputRouter.onGlfwMove(150.0, 52.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(8)

        // The whole point: if InlineNameField cancelled itself on mount, `edit` is already null here
        // and every keystroke below lands on nothing. Capture proof either way.
        val fieldShot = capture("explorer_new_folder_field_open.png")
        println("[new-folder-e2e] field-open probe: diffCount=${PanelPixelProbe.contextMenuRegionDiffCount(fieldShot)}")

        runOnClient { "gadget".forEach { c -> DockInputRouter.onGlfwChar(c.code) } }
        waitClientTicks(6)
        runOnClient { DockInputRouter.onGlfwKey(GLFW.GLFW_KEY_ENTER, GLFW.GLFW_PRESS) }
        waitClientTicks(8)

        sent shouldBe listOf(CreateFolderC2S("", "gadget"))

        runOnClient { mc ->
            DockInputRouter.clearFocus()
            ComposeOverlay.enabled = false; ViewportState.active = false; DockState.reset()
            (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
        }
        waitClientTicks(6)
    }

    /** Mounts the Explorer with one root -> "redstone" -> "clock.nbt", focused and ready to click. */
    fun mountForContextMenu() {
        runOnClient { mc ->
            DockState.reset(); ProjectTreeState.reset(); ExplorerTreeState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
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

    /**
     * Right-click at ([x], [y]), hover "New" (offset +30/+12, measured in calib_1/calib_2), and click
     * "Folder" in the flown-out submenu (offset +90/+12) -- the same real, hover-then-click path the
     * end-to-end test above calibrated against the project-root row. Any row anchors the popup at its
     * own click point, so the same relative offsets apply regardless of which row was right-clicked.
     */
    fun rightClickThenNewFolder(x: Double, y: Double) {
        runOnClient {
            DockInputRouter.onGlfwMove(x, y)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)
        runOnClient { DockInputRouter.onGlfwMove(x + 20.0, y + 12.0) }
        waitClientTicks(10)
        runOnClient {
            DockInputRouter.onGlfwMove(x + 90.0, y + 12.0)
            DockInputRouter.onGlfwPress(0)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(0) }
        waitClientTicks(8)
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

    test("New > Folder on a nested folder auto-expands it and targets it, not the root") {
        // The panel opens the field by adding the target folder's path to treeState.openNodes (see
        // ProjectExplorer's onNew callback) so the field is visible without a separate click to
        // expand. Right-click "redstone" itself (row 1, y=68). x=90 lands on the row's LABEL text --
        // "redstone" is indented one level deeper than the root, so its own chevron/folder-icon sit
        // where x=60 (safe for the unindented root row) would land instead, and a right-click there
        // never reaches TreeRow's own secondary-click handler.
        val sent = captureSends()
        mountForContextMenu()

        rightClickThenNewFolder(90.0, 68.0)
        type("clocks")
        pressEnter()

        ExplorerTreeState.treeState.openNodes.contains("redstone").shouldBeTrue()
        sent shouldBe listOf(CreateFolderC2S("redstone", "clocks"))

        unmountFromContextMenu()
    }

    test("an invalid name from a real New flow keeps the inline field open for correction") {
        // Only half of this was covered before: commitCreate("a/b") sends nothing, asserted directly
        // against ExplorerActions. This drives the same rejection through the real mounted field and
        // proves the field itself survives the rejection -- a second, valid commit in the SAME
        // interaction must still reach the sender. If the field had actually closed (or never
        // survived to receive the first Enter), this second attempt would have nowhere to land and
        // `sent` would stay empty.
        val sent = captureSends()
        mountForContextMenu()

        rightClickThenNewFolder(60.0, 40.0)
        type("   ") // trims to blank -- rejected
        pressEnter()
        sent.shouldBeEmpty()

        // The field's text isn't cleared on a rejected commit, so this appends onto the still-queued
        // "   " rather than replacing it -- "   gadget" trims to a perfectly valid "gadget". Committing
        // successfully here is only possible if the SAME field survived the rejection above.
        type("gadget")
        pressEnter()
        sent shouldBe listOf(CreateFolderC2S("", "gadget"))

        unmountFromContextMenu()
    }

    test("Escape cancels a real New field: nothing is sent and no stale text lingers") {
        val sent = captureSends()
        mountForContextMenu()

        rightClickThenNewFolder(60.0, 40.0)
        type("abandoned")
        pressEscape()
        // If Escape had not actually cancelled the field (e.g. the same "cancels on mount" class of
        // bug reappearing), the field would still be open with "abandoned" queued, and this bare
        // Enter would commit it. A genuinely cancelled field has nothing left to commit.
        pressEnter()
        sent.shouldBeEmpty()

        unmountFromContextMenu()
    }
})
