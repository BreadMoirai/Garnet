package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.explorer.ui.ExplorerActions
import com.breadmoirai.garnet.editor.structure.ui.OpenStructureState
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeSnapshot
import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.explorer.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.editor.explorer.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.explorer.network.DeletePathC2S
import com.breadmoirai.garnet.editor.explorer.network.DuplicatePathC2S
import com.breadmoirai.garnet.editor.explorer.network.MovePathC2S
import com.breadmoirai.garnet.editor.explorer.network.NewStructureC2S
import com.breadmoirai.garnet.editor.structure.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.explorer.network.RenamePathC2S
import com.breadmoirai.garnet.editor.structure.network.StructureResultS2C
import com.breadmoirai.garnet.editor.explorer.data.NewNodeKind
import com.breadmoirai.garnet.dock.shell.DockRegion
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.shell.Panel
import com.breadmoirai.garnet.dock.viewport.DockVisibilityCommit
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * What the Explorer's create/rename actions put on the wire. Pure: `ExplorerActions.sender` is a
 * settable seam, so nothing here needs a client. The in-scene flow that *reaches* these functions
 * (right-click → New Folder → type → Enter) is `ExplorerUiSpec` in `src/clientTest`.
 */
class ExplorerActionsTest : FunSpec({

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    /** A snapshot whose root contains a single file named [name] at the top level. */
    fun snapshotWith(name: String) = EditorTreeSnapshotS2C(
        root = FolderNode("project", listOf(FileNode(name, name.substringAfterLast('.', "")))),
        currentSubpath = null,
    )

    /** Registers a LEFT dock panel under [id], as `GarnetClient` does at client init. */
    fun registerLeftPanel(id: String) {
        DockState.panels += Panel(id, id, DockRegion.LEFT, AllIconsKeys.General.Information) {}
    }

    // openLocalHistory ends in commitDockVisibilityChange(), whose production side effects reach the
    // config directory and Minecraft.getInstance(). Stub all three for this plain-JVM spec; the real
    // sequence is pinned by DockVisibilityCommitTest and exercised live by ExplorerUiSpec.
    beforeTest {
        DockVisibilityCommit.persistLayout = {}
        DockVisibilityCommit.dropFocus = {}
        DockVisibilityCommit.applyFramebuffer = {}
    }

    afterTest {
        DockVisibilityCommit.resetForTest()
        ExplorerActions.resetForTest()
        ExplorerTreeSnapshot.reset()
        OpenStructureState.reset()
        DockState.reset()
    }

    test("creating a folder sends CreateFolderC2S with the target parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("redstone", NewNodeKind.FOLDER, "clocks") shouldBe null
        sent shouldBe listOf(CreateFolderC2S("redstone", "clocks"))
    }

    test("creating a folder at the root sends CreateFolderC2S with an empty parent") {
        val sent = captureSends()
        ExplorerActions.commitCreate("", NewNodeKind.FOLDER, "gadget") shouldBe null
        sent shouldBe listOf(CreateFolderC2S("", "gadget"))
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

    test("renaming a file without an extension keeps the one it had") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "ring-clock") shouldBe null
        sent shouldBe listOf(RenamePathC2S("redstone/clock.nbt", "ring-clock.nbt"))
    }

    test("renaming a folder does not acquire an extension from its dots") {
        ExplorerTreeSnapshot.onSnapshot(
            EditorTreeSnapshotS2C(
                root = FolderNode("project", listOf(FolderNode("my.stuff", emptyList()))),
                currentSubpath = null,
            ),
        )
        val sent = captureSends()
        ExplorerActions.commitRename("my.stuff", "your") shouldBe null
        sent shouldBe listOf(RenamePathC2S("my.stuff", "your"))
    }

    test("renaming to a path is rejected") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "a/b.nbt").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("duplicating sends DuplicatePathC2S for the clicked node") {
        val sent = captureSends()
        ExplorerActions.commitDuplicate("redstone/clock.nbt") shouldBe null
        sent shouldBe listOf(DuplicatePathC2S("redstone/clock.nbt"))
    }

    test("deleting sends DeletePathC2S for the clicked node") {
        val sent = captureSends()
        ExplorerActions.commitDelete("redstone") shouldBe null
        sent shouldBe listOf(DeletePathC2S("redstone"))
    }

    test("the project root cannot be duplicated, deleted, or moved") {
        val sent = captureSends()
        ExplorerActions.commitDuplicate("").shouldNotBeNull()
        ExplorerActions.commitDelete("").shouldNotBeNull()
        ExplorerActions.commitMove("", "redstone").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("moving sends MovePathC2S with the destination folder") {
        val sent = captureSends()
        ExplorerActions.commitMove("clock.nbt", "redstone") shouldBe null
        sent shouldBe listOf(MovePathC2S("clock.nbt", "redstone"))
    }

    test("moving to the project root sends an empty destination") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone/clock.nbt", "") shouldBe null
        sent shouldBe listOf(MovePathC2S("redstone/clock.nbt", ""))
    }

    test("a folder cannot be moved into itself or its own subtree") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone", "redstone").shouldNotBeNull()
        ExplorerActions.commitMove("redstone", "redstone/clocks").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("moving into a same-prefixed sibling folder is allowed") {
        // "redstoneworks" is a sibling of "redstone", not a descendant — a plain startsWith check
        // would wrongly reject this.
        val sent = captureSends()
        ExplorerActions.commitMove("redstone", "redstoneworks") shouldBe null
        sent shouldBe listOf(MovePathC2S("redstone", "redstoneworks"))
    }

    test("moving into the folder a node already lives in sends nothing") {
        val sent = captureSends()
        ExplorerActions.commitMove("redstone/clock.nbt", "redstone").shouldNotBeNull()
        sent.shouldBeEmpty()
    }

    test("opening local history for an unplaced structure places it first") {
        val sent = captureSends()
        ExplorerTreeSnapshot.onSnapshot(snapshotWith("clock.nbt"))

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        sent.filterIsInstance<PlaceStructureC2S>().single().subpath shouldBe "clock.nbt"
    }

    test("opening local history for the already-placed structure sends no place packet") {
        val sent = captureSends()
        ExplorerTreeSnapshot.onSnapshot(snapshotWith("clock.nbt"))
        OpenStructureState.onStructureResult(StructureResultS2C("clock.nbt", 1, 1, 1, "placed"))

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        sent.filterIsInstance<PlaceStructureC2S>().shouldBeEmpty()
    }

    test("opening local history switches the LEFT region to the Local History panel") {
        captureSends()
        ExplorerTreeSnapshot.onSnapshot(snapshotWith("clock.nbt"))
        registerLeftPanel("garnet.explorer")
        registerLeftPanel("garnet.localHistory")
        DockState.showPanel("garnet.explorer")

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        DockState.openPanelId(DockRegion.LEFT) shouldBe "garnet.localHistory"
    }

    test("opening local history with no Local History panel registered leaves LEFT closed") {
        // Pins the showPanel() no-op on an unknown id. The former implementation passed
        // leftPanels.indexOfFirst { ... } -- i.e. -1 -- into setActiveTab, whose clamp turned it
        // into index 0, so an unregistered Local History silently selected the *Explorer* tab on an
        // already-open LEFT. showPanel does nothing at all instead.
        captureSends()
        ExplorerTreeSnapshot.onSnapshot(snapshotWith("clock.nbt"))
        registerLeftPanel("garnet.explorer")

        ExplorerActions.openLocalHistory("clock.nbt") shouldBe null

        DockState.openPanelId(DockRegion.LEFT) shouldBe null
        DockState.isVisible(DockRegion.LEFT) shouldBe false
    }

    test("local history is refused for a non-structure path") {
        val sent = captureSends()

        ExplorerActions.openLocalHistory("notes.spec.kts").shouldNotBeNull()

        sent.shouldBeEmpty()
    }
})
