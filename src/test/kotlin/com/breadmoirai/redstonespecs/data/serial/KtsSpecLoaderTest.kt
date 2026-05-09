package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.Vec3i

class KtsSpecLoaderTest : FunSpec({

    test("loadRedstoneSpec returns a dsl.RedstoneSpec from new-style source") {
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*
            import net.minecraft.core.Vec3i

            redstoneSpec(id = "loader-1", bounds = Vec3i(2, 2, 2), lifespan = 4) {}
        """.trimIndent()
        val spec = KtsSpecLoader.loadRedstoneSpec(source, name = "loader-1.spec.kts")
        spec.shouldBeInstanceOf<RedstoneSpec>()
        spec.id shouldBe "loader-1"
    }

    test("loadRedstoneSpec extracts id and lifespan from new-style source") {
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*
            import net.minecraft.core.Vec3i

            redstoneSpec(id = "loader-2", bounds = Vec3i(3, 3, 3), lifespan = 6) {}
        """.trimIndent()
        val extracted = KtsSpecLoader.loadRedstoneSpec(source, name = "loader-2.spec.kts")
        extracted.id shouldBe "loader-2"
        extracted.lifespan shouldBe 6
    }

    test("loadRedstoneSpec parses a minimal spec") {
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*
            import net.minecraft.core.Vec3i

            redstoneSpec(id = "with-entries", bounds = Vec3i(3, 3, 3), lifespan = 5) {}
        """.trimIndent()
        val spec = KtsSpecLoader.loadRedstoneSpec(source)
        spec.id shouldBe "with-entries"
        spec.lifespan shouldBe 5
    }

    test("loadRedstoneSpec surfaces compilation errors") {
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*

            not_a_function()
        """.trimIndent()
        val ex = runCatching { KtsSpecLoader.loadRedstoneSpec(source) }.exceptionOrNull()
        ex shouldNotBe null
    }
})
