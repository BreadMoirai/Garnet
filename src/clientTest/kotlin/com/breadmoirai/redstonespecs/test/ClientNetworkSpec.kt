package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.client.screen.RecorderScreen
import com.breadmoirai.redstonespecs.client.screen.RunnerScreen
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerState
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import net.minecraft.client.gui.screens.ConfirmScreen
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i

/**
 * Client-side coverage for `ClientNetworkHandler` receivers.
 *
 * Covers UC-NET-01.b/c, UC-NET-03.e, and UC-NET-04.a (partial) from
 * `docs/use-cases/networking.md`. Server-side rows live in
 * `RecorderRunnerNetworkRegistrySpec` (gametest sourceset).
 *
 * Test bodies run on the Kotest worker thread under [ClientSpec]'s dispatcher.
 * Server-side mutations and sends hop via `onServer { … }`; UI assertions poll
 * `Minecraft.getInstance().screen` directly via the helpers in
 * `ClientNetworkTestSupport.kt`. See `docs/gametest/client-test-threading.md`.
 */
class ClientNetworkSpec : ClientSpec({

    // UC-NET-01.c: server-emitted OpenRecorderScreenS2C opens RecorderScreen on the client
    // with the correct originPos. The plan called for rightClickBlock to drive useWithoutItem
    // end-to-end, but that helper asserts the Fabric test thread. Use openScreenFor instead —
    // it's the public helper extracted from useWithoutItem in the server-side cycle and
    // exercises the same ServerPlayNetworking.send path that the block triggers.
    test("UC-NET-01.c: server build of OpenRecorderScreenS2C opens RecorderScreen with originPos") {
        val pos = BlockPos(120, 64, 100)
        onServer {
            val level = this.overworld()
            val player = level.players().firstOrNull() ?: error("no overworld player")
            level.setBlock(pos, ModRegistries.REDSTONE_SPEC_RECORDER_BLOCK.defaultBlockState(), 2)
            val be = level.getBlockEntity(pos) as SpecBlockEntity
            be.setSpecId("demo")
            be.setStructure("demo")
            be.setSpecBounds(Vec3i(3, 3, 3))
            RedstoneSpecRecorderBlock.openScreenFor(player, be)
        }

        waitForClientScreen(RecorderScreen::class.java)
        val originPos = onClient { mc -> (mc.screen as RecorderScreen).originPos }
        originPos shouldBe pos
        closeClientScreen()
    }

    // UC-NET-03.e: combined matching + mismatched origin in a single test so we reuse one
    // RunnerScreen instance — opening a fresh RunnerScreen between sub-cases is wasteful
    // and complicates polling.
    test("UC-NET-03.e: RunnerStatusS2C only updates RunnerScreen.active when originPos matches") {
        val activePos = BlockPos(140, 64, 100)
        val otherPos = BlockPos(140, 64, 200)

        RunnerScreen.active = null
        sendOpenRunnerScreen(OpenRunnerScreenS2C(activePos, "", emptyList(), null))

        val openDeadline = System.currentTimeMillis() + 5000
        while (RunnerScreen.active == null && System.currentTimeMillis() < openDeadline) Thread.sleep(50)
        RunnerScreen.active shouldNotBe null
        val initialText = RunnerScreen.active!!.statusText
        val initialState = RunnerScreen.active!!.statusState

        sendRunnerStatus(RunnerStatusS2C(otherPos, RunnerState.FAIL, "Should be ignored"))
        waitClientTicks(5)
        RunnerScreen.active?.statusText shouldBe initialText
        RunnerScreen.active?.statusState shouldBe initialState

        sendRunnerStatus(RunnerStatusS2C(activePos, RunnerState.PASS, "All good"))
        val updateDeadline = System.currentTimeMillis() + 5000
        while (RunnerScreen.active?.statusText != "All good" && System.currentTimeMillis() < updateDeadline) Thread.sleep(50)
        RunnerScreen.active?.statusText shouldBe "All good"
        RunnerScreen.active?.statusState shouldBe RunnerState.PASS

        RunnerScreen.active = null
        closeClientScreen()
    }

    // UC-NET-04.a: receiver-opens-ConfirmScreen half. Click->send half of the handshake
    // is exercised indirectly by handleOverwriteDecision server-side tests
    // (UC-NET-04.b/c). See networking.md footnote 5.
    test("UC-NET-04.a: OverwritePromptS2C opens ConfirmScreen") {
        val pos = BlockPos(160, 64, 100)
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        waitForClientScreen(ConfirmScreen::class.java)

        // ConfirmScreen.message is private; assert on the inherited title field.
        val titleText = onClient { mc -> (mc.screen as ConfirmScreen).title.string }
        titleText shouldContain "Blocks found"

        takeClientScreenshot("uc-net-04a-confirm-screen")
        closeClientScreen()
    }
})
