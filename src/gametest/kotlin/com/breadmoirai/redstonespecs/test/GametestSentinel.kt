package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.TestBridgeLifecycle
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.gametest.framework.GameTestHelper
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Single sentinel for the gametest source set. Launches the Kotest engine on a worker
 * thread and resolves the gametest via `helper.succeed()`/`fail()` posted back to the
 * server thread. We must NOT call launchKotest synchronously from this method, because
 * that would block the server tick loop and prevent any of the suspending primitives
 * from making progress.
 */
class GametestSentinel {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    @GameTest(structure = "redstonespecs:empty_platform", maxTicks = 12000)
    fun runAll(helper: GameTestHelper) {
        TestBridgeLifecycle.register()

        val server = helper.level.server
        val worker = Thread.ofPlatform()
            .name("kotest-gametest")
            .uncaughtExceptionHandler { _, t ->
                logger.error("Kotest worker crashed", t)
                server.execute { helper.fail("Kotest worker crashed: ${t.message}") }
            }
            .unstarted {
                val result = runCatching {
                    launchKotest(
                        sourceSet = "gametest",
                        reportsDir = Path.of("build/reports/redstonespecs/gametest"),
                    )
                }
                server.execute {
                    result.fold(
                        onSuccess = { r ->
                            if (r.failed > 0) helper.fail(r.summary())
                            else helper.succeed()
                        },
                        onFailure = { e ->
                            logger.error("Kotest engine error", e)
                            helper.fail("Kotest engine error: ${e.message}")
                        },
                    )
                }
            }
        worker.start()
        // Return immediately. GameTestSequence keeps the test alive until the worker
        // resolves it via helper.succeed()/fail().
    }
}
