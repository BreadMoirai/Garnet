package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.client.screen.RunnerScreen
import com.breadmoirai.redstonespecs.network.OpenRunnerScreenS2C
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import io.kotest.matchers.shouldNotBe
import net.minecraft.core.BlockPos

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

    test("scaffold: sendOpenRunnerScreen opens RunnerScreen") {
        val pos = BlockPos(100, 64, 100)
        sendOpenRunnerScreen(OpenRunnerScreenS2C(pos, "", emptyList(), null))
        waitForClientScreen(RunnerScreen::class.java)
        RunnerScreen.active shouldNotBe null

        // Reset only the static reference; mc.screen lingers but no test depends on it.
        RunnerScreen.active = null
    }
})
