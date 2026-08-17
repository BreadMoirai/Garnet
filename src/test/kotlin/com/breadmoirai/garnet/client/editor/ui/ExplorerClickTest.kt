package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.explorer.data.FileNode
import com.breadmoirai.garnet.editor.explorer.data.FolderNode
import com.breadmoirai.garnet.editor.network.LoadEditorFolderC2S
import com.breadmoirai.garnet.editor.network.PlaceStructureC2S
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerActions
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.explorer.ui.onElementClick
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/**
 * Clicking a folder row anywhere toggles it, the way IntelliJ's Project view does — expansion must
 * not be reachable only through the chevron or a double-click.
 *
 * A folder that directly contains specs keeps loading as a project on the same click: the load is
 * idempotent, so making the row do both costs nothing and avoids a rule where some folders expand
 * on click and others do not.
 */
class ExplorerClickTest : FunSpec({

    val specFolder = FolderNode("adders", listOf(FileNode("full.spec.kts", "kts")))
    val plainFolder = FolderNode("redstone", listOf(FileNode("clock.nbt", "nbt")))

    fun captureSends(): MutableList<CustomPacketPayload> {
        val sent = mutableListOf<CustomPacketPayload>()
        ExplorerActions.sender = { sent += it }
        return sent
    }

    afterTest { ExplorerActions.resetForTest() }

    test("clicking a plain folder toggles it open, and clicking again closes it") {
        ExplorerTreeState.reset()
        val sent = captureSends()

        onElementClick(plainFolder, "redstone")
        ExplorerTreeState.expandedPaths shouldContain "redstone"

        onElementClick(plainFolder, "redstone")
        ExplorerTreeState.expandedPaths shouldNotContain "redstone"

        // A folder with no specs in it is a pure expand/collapse: nothing goes to the server.
        sent.shouldBeEmpty()
    }

    test("clicking a spec-bearing folder both toggles it and loads it as a project") {
        ExplorerTreeState.reset()
        val sent = captureSends()

        onElementClick(specFolder, "adders")

        ExplorerTreeState.expandedPaths shouldContain "adders"
        sent shouldContainExactly listOf(LoadEditorFolderC2S("adders"))
    }

    test("clicking a structure places it and never touches expansion") {
        ExplorerTreeState.reset()
        val sent = captureSends()

        onElementClick(FileNode("clock.nbt", "nbt"), "redstone/clock.nbt")

        ExplorerTreeState.expandedPaths.shouldBeEmpty()
        sent shouldContainExactly listOf(PlaceStructureC2S("redstone/clock.nbt"))
    }

    test("clicking a non-structure file does nothing at all") {
        ExplorerTreeState.reset()
        val sent = captureSends()

        onElementClick(FileNode("notes.txt", "txt"), "notes.txt")

        ExplorerTreeState.expandedPaths.shouldBeEmpty()
        sent.shouldBeEmpty()
    }
})
