package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.ui.ExplorerTreeState
import com.breadmoirai.garnet.editor.ui.ProjectTreeState
import com.breadmoirai.garnet.editor.network.StructureResultS2C
import com.breadmoirai.garnet.editor.network.StructureAutoSavedS2C
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StructureExplorerStatusTest : FunSpec({
    test("onStructureResult surfaces the message as Explorer status") {
        ProjectTreeState.reset()
        ExplorerTreeState.reset()
        ProjectTreeState.onStructureResult(
            StructureResultS2C("a/box.nbt", 2, 1, 3, message = "placed a/box.nbt"),
        )
        ProjectTreeState.status shouldBe "placed a/box.nbt"
    }

    test("an auto-save result lands in the Explorer status line") {
        ProjectTreeState.reset()
        ProjectTreeState.onAutoSaved(
            StructureAutoSavedS2C("redstone/clock.nbt", 5, 3, 7, 42, savedAtMillis = 1_700_000_000_000L),
        )
        ProjectTreeState.status shouldBe "auto-saved redstone/clock.nbt (5×3×7, 42 blocks)"
    }
})
