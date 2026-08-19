package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.harness.client.ClientContextHolder
import com.breadmoirai.garnet.harness.client.FabricTestThreadPump
import com.breadmoirai.garnet.camera.OrbitCameraSpec
import com.breadmoirai.garnet.core.async.AsyncEventHandler
import com.breadmoirai.garnet.harness.client.WorldHolder
import com.breadmoirai.garnet.harness.launcher.LauncherResult
import com.breadmoirai.garnet.harness.launcher.launchKotest
import com.breadmoirai.garnet.dock.compose.ComposeOverlay
import com.breadmoirai.garnet.dock.input.DockFocusKeybindSpec
import com.breadmoirai.garnet.dock.shell.DockInputSpec
import com.breadmoirai.garnet.dock.shell.DockRenderSpec
import com.breadmoirai.garnet.dock.shell.DockState
import com.breadmoirai.garnet.dock.viewport.ViewportSpec
import com.breadmoirai.garnet.dock.viewport.ViewportState
import com.breadmoirai.garnet.dock.viewport.WindowViewportExt
import com.breadmoirai.garnet.editor.explorer.ui.ExplorerUiSpec
import com.breadmoirai.garnet.editor.explorer.ui.JewelExplorerSpec
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.Minecraft
import org.apache.commons.lang3.function.FailableConsumer
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
                // `registerDockWorldLifecycle`'s JOIN handler (ui/viewport/DockKeybinds.kt) now
                // fires on this world's join like any other Garnet-capable server, and applies the
                // remembered LEFT-region visibility (defaulting to visible when no
                // garnet-dock.json exists yet, which is the case for a fresh clientTest run dir).
                // Every spec's "effect off" baseline assumes a closed dock, so establish that
                // explicitly here rather than relying on each spec's own DockState.reset() — that
                // per-spec reset only happens to run early enough today; auto-open now makes it
                // load-bearing instead of incidental. Normalize once, centrally, immediately after
                // world creation and before Kotest starts.
                context.runOnClient(object : FailableConsumer<Minecraft, RuntimeException> {
                    override fun accept(mc: Minecraft) {
                        DockState.reset()
                        ViewportState.active = false
                        ComposeOverlay.enabled = false
                        (mc.window as Any as WindowViewportExt).`garnet$updateScaledFramebuffer`(true)
                    }
                })
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
                        ViewportSpec::class,
                        DockRenderSpec::class,
                        DockInputSpec::class,
                        DockFocusKeybindSpec::class,
                        JewelExplorerSpec::class,
                        ExplorerUiSpec::class,
                        // Last on purpose: it is the only spec that puts the player into
                        // spectator (camera mode is a real server round trip here), so any
                        // residue it could leave lands after every other spec has run.
                        OrbitCameraSpec::class,
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
