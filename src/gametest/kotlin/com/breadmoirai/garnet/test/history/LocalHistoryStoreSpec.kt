package com.breadmoirai.garnet.test.history

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.history.LocalHistoryStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.nbt.CompoundTag
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText

/**
 * Filesystem-level coverage for the local-history store. Needs no world: every call is pure IO,
 * so these run without an `onServer` block.
 */
class LocalHistoryStoreSpec : GarnetTestSpec({

    /** Point the store at a scratch directory and restore the previous settings afterwards. */
    fun withStore(block: (java.nio.file.Path) -> Unit) {
        val dir = createTempDirectory("garnet-history")
        val prevDir = SharedSettings.localHistoryDir
        val prevDays = SharedSettings.localHistoryDays
        val prevMax = SharedSettings.localHistoryMaxRevisions
        val prevEnabled = SharedSettings.localHistoryEnabled
        SharedSettings.localHistoryDir = dir.toAbsolutePath().toString()
        SharedSettings.localHistoryEnabled = true
        try {
            block(dir)
        } finally {
            SharedSettings.localHistoryDir = prevDir
            SharedSettings.localHistoryDays = prevDays
            SharedSettings.localHistoryMaxRevisions = prevMax
            SharedSettings.localHistoryEnabled = prevEnabled
            dir.toFile().deleteRecursively()
        }
    }

    /** A minimal distinguishable tag — the store never interprets it, only stores it. */
    fun tagWith(marker: String): CompoundTag {
        val tag = CompoundTag()
        tag.putString("garnetTestMarker", marker)
        return tag
    }

    test("a revision is written, indexed, and reads back byte-identical") {
        withStore { _ ->
            val proj = createTempDirectory("proj")
            val file = proj.resolve("clock.nbt")
            file.writeText("placeholder")

            val rev = LocalHistoryStore.writeRevision(
                file, tagWith("first"), 3, 2, 1, 5, LocalHistoryStore.REASON_PLACED, nowMillis = 1_000L,
            ).shouldNotBeNull()

            rev.timestampMillis shouldBe 1_000L
            rev.sizeX shouldBe 3
            rev.sizeY shouldBe 2
            rev.sizeZ shouldBe 1
            rev.blockCount shouldBe 5
            rev.reason shouldBe LocalHistoryStore.REASON_PLACED

            LocalHistoryStore.revisions(file) shouldHaveSize 1
            LocalHistoryStore.readTag(file, rev)!!.getStringOr("garnetTestMarker", "") shouldBe "first"

            proj.toFile().deleteRecursively()
        }
    }

    test("two revisions in the same millisecond get distinct sequence numbers") {
        withStore { _ ->
            val proj = createTempDirectory("proj-seq")
            val file = proj.resolve("clock.nbt")

            val a = LocalHistoryStore.writeRevision(
                file, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 5_000L,
            ).shouldNotBeNull()
            val b = LocalHistoryStore.writeRevision(
                file, tagWith("b"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 5_000L,
            ).shouldNotBeNull()

            a.file shouldNotBe b.file
            LocalHistoryStore.revisions(file) shouldHaveSize 2
            LocalHistoryStore.readTag(file, b)!!.getStringOr("garnetTestMarker", "") shouldBe "b"

            proj.toFile().deleteRecursively()
        }
    }

    test("revisions come back in chronological order regardless of write order") {
        withStore { _ ->
            val proj = createTempDirectory("proj-order")
            val file = proj.resolve("clock.nbt")
            LocalHistoryStore.writeRevision(file, tagWith("late"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 9_000L)
            LocalHistoryStore.writeRevision(file, tagWith("early"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1_000L)
            LocalHistoryStore.revisions(file).map { it.timestampMillis } shouldBe listOf(1_000L, 9_000L)
            proj.toFile().deleteRecursively()
        }
    }

    test("revisions older than localHistoryDays are pruned on the next write") {
        withStore { _ ->
            SharedSettings.localHistoryDays = 5
            SharedSettings.localHistoryMaxRevisions = 100
            val proj = createTempDirectory("proj-age")
            val file = proj.resolve("clock.nbt")
            val now = 100L * 24 * 60 * 60 * 1000  // day 100
            val sixDaysAgo = now - 6L * 24 * 60 * 60 * 1000
            val oneDayAgo = now - 1L * 24 * 60 * 60 * 1000

            LocalHistoryStore.writeRevision(file, tagWith("old"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = sixDaysAgo)
            LocalHistoryStore.writeRevision(file, tagWith("recent"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = oneDayAgo)
            // The write below is what triggers pruning; the 6-day-old entry falls outside the window.
            LocalHistoryStore.writeRevision(file, tagWith("now"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = now)

            val kept = LocalHistoryStore.revisions(file)
            kept shouldHaveSize 2
            kept.map { it.timestampMillis } shouldBe listOf(oneDayAgo, now)
            // The pruned revision's blob is gone from disk too, not merely dropped from the index.
            LocalHistoryStore.dirFor(file).listDirectoryEntries("*.nbt") shouldHaveSize 2

            proj.toFile().deleteRecursively()
        }
    }

    test("revisions beyond localHistoryMaxRevisions are pruned oldest-first") {
        withStore { _ ->
            SharedSettings.localHistoryDays = 3650
            SharedSettings.localHistoryMaxRevisions = 3
            val proj = createTempDirectory("proj-count")
            val file = proj.resolve("clock.nbt")
            repeat(5) { i ->
                LocalHistoryStore.writeRevision(
                    file, tagWith("r$i"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE,
                    nowMillis = 1_000L + i,
                )
            }
            val kept = LocalHistoryStore.revisions(file)
            kept shouldHaveSize 3
            kept.map { it.timestampMillis } shouldBe listOf(1_002L, 1_003L, 1_004L)
            proj.toFile().deleteRecursively()
        }
    }

    test("localHistoryEnabled=false writes nothing") {
        withStore { _ ->
            SharedSettings.localHistoryEnabled = false
            val proj = createTempDirectory("proj-off")
            val file = proj.resolve("clock.nbt")
            LocalHistoryStore.writeRevision(file, tagWith("x"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE) shouldBe null
            LocalHistoryStore.revisions(file) shouldHaveSize 0
            proj.toFile().deleteRecursively()
        }
    }

    test("the same file reached through two project roots resolves to one history") {
        withStore { _ ->
            // The key is the .nbt's own absolute path, so opening the parent as root or the folder
            // itself as root must not fork the history.
            val proj = createTempDirectory("proj-roots")
            val nested = proj.resolve("redstone").createDirectories()
            val file = nested.resolve("clock.nbt")
            val sameFileOtherWay = proj.resolve("redstone").resolve(".").resolve("clock.nbt")

            LocalHistoryStore.keyOf(sameFileOtherWay) shouldBe LocalHistoryStore.keyOf(file)

            LocalHistoryStore.writeRevision(file, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)
            LocalHistoryStore.writeRevision(sameFileOtherWay, tagWith("b"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 2L)
            LocalHistoryStore.revisions(file) shouldHaveSize 2

            proj.toFile().deleteRecursively()
        }
    }

    test("path normalization lowercases on Windows only") {
        val upper = java.nio.file.Path.of("C:/Repo/Clock.nbt")
        LocalHistoryStore.normalizePath(upper, windows = true) shouldBe
            LocalHistoryStore.normalizePath(java.nio.file.Path.of("c:/repo/clock.nbt"), windows = true)
        LocalHistoryStore.normalizePath(upper, windows = false) shouldNotBe
            LocalHistoryStore.normalizePath(java.nio.file.Path.of("c:/repo/clock.nbt"), windows = false)
    }

    test("moveHistory relocates a structure's revisions onto its new path") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)

            LocalHistoryStore.moveHistory(from, to)

            LocalHistoryStore.revisions(from) shouldHaveSize 0
            LocalHistoryStore.revisions(to) shouldHaveSize 1
            LocalHistoryStore.dirFor(from).exists() shouldBe false

            proj.toFile().deleteRecursively()
        }
    }

    test("moveHistory onto a path that already has history merges chronologically") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move-merge")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("from"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)
            LocalHistoryStore.writeRevision(to, tagWith("to"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 2L)

            LocalHistoryStore.moveHistory(from, to)

            LocalHistoryStore.revisions(to).map { it.timestampMillis } shouldBe listOf(1L, 2L)
            proj.toFile().deleteRecursively()
        }
    }
})
