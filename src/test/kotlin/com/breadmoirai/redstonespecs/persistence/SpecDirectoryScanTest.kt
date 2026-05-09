package com.breadmoirai.redstonespecs.persistence

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

class SpecDirectoryScanTest : FunSpec({
    test("lists only .spec.kts files, sorted") {
        val dir = createTempDirectory("spec-scan-")
        try {
            dir.resolve("z.spec.kts").writeText("// stub")
            dir.resolve("a.spec.kts").writeText("// stub")
            dir.resolve("not-a-spec.txt").writeText("// stub")
            dir.resolve("README.md").writeText("// stub")

            SpecDirectoryScan.list(dir).shouldContainExactly("a.spec.kts", "z.spec.kts")
        } finally {
            // best-effort cleanup
            Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    test("returns empty list when directory does not exist") {
        SpecDirectoryScan.list(java.nio.file.Path.of("/no/such/path/here/at/all")).shouldBe(emptyList())
    }
})
