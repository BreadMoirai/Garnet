package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.config.ModConfig
import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.harness.ClientSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ModConfigSpec : ClientSpec({

    test("every setting round-trips through garnet.json") {
        val dir = createTempDirectory("garnet-config")
        val file = dir.resolve("garnet.json").toFile()
        ModConfig.configFileForTest(file)
        try {
            SharedSettings.projectRootPath = "/tmp/proj"
            SharedSettings.autoSaveEnabled = false
            SharedSettings.autoSaveDebounceTicks = 7
            SharedSettings.autoSaveMaxDirtyTicks = 77
            SharedSettings.localHistoryEnabled = false
            SharedSettings.localHistoryDays = 3
            SharedSettings.localHistoryMaxRevisions = 9
            SharedSettings.localHistoryDir = "/tmp/hist"
            SharedSettings.structureRegionChunks = 2
            ModConfig.save()

            // Clobber every field, then reload: each must come back from disk.
            SharedSettings.projectRootPath = ""
            SharedSettings.autoSaveEnabled = true
            SharedSettings.autoSaveDebounceTicks = 20
            SharedSettings.autoSaveMaxDirtyTicks = 600
            SharedSettings.localHistoryEnabled = true
            SharedSettings.localHistoryDays = 5
            SharedSettings.localHistoryMaxRevisions = 100
            SharedSettings.localHistoryDir = ""
            SharedSettings.structureRegionChunks = 9
            ModConfig.load()

            SharedSettings.projectRootPath shouldBe "/tmp/proj"
            SharedSettings.autoSaveEnabled shouldBe false
            SharedSettings.autoSaveDebounceTicks shouldBe 7
            SharedSettings.autoSaveMaxDirtyTicks shouldBe 77
            SharedSettings.localHistoryEnabled shouldBe false
            SharedSettings.localHistoryDays shouldBe 3
            SharedSettings.localHistoryMaxRevisions shouldBe 9
            SharedSettings.localHistoryDir shouldBe "/tmp/hist"
            SharedSettings.structureRegionChunks shouldBe 2
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a missing config file leaves defaults untouched") {
        val dir = createTempDirectory("garnet-config-missing")
        ModConfig.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            SharedSettings.autoSaveDebounceTicks = 20
            ModConfig.load()
            SharedSettings.autoSaveDebounceTicks shouldBe 20
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a config file missing a key keeps that setting's current value") {
        val dir = createTempDirectory("garnet-config-partial")
        val file = dir.resolve("garnet.json").toFile()
        file.writeText("""{"projectRootPath":"/only/this"}""")
        ModConfig.configFileForTest(file)
        try {
            SharedSettings.localHistoryDays = 42
            ModConfig.load()
            SharedSettings.projectRootPath shouldBe "/only/this"
            SharedSettings.localHistoryDays shouldBe 42
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }
})
