package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.harness.client.ClientContextHolder
import com.breadmoirai.garnet.harness.client.FabricTestThreadPump
import com.breadmoirai.garnet.core.async.AsyncEventHandler
import com.breadmoirai.garnet.harness.client.WorldHolder
import com.breadmoirai.garnet.harness.launcher.LauncherResult
import com.breadmoirai.garnet.harness.launcher.launchKotest
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("UnstableApiUsage")
class ClientTestSentinel : FabricClientGameTest {

    private val logger = LoggerFactory.getLogger("Garnet")

    override fun runTest(context: ClientGameTestContext) {
        AsyncEventHandler.register()
        ClientContextHolder.install(context)
        // Construct a singleplayer world to fire SERVER_STARTED, which installs AsyncDispatchers.
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
     * `runGarnetSpec` test bodies suspend on `awaitTickEnd`, which only fires when the
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
                result = launchKotest(
                    sourceSet = "clientTest",
                    reportsDir = Path.of("build/reports/garnet/clientTest"),
                    specs = listOf(
                        RunGarnetSpecSmokeTest::class,
                        ViewportCompositeSpec::class,
                        ViewportPickingSpec::class,
                        ViewportCursorMappingSpec::class,
                        CursorFocusToggleSpec::class,
                        DockInsetsSpec::class,
                        DockRenderSpec::class,
                        DockInputSpec::class,
                        DockLifecycleSpec::class,
                        GlfwKeyMapSpec::class,
                        ExplorerTreeStateSpec::class,
                        ModConfigSpec::class,
                        ProjectExplorerSpec::class,
                        RootPickerSpec::class,
                        StructureExplorerSpec::class,
                        JewelExplorerSpec::class,
                        ExplorerContextMenuSpec::class,
                    ),
                )
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.set(true)
            }
        }, "garnet-kotest-worker").apply { isDaemon = true }
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
