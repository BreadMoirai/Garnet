package com.breadmoirai.garnet.client.editor.ui

import com.breadmoirai.garnet.editor.network.RevisionEntry
import com.breadmoirai.garnet.editor.network.StructureHistoryS2C
import com.breadmoirai.garnet.editor.history.ui.LocalHistoryState
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * The Local History panel's list model. Pure: no Compose scene, no client — the panel body's
 * rendering is covered by the pixel probe in `JewelExplorerSpec`.
 */
class LocalHistoryStateTest : FunSpec({

    afterTest { LocalHistoryState.reset() }

    fun history(vararg stamps: Long) = StructureHistoryS2C(
        "clock.nbt",
        // The wire order is oldest-first, as LocalHistoryStore returns it.
        stamps.sorted().map { RevisionEntry(it, 1, 1, 1, 1, "autosave") },
    )

    test("revisions are exposed newest first regardless of wire order") {
        LocalHistoryState.onHistory(history(300L, 100L, 200L))

        LocalHistoryState.revisions.map { it.timestampMillis } shouldBe listOf(300L, 200L, 100L)
    }

    test("the newest revision is the current one and is not restorable") {
        LocalHistoryState.onHistory(history(100L, 200L))

        LocalHistoryState.currentTimestamp shouldBe 200L
        LocalHistoryState.isRestorable(200L).shouldBeFalse()
        LocalHistoryState.isRestorable(100L).shouldBeTrue()
    }

    test("an empty list has no current revision and nothing restorable") {
        LocalHistoryState.onHistory(StructureHistoryS2C("clock.nbt", emptyList()))

        LocalHistoryState.revisions.shouldBeEmpty()
        LocalHistoryState.currentTimestamp shouldBe null
        LocalHistoryState.isRestorable(1L).shouldBeFalse()
    }

    test("a selection that no longer exists is dropped when a new list arrives") {
        LocalHistoryState.onHistory(history(100L, 200L))
        LocalHistoryState.select(100L)
        LocalHistoryState.selected shouldBe 100L

        // 100 was pruned away between pushes.
        LocalHistoryState.onHistory(history(200L, 300L))

        LocalHistoryState.selected shouldBe null
    }

    test("selecting the current revision is refused") {
        LocalHistoryState.onHistory(history(100L, 200L))

        LocalHistoryState.select(200L)

        LocalHistoryState.selected shouldBe null
    }
})
