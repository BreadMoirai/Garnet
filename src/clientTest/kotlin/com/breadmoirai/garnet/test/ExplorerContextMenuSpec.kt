package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.ExplorerActions
import com.breadmoirai.garnet.client.ide.ProjectTreeState
import com.breadmoirai.garnet.client.ide.explorerPanel
import com.breadmoirai.garnet.client.ui.compose.ComposeSurface
import com.breadmoirai.garnet.client.ui.compose.dock.DockRegion
import com.breadmoirai.garnet.client.ui.compose.dock.DockState
import com.breadmoirai.garnet.client.ui.compose.input.DockInputRouter
import com.breadmoirai.garnet.network.project.CreateFolderC2S
import com.breadmoirai.garnet.network.project.NewStructureC2S
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.network.project.RenamePathC2S
import com.breadmoirai.garnet.project.FileNode
import com.breadmoirai.garnet.project.FolderNode
import com.breadmoirai.garnet.project.NewNodeKind
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.lwjgl.glfw.GLFW

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

    test("a right-click on a tree row opens the menu") {
        val sent = captureSends()
        runOnClient {
            DockState.reset()
            ProjectTreeState.onSnapshot(ProjectTreeSnapshotS2C(
                FolderNode("myproject", listOf(FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt"))))),
                null,
            ))
            DockState.leftPanels.clear()
            DockState.leftPanels.add(explorerPanel())
            DockState.setVisible(DockRegion.LEFT, true)
            DockInputRouter.focus(DockRegion.LEFT)
        }
        waitClientTicks(6)

        // Row 1 of the tree body, below the toolbar: the project root node.
        runOnClient {
            DockInputRouter.onGlfwMove(60.0, 40.0)
            DockInputRouter.onGlfwPress(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
        }
        waitClientTicks(2)
        runOnClient { DockInputRouter.onGlfwRelease(GLFW.GLFW_MOUSE_BUTTON_RIGHT) }
        waitClientTicks(8)

        ComposeSurface.disabled.shouldBeFalse()
        sent.shouldBeEmpty() // opening a menu sends nothing

        runOnClient { DockInputRouter.clearFocus(); DockState.leftPanels.clear() }
    }
})
