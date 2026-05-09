package com.breadmoirai.redstonespecs.managed

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class ManagedNewSpecTest : FunSpec({

    test("blank name throws IllegalArgumentException") {
        val tmp = Files.createTempDirectory("managed-new-blank")
        try {
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "   ") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("illegal characters in name throw") {
        val tmp = Files.createTempDirectory("managed-new-illegal")
        try {
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "with space") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "a/b") }
            shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "dot.name") }
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("file already exists throws") {
        val tmp = Files.createTempDirectory("managed-new-exists")
        try {
            tmp.resolve("dup.spec.kts").writeText("// stub")
            val ex = shouldThrow<IllegalArgumentException> { ManagedNewSpec.create(tmp, "dup") }
            ex.message?.shouldContain("already exists")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    test("create writes <name>.spec.kts with stub content") {
        val tmp = Files.createTempDirectory("managed-new-ok")
        try {
            val out = ManagedNewSpec.create(tmp, "fresh")
            out shouldBe tmp.resolve("fresh.spec.kts")
            out.exists() shouldBe true
            // Stub from RecordingDslEmitter.emitStub mentions the spec id.
            out.readText().shouldContain("fresh")
        } finally {
            Files.walk(tmp).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
})
