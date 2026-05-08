package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import com.breadmoirai.redstonespecs.data.dsl.redstoneSpec
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.core.BlockPos
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

    test("emit then loadRedstoneSpec round-trips identity") {
        val spec = RedstoneSpec(
            id = "round_trip",
            bounds = Vec3i(5, 4, 5),
            lifespan = 40,
            structure = "redstonespecs:rt",
            entries = listOf(
                SpecEntry(BlockPos(2, 0, 2), "lever", 0xFFFF4444.toInt(),
                    EntryKind.INPUT, SimTime.START,
                    StateCondition.BoolProperty("powered", true)),
                SpecEntry(BlockPos(2, 0, 2), "lever", 0xFFFF4444.toInt(),
                    EntryKind.INPUT, SimTime(10, Phase.START_OF_TICK),
                    StateCondition.Not(StateCondition.BoolProperty("powered", true))),
                SpecEntry(BlockPos(4, 0, 4), "lamp", -1,
                    EntryKind.OUTPUT, SimTime(11, Phase.END_OF_TICK),
                    StateCondition.BoolProperty("lit", true)),
            ),
        )
        val source = KtsSpecEmitter.emit(spec)
        val reloaded = KtsSpecLoader.loadRedstoneSpec(source, name = "round_trip.spec.kts")
        reloaded shouldBe spec
    }
})
