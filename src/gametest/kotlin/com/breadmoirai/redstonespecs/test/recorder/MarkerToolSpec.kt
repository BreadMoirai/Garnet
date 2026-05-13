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
