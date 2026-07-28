package com.breadmoirai.garnet.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path

class ProjectSaveNamingTest : FunSpec({

    test("preserves alphanumeric tail and appends 8-hex hash") {
        val name = ProjectSaveNaming.saveName(Path.of("/foo/specs"))
        name shouldStartWith "project-specs-"
        name shouldMatch Regex("project-specs-[0-9a-f]{8}")
    }

    test("non-alphanumeric tail characters become underscores") {
        val name = ProjectSaveNaming.saveName(Path.of("/foo/my specs!"))
        name shouldStartWith "project-my_specs_-"
    }

    test("preserves hyphens and underscores in tail") {
        val name = ProjectSaveNaming.saveName(Path.of("/foo/my-cool_specs"))
        name shouldStartWith "project-my-cool_specs-"
    }

    test("blank tail falls back to 'root'") {
        val name = ProjectSaveNaming.saveName(Path.of("/"))
        name shouldStartWith "project-root-"
    }

    test("same tail at different absolute paths produce different hashes") {
        val n1 = ProjectSaveNaming.saveName(Path.of("/a/specs"))
        val n2 = ProjectSaveNaming.saveName(Path.of("/b/specs"))
        n1 shouldStartWith "project-specs-"
        n2 shouldStartWith "project-specs-"
        (n1 == n2) shouldBe false
    }

    test("same absolute path produces same name across calls") {
        val p = Path.of("/foo/specs")
        ProjectSaveNaming.saveName(p) shouldBe ProjectSaveNaming.saveName(p)
    }

    test("hash suffix is exactly 8 hex chars") {
        val name = ProjectSaveNaming.saveName(Path.of("/x/y/z"))
        val hash = name.substringAfterLast('-')
        hash shouldMatch Regex("[0-9a-f]{8}")
    }
})
