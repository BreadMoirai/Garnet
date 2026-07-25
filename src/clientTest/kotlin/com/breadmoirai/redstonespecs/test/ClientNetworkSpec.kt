package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.ModRegistries
import com.breadmoirai.redstonespecs.block.RedstoneSpecRecorderBlock
import com.breadmoirai.redstonespecs.block.SpecBlockEntity
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.network.OverwriteDecisionC2SPayload
import com.breadmoirai.redstonespecs.network.OverwritePromptS2CPayload
import com.breadmoirai.redstonespecs.network.RunnerState
import com.breadmoirai.redstonespecs.network.RunnerStatusS2C
import com.breadmoirai.redstonespecs.testing.ClientSpec
import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
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

    // UC-NET-01.c (post-Task-7): the recorder UI has been hard-cut — the client no longer owns
    // a RecorderScreen. OpenRecorderScreenS2C is now handled as a logged no-op (recorder returns
    // as a panel in sub-project A/B). Assert the receiver doesn't open any screen. The plan called
    // for rightClickBlock to drive useWithoutItem end-to-end, but that helper asserts the Fabric
    // test thread. Use openScreenFor instead — it's the public helper extracted from
    // useWithoutItem in the server-side cycle and exercises the same ServerPlayNetworking.send
    // path that the block triggers.
    test("UC-NET-01.c: OpenRecorderScreenS2C is a no-op (recorder UI removed)") {
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

        waitClientTicks(5)
        onClient { mc -> mc.screen } shouldBe null
    }

    // UC-NET-03.e (post-Task-7): the runner UI has been hard-cut — RunnerScreen.active no longer
    // exists. OpenRunnerScreenS2C and RunnerStatusS2C are now logged no-ops (runner returns as a
    // panel in sub-project A/B). Assert neither receiver opens a screen.
    test("UC-NET-03.e: OpenRunnerScreenS2C and RunnerStatusS2C are no-ops (runner UI removed)") {
        val activePos = BlockPos(140, 64, 100)

        sendOpenRunnerScreen(OpenRunnerScreenS2C(activePos, "", emptyList(), null))
        waitClientTicks(5)
        onClient { mc -> mc.screen } shouldBe null

        sendRunnerStatus(RunnerStatusS2C(activePos, RunnerState.PASS, "All good"))
        waitClientTicks(5)
        onClient { mc -> mc.screen } shouldBe null
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

    test("UC-NET-04.a: clicking Overwrite sends OverwriteDecisionC2SPayload(true)") {
        val pos = BlockPos(180, 64, 100)
        drainClientPayloads()
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        waitForClientScreen(ConfirmScreen::class.java)

        com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump.runOnTestThread { ctx ->
            ctx.clickScreenButton("Overwrite")
        }

        val deadline = System.currentTimeMillis() + 5000
        while (onClient { mc -> mc.screen } != null && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val decisions = drainClientPayloads().filterIsInstance<OverwriteDecisionC2SPayload>()
        decisions shouldHaveSize 1
        decisions[0].originPos shouldBe pos
        decisions[0].overwrite shouldBe true
    }

    test("UC-NET-04.a: clicking Skip Structure sends OverwriteDecisionC2SPayload(false)") {
        val pos = BlockPos(200, 64, 100)
        drainClientPayloads()
        sendOverwritePrompt(OverwritePromptS2CPayload(pos, "demo"))
        waitForClientScreen(ConfirmScreen::class.java)

        com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump.runOnTestThread { ctx ->
            ctx.clickScreenButton("Skip Structure")
        }

        val deadline = System.currentTimeMillis() + 5000
        while (onClient { mc -> mc.screen } != null && System.currentTimeMillis() < deadline) Thread.sleep(50)

        val decisions = drainClientPayloads().filterIsInstance<OverwriteDecisionC2SPayload>()
        decisions shouldHaveSize 1
        decisions[0].originPos shouldBe pos
        decisions[0].overwrite shouldBe false
    }
})
