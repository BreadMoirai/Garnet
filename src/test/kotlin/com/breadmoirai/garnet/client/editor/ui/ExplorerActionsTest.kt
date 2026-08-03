package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.ui.ExplorerActions
import com.breadmoirai.garnet.editor.network.CreateFolderC2S
import com.breadmoirai.garnet.editor.network.NewStructureC2S
import com.breadmoirai.garnet.editor.network.RenamePathC2S
import com.breadmoirai.garnet.editor.data.NewNodeKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

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

    afterTest { ExplorerActions.resetForTest() }

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

    test("renaming to a path is rejected") {
        val sent = captureSends()
        ExplorerActions.commitRename("redstone/clock.nbt", "a/b.nbt").shouldNotBeNull()
        sent.shouldBeEmpty()
    }
})
