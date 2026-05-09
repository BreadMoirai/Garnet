package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.core.Vec3i

class KtsSpecEmitterTest : FunSpec({

    test("emit wraps the spec DSL in a RedstoneTestSpec subclass with a single named test") {
        val spec = redstoneSpec("comparator-latch") {
            bounds(5, 3, 5)
            lifespan = 8
        }
        val source = KtsSpecEmitter.emit(spec)

        source shouldContain "class ComparatorLatchSpec : RedstoneTestSpec("
        source shouldContain "test(\"comparator-latch\")"
        source shouldContain "runRedstoneSpec("
        source shouldContain "redstoneSpec(\"comparator-latch\")"
        source shouldContain "bounds(5, 3, 5)"
        source shouldContain "SpecLiteralCapture.record"
    }

    test("classNameFor splits on dashes, underscores, spaces, dots, and slashes") {
        KtsSpecEmitter.classNameFor("comparator-latch") shouldBe "ComparatorLatchSpec"
        KtsSpecEmitter.classNameFor("my_spec") shouldBe "MySpecSpec"
        KtsSpecEmitter.classNameFor("ns/category/thing") shouldBe "NsCategoryThingSpec"
        KtsSpecEmitter.classNameFor("a.b.c") shouldBe "ABCSpec"
        KtsSpecEmitter.classNameFor("simple") shouldBe "SimpleSpec"
    }

    test("new-dsl source round-trips meta fields via loadRedstoneSpec") {
        // The new-DSL path: hand-authored source (as RecordingDslEmitter would emit),
        // loaded back via KtsSpecLoader → dsl.RedstoneSpec. We verify the value-class
        // meta fields (id, bounds, lifespan, structure); the lambda body is opaque.
        val source = """
            import com.breadmoirai.redstonespecs.dsl.*
            import net.minecraft.core.Vec3i

            redstoneSpec(id = "round_trip", bounds = Vec3i(5, 4, 5), lifespan = 40,
                structure = "redstonespecs:rt") {}
        """.trimIndent()
        val reloaded = KtsSpecLoader.loadRedstoneSpec(source, name = "round_trip.spec.kts")
        reloaded.id shouldBe "round_trip"
        reloaded.bounds shouldBe Vec3i(5, 4, 5)
        reloaded.lifespan shouldBe 40
        reloaded.structure shouldBe "redstonespecs:rt"
    }
})
