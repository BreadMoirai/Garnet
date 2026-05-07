package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock

class StateRecordingViewTest : FunSpec({

    beforeSpec {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    fun leverBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    fun leverState(powered: Boolean) =
        leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, powered)

    test("stateAt returns initial snapshot when no changes") {
        val pos = BlockPos(0, 0, 0)
        val initial = leverState(false)
        val view = StateRecordingView(mapOf(pos to initial), emptyList())
        view.stateAt(pos, SimTime(0, Phase.END_OF_TICK)) shouldBe initial
    }

    test("stateAt applies diff at exact simTime") {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        view.stateAt(pos, t) shouldBe leverState(true)
    }

    test("stateAt does not apply future change") {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        val before = SimTime(0, Phase.BLOCK_EVENTS, 0)
        view.stateAt(pos, before) shouldBe leverState(false)
    }

    test("changesAt filters by position") {
        val p1 = BlockPos(0, 0, 0)
        val p2 = BlockPos(1, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val c1 = BlockStateChange(p1, t, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(p2, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(
            mapOf(p1 to leverState(false), p2 to leverState(false)),
            listOf(c1, c2),
        )
        view.changesAt(p1) shouldBe listOf(c1)
        view.changesAt(p2) shouldBe listOf(c2)
    }

    test("changesInPhase filters by tick and phase") {
        val pos = BlockPos(0, 0, 0)
        val t1 = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val t2 = SimTime(0, Phase.END_OF_TICK, 0)
        val c1 = BlockStateChange(pos, t1, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(pos, t2, null, listOf(PropertyDiff("powered", "false")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(c1, c2))
        view.changesInPhase(0, Phase.SCHEDULED_TICKS) shouldBe listOf(c1)
        view.changesInPhase(0, Phase.END_OF_TICK) shouldBe listOf(c2)
    }

    test("stateAt applies toBlock block type change") {
        val pos = BlockPos(0, 0, 0)
        val leverBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
        val stoneBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:stone"))
        val initial = leverBlock.defaultBlockState()
        val stoneId = BuiltInRegistries.BLOCK.getKey(stoneBlock)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, stoneId, emptyList())
        val view = StateRecordingView(mapOf(pos to initial), listOf(change))
        view.stateAt(pos, t) shouldBe stoneBlock.defaultBlockState()
    }
})
