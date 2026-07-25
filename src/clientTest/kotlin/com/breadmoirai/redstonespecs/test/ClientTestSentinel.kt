package com.breadmoirai.redstonespecs.test

import com.breadmoirai.redstonespecs.testing.core.ClientContextHolder
import com.breadmoirai.redstonespecs.testing.core.FabricTestThreadPump
import com.breadmoirai.redstonespecs.testing.core.RedstoneTestLifecycle
import com.breadmoirai.redstonespecs.testing.core.WorldHolder
import com.breadmoirai.redstonespecs.testing.launcher.LauncherResult
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("UnstableApiUsage")
class ClientTestSentinel : FabricClientGameTest {

    private val logger = LoggerFactory.getLogger("Redstone Specs")

    override fun runTest(context: ClientGameTestContext) {
        RedstoneTestLifecycle.register()
        ClientContextHolder.install(context)
        // Construct a singleplayer world to fire SERVER_STARTED, which installs McDispatchers.
        // `use { }` closes the world before runTest returns — Fabric asserts no server is still
        // running when the test exits.
        SpecTestContext.createWorld(context).use { world ->
            WorldHolder.install(world)
            try {
                val result = runKotestOnWorker(context)
                if (result.failed > 0) {
                    logger.error(result.summary())
                    error("${result.failed}/${result.total} Kotest test(s) failed")
                }
                logger.info(result.summary())
            } finally {
                WorldHolder.clear()
                ClientContextHolder.clear()
            }
        }
    }

    /**
     * FabricClientGameTest's test thread is the only thread that can call
     * [ClientGameTestContext.waitTick] to advance the integrated server. Our
     * `runRedstoneSpec` test bodies suspend on `awaitTickEnd`, which only fires when the
     * server ticks. If we ran Kotest synchronously on the test thread, the test thread
     * would block in launcher.launch() and the server would never tick → deadlock.
     *
     * This method runs Kotest on a daemon worker, while the test thread drives ticks via
     * `context.waitTick()` until the worker finishes.
     */
    private fun runKotestOnWorker(context: ClientGameTestContext): LauncherResult {
        val done = AtomicBoolean(false)
        // No @Volatile needed: worker.join() before reading establishes happens-before.
        var result: LauncherResult? = null
        var failure: Throwable? = null

        val worker = Thread({
            try {
                // RunnerBlockEngineE2ETest and DiagnosticRecordingE2ETest call EngineDrivenRun.run
                // synchronously from inside a Kotest test body. EngineDrivenRun launches another
                // Kotest engine that dispatches to McDispatchers.Server — but the outer test body
                // is already on the server thread, producing a recursive deadlock. The production
                // path (SpecRunnerCoordinator.startRun) avoids this by spawning a worker thread.
                // We exclude these tests here; their underlying behavior is exercised by JVM tests
                // (EngineDrivenRunToTestResultTest) and by hand testing the in-game Run button.
                result = launchKotest(
                    sourceSet = "clientTest",
                    reportsDir = Path.of("build/reports/redstonespecs/clientTest"),
                    specs = listOf(
                        RunRedstoneSpecSmokeTest::class,
                        ClientNetworkSpec::class,
                        RecorderScreenSpec::class,
                        ProjectEntryFlowSpec::class,
                        ViewportCompositeSpec::class,
                        ViewportPickingSpec::class,
                        CursorFocusToggleSpec::class,
                        ComposeOverlaySpec::class,
                        DockInsetsSpec::class,
                        DockRenderSpec::class,
                        DockInputSpec::class,
                    ),
                )
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.set(true)
            }
        }, "redstonespecs-kotest-worker").apply { isDaemon = true }
        worker.start()

        while (!done.get()) {
            context.waitTick()
            FabricTestThreadPump.drain(context)
        }
        worker.join()

        failure?.let { throw it }
        return result ?: error("Kotest worker exited without producing a result")
    }
}
