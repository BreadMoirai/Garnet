package com.breadmoirai.garnet.core.config

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

/**
 * Snapshot of every [SharedSettings] field this spec mutates (F8) — this spec deliberately
 * clobbers global mutable state to exercise the save/load round trip, so each test must restore
 * it in `finally`, the same way the gametest specs do. Without this, a test that only fails
 * partway through (or a later spec sharing the same JVM) inherits whatever this spec last left
 * behind: `autoSaveEnabled=false`, `projectRootPath="/only/this"`, `localHistoryDir="/tmp/hist"`,
 * `structureRegionChunks=2`, etc.
 */
private data class SettingsSnapshot(
    val projectRootPath: String = SharedSettings.projectRootPath,
    val autoSaveEnabled: Boolean = SharedSettings.autoSaveEnabled,
    val autoSaveDebounceTicks: Int = SharedSettings.autoSaveDebounceTicks,
    val autoSaveMaxDirtyTicks: Int = SharedSettings.autoSaveMaxDirtyTicks,
    val localHistoryEnabled: Boolean = SharedSettings.localHistoryEnabled,
    val localHistoryDays: Int = SharedSettings.localHistoryDays,
    val localHistoryMaxRevisions: Int = SharedSettings.localHistoryMaxRevisions,
    val localHistoryDir: String = SharedSettings.localHistoryDir,
    val structureRegionChunks: Int = SharedSettings.structureRegionChunks,
    val newStructurePlatformBlock: String = SharedSettings.newStructurePlatformBlock,
    val newStructurePlatformWidth: Int = SharedSettings.newStructurePlatformWidth,
    val newStructurePlatformDepth: Int = SharedSettings.newStructurePlatformDepth,
) {
    fun restore() {
        SharedSettings.projectRootPath = projectRootPath
        SharedSettings.autoSaveEnabled = autoSaveEnabled
        SharedSettings.autoSaveDebounceTicks = autoSaveDebounceTicks
        SharedSettings.autoSaveMaxDirtyTicks = autoSaveMaxDirtyTicks
        SharedSettings.localHistoryEnabled = localHistoryEnabled
        SharedSettings.localHistoryDays = localHistoryDays
        SharedSettings.localHistoryMaxRevisions = localHistoryMaxRevisions
        SharedSettings.localHistoryDir = localHistoryDir
        SharedSettings.structureRegionChunks = structureRegionChunks
        SharedSettings.newStructurePlatformBlock = newStructurePlatformBlock
        SharedSettings.newStructurePlatformWidth = newStructurePlatformWidth
        SharedSettings.newStructurePlatformDepth = newStructurePlatformDepth
    }
}

class ModConfigTest : FunSpec({

    test("every setting round-trips through garnet.json") {
        val dir = createTempDirectory("garnet-config")
        val file = dir.resolve("garnet.json").toFile()
        ModConfig.configFileForTest(file)
        val snapshot = SettingsSnapshot()
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
            SharedSettings.newStructurePlatformBlock = "minecraft:gold_block"
            SharedSettings.newStructurePlatformWidth = 5
            SharedSettings.newStructurePlatformDepth = 7
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
            SharedSettings.newStructurePlatformBlock = "minecraft:smooth_stone"
            SharedSettings.newStructurePlatformWidth = 3
            SharedSettings.newStructurePlatformDepth = 3
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
            SharedSettings.newStructurePlatformBlock shouldBe "minecraft:gold_block"
            SharedSettings.newStructurePlatformWidth shouldBe 5
            SharedSettings.newStructurePlatformDepth shouldBe 7
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
            snapshot.restore()
        }
    }

    test("a missing config file leaves defaults untouched") {
        val dir = createTempDirectory("garnet-config-missing")
        ModConfig.configFileForTest(dir.resolve("absent.json").toFile())
        val snapshot = SettingsSnapshot()
        try {
            SharedSettings.autoSaveDebounceTicks = 20
            ModConfig.load()
            SharedSettings.autoSaveDebounceTicks shouldBe 20
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
            snapshot.restore()
        }
    }

    test("a config file missing a key keeps that setting's current value") {
        val dir = createTempDirectory("garnet-config-partial")
        val file = dir.resolve("garnet.json").toFile()
        file.writeText("""{"projectRootPath":"/only/this"}""")
        ModConfig.configFileForTest(file)
        val snapshot = SettingsSnapshot()
        try {
            SharedSettings.localHistoryDays = 42
            ModConfig.load()
            SharedSettings.projectRootPath shouldBe "/only/this"
            SharedSettings.localHistoryDays shouldBe 42
        } finally {
            ModConfig.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
            snapshot.restore()
        }
    }
})
