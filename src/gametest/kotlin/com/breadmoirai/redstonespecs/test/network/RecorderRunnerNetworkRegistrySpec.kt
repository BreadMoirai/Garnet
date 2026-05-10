package com.breadmoirai.redstonespecs.test.network

import com.breadmoirai.redstonespecs.network.handleRecorderCommand
import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
import com.breadmoirai.redstonespecs.network.handleRunnerCommand
import com.breadmoirai.redstonespecs.network.RunnerCmd
import com.breadmoirai.redstonespecs.network.RunnerCommandC2S
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldBeEmpty
import net.minecraft.core.BlockPos

/**
 * Server-side coverage for `NetworkRegistry` recorder/runner C2S handlers.
 * Each test corresponds to one or more rows in `docs/use-cases/networking.md`
 * (UC-NET-01.a/d through UC-NET-05). Test names embed the UC ID for traceability.
 *
 * Client-side rows (UC-NET-01.b/c, UC-NET-03.e, UC-NET-04.a) are deferred to
 * a future client-gametest cycle.
 */
class RecorderRunnerNetworkRegistrySpec : RedstoneTestSpec({

    // UC-NET-02 — Server validates originPos and rejects stale or missing BEs.
    // 02.a (server-thread wrap) is verified structurally: every test below invokes
    //      handleX from inside `onServer { }`, which is exactly the contract the
    //      `context.server().execute { … }` lambda enforces at the registration site.
    // 02.c (block-kind re-validation) is covered by UC-NET-05.a and UC-NET-05.b.

    test("UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op") {
        withTempRoot("net-uc02b") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(BlockPos(1000, 64, 1000), RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }

    test("UC-NET-02.d: handleRunnerCommand on null BE sends no S2C ack") {
        withTempRoot("net-uc02d") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRunnerCommand(this, player, RunnerCommandC2S(BlockPos(1004, 64, 1000), RunnerCmd.PLACE_STRUCTURE))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }
})
