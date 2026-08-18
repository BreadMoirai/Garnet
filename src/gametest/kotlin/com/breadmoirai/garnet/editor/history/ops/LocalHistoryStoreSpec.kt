package com.breadmoirai.garnet.editor.history.ops

import com.breadmoirai.garnet.core.config.SharedSettings
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.editor.history.data.LocalHistoryStore
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.nbt.CompoundTag
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.moveTo
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

    /**
     * Like [withStore], but also hands the block a scratch project directory to resolve test files
     * in — the history root and the "project" a structure file lives under must be separate
     * directories, same as every other test in this file (`withStore` + a sibling `createTempDirectory`
     * call), so a raw-revision test can just do `dir.resolve("clock.spec.kts")`.
     */
    fun withHistoryDir(block: (java.nio.file.Path) -> Unit) {
        withStore { _ ->
            val proj = createTempDirectory("proj-raw")
            try {
                block(proj)
            } finally {
                proj.toFile().deleteRecursively()
            }
        }
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

    test("a pruning write leaves every indexed revision readable and no temp litter behind") {
        withStore { _ ->
            // The index must never reference a blob that has already been deleted, and index.json
            // must never be left half-written. writeIndex writes via a temp file + ATOMIC_MOVE, and
            // pruned blobs are deleted only AFTER that index write lands — so at every observable
            // point the directory is self-consistent.
            SharedSettings.localHistoryDays = 3650
            SharedSettings.localHistoryMaxRevisions = 3
            val proj = createTempDirectory("proj-consistent")
            val file = proj.resolve("clock.nbt")
            repeat(6) { i ->
                LocalHistoryStore.writeRevision(
                    file, tagWith("r$i"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE,
                    nowMillis = 2_000L + i,
                )
            }

            val kept = LocalHistoryStore.revisions(file)
            kept shouldHaveSize 3
            // Every revision the index still names resolves to a real, readable blob.
            for (revision in kept) {
                LocalHistoryStore.readTag(file, revision).shouldNotBeNull()
                    .getStringOr("garnetTestMarker", "") shouldNotBe ""
            }
            val dir = LocalHistoryStore.dirFor(file)
            // No orphan blobs, and no abandoned index temp file.
            dir.listDirectoryEntries("*.nbt") shouldHaveSize 3
            dir.listDirectoryEntries("*.tmp-*") shouldHaveSize 0

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

    // --- Fix round 1 regressions ------------------------------------------------------------

    test("moveHistory leaves a revision that fails to move intact at the source") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move-fail")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)

            // Real OS-level failure induction (locked files, read-only dirs) doesn't behave
            // uniformly across platforms, so `move` is a testing seam: simulate every move failing.
            LocalHistoryStore.moveHistory(from, to) { _, _ -> throw java.io.IOException("simulated move failure") }

            // The blob never left the source: history must stay in place, not be silently dropped.
            LocalHistoryStore.revisions(from) shouldHaveSize 1
            LocalHistoryStore.revisions(from).single().timestampMillis shouldBe 1L
            LocalHistoryStore.dirFor(from).exists() shouldBe true
            LocalHistoryStore.revisions(to) shouldHaveSize 0

            proj.toFile().deleteRecursively()
        }
    }

    test("moveHistory partial failure keeps only the failed revisions at the source and moves the rest") {
        withStore { _ ->
            val proj = createTempDirectory("proj-move-partial")
            val from = proj.resolve("clock.nbt")
            val to = proj.resolve("ring.nbt")
            LocalHistoryStore.writeRevision(from, tagWith("a"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L)
            LocalHistoryStore.writeRevision(from, tagWith("b"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 2L)
            val failingFile = LocalHistoryStore.revisions(from).first { it.timestampMillis == 1L }.file

            LocalHistoryStore.moveHistory(from, to) { source, target ->
                if (source.fileName.toString() == failingFile) throw java.io.IOException("simulated move failure")
                source.moveTo(target)
            }

            // Only the revision whose move failed stays at the source; the other one relocated.
            LocalHistoryStore.revisions(from).map { it.timestampMillis } shouldBe listOf(1L)
            LocalHistoryStore.revisions(to).map { it.timestampMillis } shouldBe listOf(2L)
            LocalHistoryStore.dirFor(from).exists() shouldBe true

            proj.toFile().deleteRecursively()
        }
    }

    test("writeRevision returns null and leaves no orphan blob when the index write fails") {
        withStore { _ ->
            val proj = createTempDirectory("proj-index-fail")
            val file = proj.resolve("clock.nbt")

            // Occupy index.json's path with a directory so the eventual writeText(...) fails with
            // an IOException, deterministically and portably (no locking/permission tricks needed).
            val dir = LocalHistoryStore.dirFor(file)
            dir.createDirectories()
            dir.resolve("index.json").createDirectory()

            val result = LocalHistoryStore.writeRevision(
                file, tagWith("x"), 1, 1, 1, 1, LocalHistoryStore.REASON_AUTOSAVE, nowMillis = 1L,
            )

            result shouldBe null
            // The blob write succeeded before the index failed; it must not be left as an orphan
            // that revisions() can never report.
            dir.listDirectoryEntries("*.nbt") shouldHaveSize 0

            proj.toFile().deleteRecursively()
        }
    }

    test("writeRawRevision round-trips arbitrary bytes") {
        withHistoryDir { dir ->
            val file = dir.resolve("clock.spec.kts")
            val bytes = "spec { name = \"clock\" }".toByteArray()
            val rev = LocalHistoryStore.writeRawRevision(file, bytes, LocalHistoryStore.REASON_PRE_DELETE)
            rev.shouldNotBeNull()
            rev.reason shouldBe "pre-delete"
            LocalHistoryStore.readRawBytes(file, rev) shouldBe bytes
        }
    }

    test("readRawBytes returns null for a typed structure revision") {
        withHistoryDir { dir ->
            val file = dir.resolve("gadget.nbt")
            val tag = CompoundTag().also { it.putString("garnetTestMarker", "typed") }
            val rev = LocalHistoryStore.writeRevision(file, tag, 1, 2, 3, blockCount = 4, reason = LocalHistoryStore.REASON_MANUAL)
            rev.shouldNotBeNull()
            // A typed revision must not be mistaken for a raw one — restore picks its write path
            // off exactly this distinction, and a wrong answer writes a wrapper tag over a .nbt.
            LocalHistoryStore.readRawBytes(file, rev).shouldBeNull()
        }
    }

    test("raw and typed revisions coexist in one index") {
        withHistoryDir { dir ->
            val file = dir.resolve("mixed.nbt")
            val tag = CompoundTag().also { it.putString("garnetTestMarker", "typed") }
            LocalHistoryStore.writeRevision(file, tag, 1, 1, 1, blockCount = 1, reason = LocalHistoryStore.REASON_MANUAL)
            LocalHistoryStore.writeRawRevision(file, byteArrayOf(1, 2, 3), LocalHistoryStore.REASON_PRE_DELETE)
            LocalHistoryStore.revisions(file) shouldHaveSize 2
        }
    }

    test("a raw revision records zero size and zero block count") {
        withHistoryDir { dir ->
            val file = dir.resolve("notes.txt")
            val rev = LocalHistoryStore.writeRawRevision(file, byteArrayOf(7), LocalHistoryStore.REASON_PRE_DELETE)
            rev.shouldNotBeNull()
            rev.sizeX shouldBe 0
            rev.sizeY shouldBe 0
            rev.sizeZ shouldBe 0
            rev.blockCount shouldBe 0
        }
    }

    test("a restore revision round-trips its reason") {
        withHistoryDir { dir ->
            val file = dir.resolve("clock.nbt")
            val tag = CompoundTag()
            tag.putString("marker", "restored")

            LocalHistoryStore.writeRevision(
                file, tag, 1, 2, 3, blockCount = 7, reason = LocalHistoryStore.REASON_RESTORE,
            ).shouldNotBeNull()

            val revisions = LocalHistoryStore.revisions(file)
            revisions shouldHaveSize 1
            revisions.last().reason shouldBe "restore"
            revisions.last().blockCount shouldBe 7
            LocalHistoryStore.readTag(file, revisions.last())!!.getStringOr("marker", "") shouldBe "restored"
        }
    }
})
