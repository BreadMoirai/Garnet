package com.breadmoirai.garnet.editor.ops

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class EditorNewSpecTest : FunSpec({

    test("blank name throws IllegalArgumentException") {
        val tmp = Files.createTempDirectory("project-new-blank")
        try {
            shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "") }
            shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "   ") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("illegal characters in name throw") {
        val tmp = Files.createTempDirectory("project-new-illegal")
        try {
            shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "with space") }
            shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "a/b") }
            shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "dot.name") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("file already exists throws") {
        val tmp = Files.createTempDirectory("project-new-exists")
        try {
            tmp.resolve("dup.spec.kts").writeText("// stub")
            val ex = shouldThrow<IllegalArgumentException> { EditorNewSpec.create(tmp, "dup") }
            ex.message?.shouldContain("already exists")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("create writes <name>.spec.kts with stub content") {
        val tmp = Files.createTempDirectory("project-new-ok")
        try {
            val out = EditorNewSpec.create(tmp, "fresh")
            out shouldBe tmp.resolve("fresh.spec.kts")
            out.exists() shouldBe true
            // Stub from RecordingDslEmitter.emitStub mentions the spec id.
            out.readText().shouldContain("fresh")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
})
