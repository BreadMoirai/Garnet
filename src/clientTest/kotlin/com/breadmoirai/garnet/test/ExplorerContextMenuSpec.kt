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
})
