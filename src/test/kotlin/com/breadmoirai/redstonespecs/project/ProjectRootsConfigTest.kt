package com.breadmoirai.redstonespecs.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class ProjectRootsConfigTest : FunSpec({
    test("save then load roundtrips a list of paths") {
        val tmp = Files.createTempDirectory("project-roots")
        val cfg = tmp.resolve("project-roots.json")
        ProjectRootsConfig.save(cfg, listOf("/a/b", "/c/d"))
        ProjectRootsConfig.load(cfg) shouldBe listOf("/a/b", "/c/d")
    }

    test("load returns empty when file missing") {
        val tmp = Files.createTempDirectory("project-roots")
        ProjectRootsConfig.load(tmp.resolve("missing.json")) shouldBe emptyList()
    }

    test("save creates parent directories if needed") {
        val tmp = Files.createTempDirectory("project-roots")
        val cfg = tmp.resolve("nested/dir/roots.json")
        ProjectRootsConfig.save(cfg, listOf("/x"))
        ProjectRootsConfig.load(cfg) shouldBe listOf("/x")
    }
})
