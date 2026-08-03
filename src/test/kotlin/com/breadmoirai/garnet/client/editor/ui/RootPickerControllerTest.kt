package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.ui.FolderPicker
import com.breadmoirai.garnet.editor.ui.RootPickerController
import com.breadmoirai.garnet.editor.network.SetEditorRootC2S
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Path
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class RootPickerControllerTest : FunSpec({

    afterTest { RootPickerController.resetForTest() }

    test("openFolder sends SetEditorRootC2S and persists the picked path") {
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

        RootPickerController.openFolder()

        sent.filterIsInstance<SetEditorRootC2S>().single().path shouldBe expected
        persisted.single() shouldBe expected
        RootPickerController.picking shouldBe false
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

    test("openFolder seeds the picker's default from the configured project root") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            var capturedDefault: String? = "unset"
            RootPickerController.picker = FolderPicker { _, default -> capturedDefault = default; null }
            RootPickerController.runner = Runnable::run
            RootPickerController.executor = Runnable::run
            RootPickerController.sender = { }
            RootPickerController.persist = { }

            RootPickerController.openFolder()

            capturedDefault shouldBe "/tmp/proj"
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("openFolder passes null as the picker's default when no root is configured") {
        val prior = SharedSettings.projectRootPath
        try {
            SharedSettings.projectRootPath = ""
            var capturedDefault: String? = "unset"
            RootPickerController.picker = FolderPicker { _, default -> capturedDefault = default; null }
            RootPickerController.runner = Runnable::run
            RootPickerController.executor = Runnable::run
            RootPickerController.sender = { }
            RootPickerController.persist = { }

            RootPickerController.openFolder()

            capturedDefault.shouldBeNull()
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }
})
