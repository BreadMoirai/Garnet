package com.breadmoirai.redstonespecs.test.network

import com.breadmoirai.redstonespecs.network.handleRecorderCommand
import com.breadmoirai.redstonespecs.network.RecorderCmd
import com.breadmoirai.redstonespecs.network.RecorderCommandC2S
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

    test("UC-NET-02.b: handleRecorderCommand on null BE is a silent no-op") {
        withTempRoot("net-rr-scaffold") {
            onServer {
                val player = makeMockServerPlayer(this)
                drainPayloads(player)

                handleRecorderCommand(this, player, RecorderCommandC2S(BlockPos(1000, 64, 1000), RecorderCmd.START))

                drainPayloads(player).shouldBeEmpty()
            }
        }
    }
})
