package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class KtsSpecLoaderTest : FunSpec({

    test("loadString parses a minimal spec") {
        val source = """
            redstoneSpec("simple") {
                bounds(3, 3, 3)
                lifespan = 5
                input(1, 0, 1, label = "in") { atStart { powered() } }
                output(2, 0, 2, label = "out") { at(tick = 4) { lit() } }
            }
        """.trimIndent()

        val spec = KtsSpecLoader.loadString(source)

        spec.id shouldBe "simple"
        spec.lifespan shouldBe 5
        spec.entries.size shouldBe 2
        spec.entries.map { it.kind }.toSet() shouldBe setOf(EntryKind.INPUT, EntryKind.OUTPUT)
    }

    test("loadString surfaces compilation errors") {
        val source = """redstoneSpec("bad") { not_a_function() }"""
        val ex = runCatching { KtsSpecLoader.loadString(source) }.exceptionOrNull()
        ex shouldNotBe null
    }
})
