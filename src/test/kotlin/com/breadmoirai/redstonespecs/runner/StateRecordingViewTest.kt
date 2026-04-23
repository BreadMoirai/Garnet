package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.Phase
import com.breadmoirai.redstonespecs.data.SimTime
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.block.LeverBlock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StateRecordingViewTest {

    @BeforeAll
    fun bootstrap() {
        SharedConstants.tryDetectVersion()
        Bootstrap.bootStrap()
    }

    private fun leverBlock() = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
    private fun leverState(powered: Boolean) =
        leverBlock().defaultBlockState().setValue(LeverBlock.POWERED, powered)

    @Test
    fun `stateAt returns initial snapshot when no changes`() {
        val pos = BlockPos(0, 0, 0)
        val initial = leverState(false)
        val view = StateRecordingView(mapOf(pos to initial), emptyList())
        assertEquals(initial, view.stateAt(pos, SimTime(0, Phase.END_OF_TICK)))
    }

    @Test
    fun `stateAt applies diff at exact simTime`() {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        assertEquals(leverState(true), view.stateAt(pos, t))
    }

    @Test
    fun `stateAt does not apply future change`() {
        val pos = BlockPos(0, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(change))
        val before = SimTime(0, Phase.BLOCK_EVENTS, 0)
        assertEquals(leverState(false), view.stateAt(pos, before))
    }

    @Test
    fun `changesAt filters by position`() {
        val p1 = BlockPos(0, 0, 0)
        val p2 = BlockPos(1, 0, 0)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val c1 = BlockStateChange(p1, t, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(p2, t, null, listOf(PropertyDiff("powered", "true")))
        val view = StateRecordingView(
            mapOf(p1 to leverState(false), p2 to leverState(false)),
            listOf(c1, c2),
        )
        assertEquals(listOf(c1), view.changesAt(p1))
        assertEquals(listOf(c2), view.changesAt(p2))
    }

    @Test
    fun `changesInPhase filters by tick and phase`() {
        val pos = BlockPos(0, 0, 0)
        val t1 = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val t2 = SimTime(0, Phase.END_OF_TICK, 0)
        val c1 = BlockStateChange(pos, t1, null, listOf(PropertyDiff("powered", "true")))
        val c2 = BlockStateChange(pos, t2, null, listOf(PropertyDiff("powered", "false")))
        val view = StateRecordingView(mapOf(pos to leverState(false)), listOf(c1, c2))
        assertEquals(listOf(c1), view.changesInPhase(0, Phase.SCHEDULED_TICKS))
        assertEquals(listOf(c2), view.changesInPhase(0, Phase.END_OF_TICK))
    }

    @Test
    fun `stateAt applies toBlock block type change`() {
        val pos = BlockPos(0, 0, 0)
        val leverBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:lever"))
        val stoneBlock = BuiltInRegistries.BLOCK.getValue(Identifier.parse("minecraft:stone"))
        val initial = leverBlock.defaultBlockState()
        val stoneId = BuiltInRegistries.BLOCK.getKey(stoneBlock)
        val t = SimTime(0, Phase.SCHEDULED_TICKS, 0)
        val change = BlockStateChange(pos, t, stoneId, emptyList())
        val view = StateRecordingView(mapOf(pos to initial), listOf(change))
        assertEquals(stoneBlock.defaultBlockState(), view.stateAt(pos, t))
    }
}
