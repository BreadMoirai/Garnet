package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.client.screen.RunnerScreen
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Client-side coverage for `ClientNetworkHandler` receivers.
 * Covers UC-NET-01.b/c, UC-NET-03.e, and UC-NET-04.a (partial) from
 * `docs/use-cases/networking.md`. Server-side rows live in
 * `RecorderRunnerNetworkRegistrySpec` (gametest sourceset).
 *
 * Worker-thread caveat: Fabric's `ClientGameTestContext.waitForScreen` /
 * `waitFor` / `waitTick` assert being called from the test thread, but Kotest
 * runs on a worker. UI assertions use the worker-safe polling helpers in
 * `ClientNetworkTestSupport.kt` (`waitForClientScreen`, `closeClientScreen`,
 * `waitClientTicks`) which read `Minecraft.getInstance().screen` directly.
 */
class ClientNetworkSpec : RedstoneTestSpec({

    // UC-NET-01.c: server-emitted OpenRecorderScreenS2C opens RecorderScreen on the client
    // with the correct originPos. The plan called for rightClickBlock to drive useWithoutItem
    // end-to-end, but that helper asserts the Fabric test thread (we're on the server thread
    // here under RedstoneTestSpec's dispatcher). Use openScreenFor instead — it's the public
    // helper extracted from useWithoutItem in the server-side cycle and exercises the same
    // ServerPlayNetworking.send path that the block triggers.
    test("UC-NET-01.c: server build of OpenRecorderScreenS2C opens RecorderScreen with originPos") {
        val server = McDispatchers.currentServer
        val level = server.overworld()
        val pos = BlockPos(120, 64, 100)
        val player = level.players().firstOrNull() ?: error("no overworld player")

        // Place + configure a recorder BE on the server thread (we're already on it).
        level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
        val be = level.getBlockEntity(pos) as SpecBlockEntity
        be.setSpecId("demo")
        be.setStructure("demo")
        be.setSpecBounds(Vec3i(3, 3, 3))

        RedstoneSpecRecorderBlock.openScreenFor(player, be)
        waitForClientScreen(RecorderScreen::class.java)

        val screen = net.minecraft.client.Minecraft.getInstance().screen as RecorderScreen
        screen.originPos shouldBe pos
    }
})
