package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.client.ide.FolderPicker
import com.breadmoirai.garnet.client.ide.RootPickerController
import com.breadmoirai.garnet.network.project.SetProjectRootC2S
import com.breadmoirai.garnet.testing.ClientSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class RootPickerSpec : ClientSpec({

    afterTest { RootPickerController.resetForTest() }

    test("openFolder sends SetProjectRootC2S and persists the picked path") {
        val sent = mutableListOf<CustomPacketPayload>()
        val persisted = mutableListOf<String>()
        val picked = "/abs/picked"
        // openFolder normalizes to absolute (Path.of(picked).toAbsolutePath()); that resolution
        // is platform-dependent (e.g. Windows anchors a rootless path to the current drive), so
        // assert against the same normalization rather than a hardcoded string.
        val expected = Path.of(picked).toAbsolutePath().toString()
        RootPickerController.picker = FolderPicker { _, _ -> picked }
        RootPickerController.runner = Runnable::run
        RootPickerController.executor = Runnable::run
        RootPickerController.sender = { sent.add(it) }
        RootPickerController.persist = { persisted.add(it) }
        RootPickerController.toggleMenu() // open, then openFolder should close it

        RootPickerController.openFolder()

        sent.filterIsInstance<SetProjectRootC2S>().single().path shouldBe expected
        persisted.single() shouldBe expected
        RootPickerController.picking shouldBe false
        RootPickerController.menuOpen shouldBe false
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
    }
})
