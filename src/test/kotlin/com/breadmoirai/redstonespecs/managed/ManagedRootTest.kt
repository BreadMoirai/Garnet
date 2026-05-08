package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

class ManagedRootTest : FunSpec({

    test("resolveSubpath returns child when inside root") {
        val tmp = Files.createTempDirectory("managed-root-child")
        val root = ManagedRoot(tmp)
        tmp.resolve("a/b").createDirectories()
        val resolved = root.resolveSubpath("a/b")
        resolved?.toRealPath() shouldBe tmp.resolve("a/b").toRealPath()
    }

    test("resolveSubpath returns root itself for empty subpath") {
        val tmp = Files.createTempDirectory("managed-root-empty")
        val root = ManagedRoot(tmp)
        // Empty subpath = the root itself; allowed (used for tree listing).
        root.resolveSubpath("")?.toRealPath() shouldBe tmp.toRealPath()
    }

    test("resolveSubpath rejects parent traversal") {
        val tmp = Files.createTempDirectory("managed-root-traversal")
        val inner = tmp.resolve("inner").also { it.createDirectories() }
        val root = ManagedRoot(inner)
        tmp.resolve("escape.txt").createFile()
        root.resolveSubpath("../escape.txt").shouldBeNull()
    }

    test("resolveSubpath rejects absolute subpath") {
        val tmp = Files.createTempDirectory("managed-root-absolute")
        val root = ManagedRoot(tmp)
        root.resolveSubpath("/etc/passwd").shouldBeNull()
    }

    test("resolveSubpath returns null for non-existent") {
        val tmp = Files.createTempDirectory("managed-root-missing")
        val root = ManagedRoot(tmp)
        root.resolveSubpath("nope").shouldBeNull()
    }
})
