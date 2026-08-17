package com.breadmoirai.garnet.dock.data

import com.breadmoirai.garnet.dock.data.DockLayoutStore
import com.breadmoirai.garnet.dock.shell.DockRegion
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The `config/garnet-dock.json` round trip. Every failure path falls back to [DEFAULT_OPEN] rather
 * than propagating: restoring the layout is a convenience, and "open the Explorer" is the wanted
 * behaviour for a fresh install, so it is also the right thing to fall back to when the record
 * cannot be trusted.
 */
class DockLayoutStoreTest : FunSpec({

    fun withStore(name: String, seed: String?, body: (File) -> Unit) {
        val dir = createTempDirectory(name)
        val file = dir.resolve("garnet-dock.json").toFile()
        DockLayoutStore.configFileForTest(file)
        try {
            if (seed != null) file.writeText(seed)
            body(file)
        } finally {
            DockLayoutStore.resetConfigFileForTest()
            dir.toFile().deleteRecursively()
        }
    }

    test("an absent file falls back to the Explorer open in LEFT") {
        withStore("store-absent", seed = null) {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }

    test("a saved map round-trips") {
        withStore("store-roundtrip", seed = null) {
            DockLayoutStore.save(mapOf(DockRegion.LEFT to "garnet.localHistory"))
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.localHistory")
        }
    }

    test("an explicitly empty map round-trips as everything closed") {
        withStore("store-empty", seed = null) {
            DockLayoutStore.save(emptyMap())
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("a legacy leftVisible:true record migrates to the Explorer open") {
        withStore("store-legacy-true", seed = """{"leftVisible":true}""") {
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.explorer")
        }
    }

    test("a legacy leftVisible:false record migrates to everything closed") {
        withStore("store-legacy-false", seed = """{"leftVisible":false}""") {
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("an unknown region key is dropped rather than failing the whole read") {
        withStore("store-bad-region", seed = """{"open":{"SIDEWAYS":"garnet.explorer","LEFT":"garnet.explorer"}}""") {
            DockLayoutStore.load() shouldBe mapOf(DockRegion.LEFT to "garnet.explorer")
        }
    }

    test("a non-string panel id is dropped") {
        withStore("store-bad-id", seed = """{"open":{"LEFT":7}}""") {
            DockLayoutStore.load() shouldBe emptyMap()
        }
    }

    test("malformed JSON falls back to the default") {
        withStore("store-malformed", seed = "{not json") {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }

    test("a file with neither key falls back to the default") {
        withStore("store-no-keys", seed = """{"somethingElse":1}""") {
            DockLayoutStore.load() shouldBe DockLayoutStore.DEFAULT_OPEN
        }
    }
})
