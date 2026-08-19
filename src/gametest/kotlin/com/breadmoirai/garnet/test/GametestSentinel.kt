package com.breadmoirai.garnet.test

import com.breadmoirai.garnet.camera.CameraModeSpec
import com.breadmoirai.garnet.editor.workspace.world.EditorCellSaverSpec
import com.breadmoirai.garnet.editor.workspace.command.EditorCommandSpec
import com.breadmoirai.garnet.editor.workspace.world.EditorDimSpec
import com.breadmoirai.garnet.editor.explorer.network.EditorFileOpsNetworkSpec
import com.breadmoirai.garnet.editor.explorer.network.EditorNetworkRegistrySpec
import com.breadmoirai.garnet.editor.structure.network.EditorStructureNetworkSpec
import com.breadmoirai.garnet.editor.workspace.world.EditorTeleportSpec
import com.breadmoirai.garnet.editor.undo.network.EditorUndoNetworkSpec
import com.breadmoirai.garnet.editor.structure.ops.StructureAutoSaveSpec
import com.breadmoirai.garnet.editor.history.ops.StructureRestoreSpec
import com.breadmoirai.garnet.editor.history.ops.LocalHistoryStoreSpec
import com.breadmoirai.garnet.editor.structure.ops.StructureRegionPersistenceSpec
import com.breadmoirai.garnet.editor.structure.ops.StructureSidecarPersistenceSpec
import com.breadmoirai.garnet.core.async.AsyncEventHandler
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

    companion object {
        /**
         * Absolute origin of the running gametest structure, published for specs that need a
         * position the server actually **ticks entities** in.
         *
         * This matters more than it looks. The gametest world tracks entities only where the test
         * harness holds a chunk ticket — its own structure area. Everywhere else, including near
         * spawn and the far-out structure lane these specs normally use, a chunk loads on demand
         * for block reads/writes but never tracks entities, so `level.getEntitiesOfClass` returns
         * nothing however alive an entity is. Any spec asserting on entities must work relative to
         * this origin. (Forcing a far chunk does work, but it generates and then permanently ticks
         * a chunk thousands of chunks out and persists that ticket into the saved world.)
         */
        @Volatile
        @JvmStatic
        var testOrigin: net.minecraft.core.BlockPos? = null
            private set
    }

    @GameTest(structure = "garnet:empty_platform", maxTicks = 600000)
    fun runAll(helper: GameTestHelper) {
        val server = helper.level.server
        testOrigin = helper.absolutePos(net.minecraft.core.BlockPos.ZERO)
        // SERVER_STARTED has already fired by the time a GameTest method runs.
        // Register tick events and install the dispatcher directly from the live server.
        AsyncEventHandler.registerWithServer(server)
        // Published by the Kotest worker thread, read on the server thread by the sequence at the
        // end of this method. `null` failure + finished == the run passed.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val worker = Thread.ofPlatform()
            .name("kotest-gametest")
            .uncaughtExceptionHandler { _, t ->
                logger.error("Kotest worker crashed", t)
                failure.set("Kotest worker crashed: ${t.message}")
                finished.set(true)
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
                            EditorUndoNetworkSpec::class,
                            EditorCommandSpec::class,
                            StructureRegionPersistenceSpec::class,
                            StructureSidecarPersistenceSpec::class,
                            LocalHistoryStoreSpec::class,
                            StructureAutoSaveSpec::class,
                            StructureRestoreSpec::class,
                            CameraModeSpec::class,
                        ),
                    )
                }
                result.fold(
                    onSuccess = { r ->
                        logger.info("Kotest: {}", r.summary())
                        if (r.failed > 0) failure.set(r.summary())
                    },
                    onFailure = { e ->
                        logger.error("Kotest engine error", e)
                        failure.set("Kotest engine error: ${e.message}")
                    },
                )
                finished.set(true)
            }
        worker.start()
        // Resolve the gametest from INSIDE a sequence step, never from a `server.execute` task.
        //
        // `helper.fail()` reports by THROWING GameTestAssertException. Thrown from a server task it
        // unwinds into TickTask.run, where the server logs "Error executing task on Server" and
        // discards it — the test is never marked failed and spins until maxTicks. That cost ~32
        // minutes per failing run while a passing run took ~2, exactly backwards for TDD. Inside a
        // sequence step the framework catches the assertion and fails the test immediately.
        helper.startSequence()
            // A GameTestAssertException thrown here means "not ready yet"; the step is retried next
            // tick until it completes without throwing.
            .thenWaitUntil { if (!finished.get()) helper.fail("Kotest run still in progress") }
            .thenExecute { failure.get()?.let { helper.fail(it) } }
            .thenSucceed()
    }
}
