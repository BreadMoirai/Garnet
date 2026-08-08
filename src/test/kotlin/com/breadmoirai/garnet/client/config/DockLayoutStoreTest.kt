package com.breadmoirai.garnet.client.config

import com.breadmoirai.garnet.config.DockLayoutStore
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.io.path.createTempDirectory

class DockLayoutStoreTest : FunSpec({

    /** Runs [body] with the store redirected at a fresh temp `garnet-dock.json`. */
    fun withStore(name: String, body: (java.io.File) -> Unit) {
        val dir = createTempDirectory(name)
        val file = dir.resolve("garnet-dock.json").toFile()
        DockLayoutStore.configFileForTest(file)
        try {
            body(file)
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a visible dock round-trips through garnet-dock.json") {
        withStore("garnet-dock-visible") {
            DockLayoutStore.save(true)
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a hidden dock round-trips as false rather than defaulting back to open") {
        withStore("garnet-dock-hidden") {
            DockLayoutStore.save(false)
            DockLayoutStore.load() shouldBe false
        }
    }

    test("a missing file loads as true so a fresh install still auto-opens") {
        val dir = createTempDirectory("garnet-dock-missing")
        DockLayoutStore.configFileForTest(dir.resolve("absent.json").toFile())
        try {
            DockLayoutStore.load() shouldBe true
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("a malformed file loads as true instead of throwing") {
        withStore("garnet-dock-malformed") { file ->
            file.writeText("{ this is not json")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a record with no leftVisible key loads as true") {
        withStore("garnet-dock-nokey") { file ->
            file.writeText("""{"somethingElse":1}""")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("a non-boolean leftVisible loads as true instead of throwing") {
        withStore("garnet-dock-badtype") { file ->
            file.writeText("""{"leftVisible":{"nested":true}}""")
            DockLayoutStore.load() shouldBe true
        }
    }

    test("save overwrites a previous record rather than appending") {
        withStore("garnet-dock-overwrite") {
            DockLayoutStore.save(true)
            DockLayoutStore.save(false)
            DockLayoutStore.load() shouldBe false
        }
    }
})
