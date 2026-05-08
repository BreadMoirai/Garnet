package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf

class KtsSpecLoaderTest : FunSpec({

    test("loadSpec returns a Spec class extending RedstoneTestSpec") {
        val source = KtsSpecEmitter.emit(redstoneSpec("loader-1") { bounds(2, 2, 2); lifespan = 4 })
        val klass = KtsSpecLoader.loadSpec(source, name = "loader-1.spec.kts")
        // Instantiate to confirm the class is well-formed
        val instance = klass.java.getDeclaredConstructor().newInstance()
        instance.shouldBeInstanceOf<RedstoneTestSpec>()
    }

    test("loadRedstoneSpec extracts the inner RedstoneSpec value for editor consumers") {
        val original = redstoneSpec("loader-2") { bounds(3, 3, 3); lifespan = 6 }
        val source = KtsSpecEmitter.emit(original)
        val extracted = KtsSpecLoader.loadRedstoneSpec(source, name = "loader-2.spec.kts")
        extracted.id shouldBe "loader-2"
        extracted.lifespan shouldBe 6
    }

    test("loadRedstoneSpec parses a spec with entries") {
        val source = KtsSpecEmitter.emit(
            redstoneSpec("with-entries") {
                bounds(3, 3, 3)
                lifespan = 5
            }
        )
        val spec = KtsSpecLoader.loadRedstoneSpec(source)
        spec.id shouldBe "with-entries"
        spec.lifespan shouldBe 5
    }

    test("loadSpec surfaces compilation errors") {
        val source = """
            class BadSpec : RedstoneTestSpec({
                not_a_function()
            })
        """.trimIndent()
        val ex = runCatching { KtsSpecLoader.loadSpec(source) }.exceptionOrNull()
        ex shouldNotBe null
    }
})
