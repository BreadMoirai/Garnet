package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.test.editor.EditorCellSaverSpec
import com.breadmoirai.garnet.test.editor.EditorCommandSpec
import com.breadmoirai.garnet.test.editor.EditorDimSpec
import com.breadmoirai.garnet.test.editor.EditorFileOpsNetworkSpec
import com.breadmoirai.garnet.test.editor.EditorNetworkRegistrySpec
import com.breadmoirai.garnet.test.editor.EditorStructureNetworkSpec
import com.breadmoirai.garnet.test.editor.EditorTeleportSpec
import com.breadmoirai.garnet.test.editor.StructureAutoSaveSpec
import com.breadmoirai.garnet.test.history.LocalHistoryStoreSpec
import com.breadmoirai.garnet.test.structure.StructureRegionPersistenceSpec
import com.breadmoirai.garnet.test.structure.StructureSidecarPersistenceSpec
import com.breadmoirai.garnet.mc.McLifecycle
import com.breadmoirai.garnet.harness.launcher.launchKotest
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

    private val logger = LoggerFactory.getLogger("Garnet")

    @GameTest(structure = "garnet:empty_platform", maxTicks = 600000)
    fun runAll(helper: GameTestHelper) {
        val server = helper.level.server
        // SERVER_STARTED has already fired by the time a GameTest method runs.
        // Register tick events and install the dispatcher directly from the live server.
        McLifecycle.registerWithServer(server)
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
                        reportsDir = Path.of("build/reports/garnet/gametest"),
                        specs = listOf(
                            SmokeSpec::class,
                            EditorDimSpec::class,
                            EditorCellSaverSpec::class,
                            EditorTeleportSpec::class,
                            EditorNetworkRegistrySpec::class,
                            EditorStructureNetworkSpec::class,
                            EditorFileOpsNetworkSpec::class,
                            EditorCommandSpec::class,
                            StructureRegionPersistenceSpec::class,
                            StructureSidecarPersistenceSpec::class,
                            LocalHistoryStoreSpec::class,
                            StructureAutoSaveSpec::class,
                        ),
                    )
                }
                server.execute {
                    result.fold(
                        onSuccess = { r ->
                            logger.info("Kotest: {}", r.summary())
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
