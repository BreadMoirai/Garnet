package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.EntryKind
import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.SpecEntry
import com.breadmoirai.redstonespecs.data.StateCondition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

class KtsSpecEmitterTest : FunSpec({

    test("emit then loadString round-trips identity") {
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
        val reloaded = KtsSpecLoader.loadString(source, name = "round_trip.spec.kts")
        reloaded shouldBe spec
    }
})
