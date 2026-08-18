package com.breadmoirai.garnet.editor.structure.ui

import com.breadmoirai.garnet.editor.explorer.network.EditorErrorS2C
import com.breadmoirai.garnet.editor.structure.network.StructureAutoSavedS2C
import com.breadmoirai.garnet.editor.structure.network.StructureResultS2C
import com.breadmoirai.garnet.editor.structure.ui.StructureInfoState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Which packet lands which field. This replaced `StructureExplorerStatusTest`, which asserted on the
 * pre-baked `ExplorerTreeSnapshot.status` string — the panel needs the numbers, not a sentence.
 */
class StructureInfoStateTest : FunSpec({

    afterTest { StructureInfoState.reset() }

    test("a place result fills the subpath, the sizes and the status") {
        StructureInfoState.reset()
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        StructureInfoState.subpath shouldBe "a/box.nbt"
        StructureInfoState.sizeX shouldBe 2
        StructureInfoState.sizeY shouldBe 1
        StructureInfoState.sizeZ shouldBe 3
        StructureInfoState.status shouldBe "placed a/box.nbt"
    }

    test("a place result leaves the block count and save time unknown") {
        StructureInfoState.reset()
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
    }

    test("an auto-save fills every field from the payload, save time included") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.subpath shouldBe "redstone/clock.nbt"
        StructureInfoState.sizeX shouldBe 5
        StructureInfoState.sizeY shouldBe 3
        StructureInfoState.sizeZ shouldBe 7
        StructureInfoState.blockCount shouldBe 42
        StructureInfoState.lastSavedMillis shouldBe 1_700_000_000_000L
    }

    test("placing a different structure clears the previous one's block count and save time") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        // Carrying 42 blocks and the clock's save time under box.nbt's name would be a lie.
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
        StructureInfoState.sizeX shouldBe 2
    }

    test("an error writes only the status, leaving the structure facts intact") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        StructureInfoState.onError(EditorErrorS2C("bad name"))
        StructureInfoState.status shouldBe "error: bad name"
        StructureInfoState.subpath shouldBe "redstone/clock.nbt"
        StructureInfoState.blockCount shouldBe 42
    }

    test("reset returns every field to its no-structure sentinel") {
        StructureInfoState.reset()
        StructureInfoState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1L),
        )
        StructureInfoState.reset()
        StructureInfoState.subpath shouldBe null
        StructureInfoState.blockCount shouldBe -1
        StructureInfoState.lastSavedMillis shouldBe 0L
        StructureInfoState.status shouldBe ""
    }
})
