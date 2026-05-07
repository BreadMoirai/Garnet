package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KtsSpecLoaderTest {
    @Test
    fun `loadString parses a minimal spec`() {
        val source = """
            redstoneSpec("simple") {
                bounds(3, 3, 3)
                lifespan = 5
                input(1, 0, 1, label = "in") { atStart { powered() } }
                output(2, 0, 2, label = "out") { at(tick = 4) { lit() } }
            }
        """.trimIndent()

        val spec = KtsSpecLoader.loadString(source)

        assertEquals("simple", spec.id)
        assertEquals(5, spec.lifespan)
        assertEquals(2, spec.entries.size)
        assertEquals(setOf(EntryKind.INPUT, EntryKind.OUTPUT), spec.entries.map { it.kind }.toSet())
    }

    @Test
    fun `loadString surfaces compilation errors`() {
        val source = """redstoneSpec("bad") { not_a_function() }"""
        val ex = runCatching { KtsSpecLoader.loadString(source) }.exceptionOrNull()
        require(ex != null) { "expected exception for invalid script" }
    }
}
