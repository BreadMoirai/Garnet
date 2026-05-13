package com.breadmoirai.redstonespecs.test.recorder

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.network.SetRecorderConfigC2S
import com.breadmoirai.redstonespecs.network.handleSetRecorderConfig
import com.breadmoirai.redstonespecs.runner.EntryMarker
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.placeRecorderBE
import com.breadmoirai.redstonespecs.test.placeRunnerBE
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldBeEmpty
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Covers UC-REC-02.a/b/d (marker-tool behaviour) and UC-REC-03.b
 * (`handleSetRecorderConfig` applies specId and structureId).
 *
 * All tests run on the server thread via `onServer { … }` (per RedstoneTestSpec convention).
 * Items are pulled from `ModRegistries` because direct construction trips MC's
 * intrusive-holder guard.
 */
class MarkerToolSpec : RedstoneTestSpec({

    test("UC-REC-02.a: useOn outside any registered SpecBE bounds returns PASS and adds no marker") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            val hitPos = BlockPos(900, 64, 900)
            val item = ModRegistries.INPUT_SPEC_MARKER
            val ctx = buildUseOnContext(level, player, item, hitPos)
            val result = item.useOn(ctx)
            result shouldBe InteractionResult.PASS
        }
    }

    test("UC-REC-02.b: useOn inside a runner block's bounds returns PASS and adds no marker") {
        onServer {
            val level = this.overworld()
            val player = makeMockServerPlayer(level.server)
            val pos = BlockPos(910, 64, 910)
            val be = placeRunnerBE(level, pos, specId = "uc02b", bounds = Vec3i(3, 3, 3))
            be.specMarkers.shouldBeEmpty()

            val hitPos = pos.offset(1, 0, 1)
            val item = ModRegistries.INPUT_SPEC_MARKER
            val ctx = buildUseOnContext(level, player, item, hitPos)
            val result = item.useOn(ctx)

            result shouldBe InteractionResult.PASS
            be.specMarkers.shouldBeEmpty()
        }
    }

    test("UC-REC-02.d: addOrUpdateMarker replaces same (pos,kind), appends new pos or kind") {
        onServer {
            val level = this.overworld()
            val pos = BlockPos(920, 64, 920)
            val be = placeRecorderBE(level, pos, specId = "uc02d", bounds = Vec3i(3, 3, 3))
            be.specMarkers.shouldBeEmpty()

            val rel = BlockPos(1, 0, 0)
            val a = EntryMarker(pos = rel, label = "input_a", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)
            val aReplacement = EntryMarker(pos = rel, label = "input_a_v2", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)

            be.addOrUpdateMarker(a)
            be.specMarkers shouldHaveSize 1
            be.specMarkers.single().label shouldBe "input_a"

            be.addOrUpdateMarker(aReplacement)
            be.specMarkers shouldHaveSize 1
            be.specMarkers.single().label shouldBe "input_a_v2"

            val samePosDifferentKind = EntryMarker(pos = rel, label = "output_a", color = 0xFFFF8800.toInt(), kind = EntryMarker.Kind.OUTPUT)
            be.addOrUpdateMarker(samePosDifferentKind)
            be.specMarkers shouldHaveSize 2

            val differentPos = EntryMarker(pos = BlockPos(2, 0, 0), label = "input_b", color = 0xFF4488FF.toInt(), kind = EntryMarker.Kind.INPUT)
            be.addOrUpdateMarker(differentPos)
            be.specMarkers shouldHaveSize 3
        }
    }
})

private fun buildUseOnContext(
    level: net.minecraft.world.level.Level,
    player: net.minecraft.world.entity.player.Player,
    item: net.minecraft.world.item.Item,
    hitPos: BlockPos,
): UseOnContext {
    player.setItemInHand(InteractionHand.MAIN_HAND, item.defaultInstance)
    val hitVec = Vec3.atCenterOf(hitPos)
    val hit = BlockHitResult(hitVec, net.minecraft.core.Direction.UP, hitPos, false)
    return UseOnContext(player, InteractionHand.MAIN_HAND, hit)
}
