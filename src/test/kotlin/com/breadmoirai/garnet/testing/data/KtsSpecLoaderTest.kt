package com.breadmoirai.garnet.testing.data

import com.breadmoirai.garnet.testing.data.KtsSpecLoader
import com.breadmoirai.garnet.core.spec.GarnetSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import net.minecraft.core.Vec3i

class KtsSpecLoaderTest : FunSpec({

    test("loadGarnetSpec returns a spec.GarnetSpec from new-style source") {
        val source = """
            import com.breadmoirai.garnet.core.spec.*
            import net.minecraft.core.Vec3i

            garnetSpec(id = "loader-1", bounds = Vec3i(2, 2, 2), lifespan = 4) {}
        """.trimIndent()
        val spec = KtsSpecLoader.loadGarnetSpec(source, name = "loader-1.spec.kts")
        spec.shouldBeInstanceOf<GarnetSpec>()
        spec.id shouldBe "loader-1"
    }

    test("loadGarnetSpec extracts id and lifespan from new-style source") {
        val source = """
            import com.breadmoirai.garnet.core.spec.*
            import net.minecraft.core.Vec3i

            garnetSpec(id = "loader-2", bounds = Vec3i(3, 3, 3), lifespan = 6) {}
        """.trimIndent()
        val extracted = KtsSpecLoader.loadGarnetSpec(source, name = "loader-2.spec.kts")
        extracted.id shouldBe "loader-2"
        extracted.lifespan shouldBe 6
    }

    test("loadGarnetSpec parses a minimal spec") {
        val source = """
            import com.breadmoirai.garnet.core.spec.*
            import net.minecraft.core.Vec3i

            garnetSpec(id = "with-entries", bounds = Vec3i(3, 3, 3), lifespan = 5) {}
        """.trimIndent()
        val spec = KtsSpecLoader.loadGarnetSpec(source)
        spec.id shouldBe "with-entries"
        spec.lifespan shouldBe 5
    }

    test("loadGarnetSpec surfaces compilation errors") {
        val source = """
            import com.breadmoirai.garnet.core.spec.*

            not_a_function()
        """.trimIndent()
        val ex = runCatching { KtsSpecLoader.loadGarnetSpec(source) }.exceptionOrNull()
        ex shouldNotBe null
    }
})
