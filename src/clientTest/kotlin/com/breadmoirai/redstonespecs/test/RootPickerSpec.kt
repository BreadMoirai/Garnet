package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.ide.FolderPicker
import com.breadmoirai.redstonespecs.client.ide.RootPickerController
import com.breadmoirai.redstonespecs.network.project.SetProjectRootC2S
import com.breadmoirai.redstonespecs.testing.ClientSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class RootPickerSpec : ClientSpec({

    test("openFolder sends SetProjectRootC2S and persists the picked path") {
        val sent = mutableListOf<CustomPacketPayload>()
        val persisted = mutableListOf<String>()
        RootPickerController.picker = FolderPicker { _, _ -> "/abs/picked" }
        RootPickerController.runner = Runnable::run
        RootPickerController.executor = Runnable::run
        RootPickerController.sender = { sent.add(it) }
        RootPickerController.persist = { persisted.add(it) }
        RootPickerController.toggleMenu() // open, then openFolder should close it

        RootPickerController.openFolder()

        sent.filterIsInstance<SetProjectRootC2S>().single().path shouldBe "/abs/picked"
        persisted.single() shouldBe "/abs/picked"
        RootPickerController.picking shouldBe false
        RootPickerController.menuOpen shouldBe false

        RootPickerController.resetForTest()
    }

    test("openFolder sends nothing when the picker is cancelled") {
        val sent = mutableListOf<CustomPacketPayload>()
        RootPickerController.picker = FolderPicker { _, _ -> null }
        RootPickerController.runner = Runnable::run
        RootPickerController.executor = Runnable::run
        RootPickerController.sender = { sent.add(it) }
        RootPickerController.persist = { }

        RootPickerController.openFolder()

        sent.shouldBeEmpty()
        RootPickerController.picking shouldBe false

        RootPickerController.resetForTest()
    }
})
