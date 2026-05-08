package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ManagedRootsConfigTest : FunSpec({
    test("save then load roundtrips a list of paths") {
        val tmp = Files.createTempDirectory("managed-roots")
        val cfg = tmp.resolve("managed-roots.json")
        ManagedRootsConfig.save(cfg, listOf("/a/b", "/c/d"))
        ManagedRootsConfig.load(cfg) shouldBe listOf("/a/b", "/c/d")
    }

    test("load returns empty when file missing") {
        val tmp = Files.createTempDirectory("managed-roots")
        ManagedRootsConfig.load(tmp.resolve("missing.json")) shouldBe emptyList()
    }

    test("save creates parent directories if needed") {
        val tmp = Files.createTempDirectory("managed-roots")
        val cfg = tmp.resolve("nested/dir/roots.json")
        ManagedRootsConfig.save(cfg, listOf("/x"))
        ManagedRootsConfig.load(cfg) shouldBe listOf("/x")
    }
})
