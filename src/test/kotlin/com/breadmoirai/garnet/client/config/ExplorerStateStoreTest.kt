package com.breadmoirai.garnet.client.config

import com.breadmoirai.garnet.config.ExplorerStateStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class ExplorerStateStoreTest : FunSpec({

    test("a session round-trips through garnet-explorer.json") {
        val dir = createTempDirectory("garnet-explorer")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        try {
            ExplorerStateStore.save("/tmp/proj", setOf("", "adders", "adders/full-adder"), "adders/full-adder")

            val loaded = ExplorerStateStore.load()!!
            loaded.root shouldBe "/tmp/proj"
            loaded.expanded shouldContainExactly setOf("", "adders", "adders/full-adder")
            loaded.selected shouldBe "adders/full-adder"
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a null selection round-trips as null rather than an empty string") {
        val dir = createTempDirectory("garnet-explorer-noselect")
        ExplorerStateStore.configFileForTest(dir.resolve("garnet-explorer.json").toFile())
        try {
            ExplorerStateStore.save("/tmp/proj", setOf(""), null)
            ExplorerStateStore.load()!!.selected.shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a missing file loads as null") {
        val dir = createTempDirectory("garnet-explorer-missing")
        ExplorerStateStore.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a malformed file loads as null instead of throwing") {
        val dir = createTempDirectory("garnet-explorer-malformed")
        val file = dir.resolve("garnet-explorer.json").toFile()
        file.writeText("{ this is not json")
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a record with no root loads as null") {
        val dir = createTempDirectory("garnet-explorer-norder")
        val file = dir.resolve("garnet-explorer.json").toFile()
        file.writeText("""{"expanded":["adders"]}""")
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.load().shouldBeNull()
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("saving with a blank root writes nothing") {
        val dir = createTempDirectory("garnet-explorer-blank")
        val file = dir.resolve("garnet-explorer.json").toFile()
        ExplorerStateStore.configFileForTest(file)
        try {
            ExplorerStateStore.save("", setOf("adders"), null)
            file.exists() shouldBe false
        } finally {
            ExplorerStateStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }
})
