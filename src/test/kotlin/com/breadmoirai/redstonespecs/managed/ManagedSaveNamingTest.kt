package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Path

class ManagedSaveNamingTest : FunSpec({

    test("preserves alphanumeric tail and appends 8-hex hash") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/specs"))
        name shouldStartWith "managed-specs-"
        name shouldMatch Regex("managed-specs-[0-9a-f]{8}")
    }

    test("non-alphanumeric tail characters become underscores") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/my specs!"))
        name shouldStartWith "managed-my_specs_-"
    }

    test("preserves hyphens and underscores in tail") {
        val name = ManagedSaveNaming.saveName(Path.of("/foo/my-cool_specs"))
        name shouldStartWith "managed-my-cool_specs-"
    }

    test("blank tail falls back to 'root'") {
        val name = ManagedSaveNaming.saveName(Path.of("/"))
        name shouldStartWith "managed-root-"
    }

    test("same tail at different absolute paths produce different hashes") {
        val n1 = ManagedSaveNaming.saveName(Path.of("/a/specs"))
        val n2 = ManagedSaveNaming.saveName(Path.of("/b/specs"))
        n1 shouldStartWith "managed-specs-"
        n2 shouldStartWith "managed-specs-"
        (n1 == n2) shouldBe false
    }

    test("same absolute path produces same name across calls") {
        val p = Path.of("/foo/specs")
        ManagedSaveNaming.saveName(p) shouldBe ManagedSaveNaming.saveName(p)
    }

    test("hash suffix is exactly 8 hex chars") {
        val name = ManagedSaveNaming.saveName(Path.of("/x/y/z"))
        val hash = name.substringAfterLast('-')
        hash shouldMatch Regex("[0-9a-f]{8}")
    }
})
