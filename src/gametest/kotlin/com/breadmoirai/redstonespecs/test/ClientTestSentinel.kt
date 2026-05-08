package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.RedstoneTestLifecycle
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

@Suppress("UnstableApiUsage")
class ClientTestSentinel : FabricClientGameTest {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    override fun runTest(context: ClientGameTestContext) {
        RedstoneTestLifecycle.register()
        ClientContextHolder.install(context)
        // Construct a singleplayer world to fire SERVER_STARTED, which installs McDispatchers.
        // The returned context is unused — the framework cleans up the world when runTest returns.
        SpecTestContext.createWorld(context)
        try {
            val result = launchKotest(
                sourceSet = "clientTest",
                reportsDir = Path.of("build/reports/redstonespecs/clientTest"),
                specs = listOf(
                    RunRedstoneSpecSmokeTest::class,
                    RunnerBlockEngineE2ETest::class,
                    DiagnosticRecordingE2ETest::class,
                ),
            )
            if (result.failed > 0) {
                logger.error(result.summary())
                error("${result.failed}/${result.total} Kotest test(s) failed")
            }
            logger.info(result.summary())
        } finally {
            ClientContextHolder.clear()
        }
    }
}
