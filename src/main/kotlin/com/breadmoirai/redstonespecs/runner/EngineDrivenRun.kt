package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import com.breadmoirai.redstonespecs.data.SimTime
import com.breadmoirai.redstonespecs.data.TestResult
import com.breadmoirai.redstonespecs.data.TickCheck
import com.breadmoirai.redstonespecs.data.serial.KtsSpecEmitter
import com.breadmoirai.redstonespecs.data.serial.KtsSpecLoader
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpecContext
import com.breadmoirai.redstonespecs.testing.launcher.LauncherResult
import com.breadmoirai.redstonespecs.testing.launcher.launchKotest
import io.kotest.core.spec.Spec
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.storage.LevelResource
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.reflect.KClass

private val LOGGER = LoggerFactory.getLogger("Redstone Specs")

/**
 * Runs one [RedstoneSpec] through the Kotest engine.
 *
 * Compiles the spec to a Spec class (via emit→load), binds origin/level into a thread-local,
 * launches the engine on the calling thread (which will yield to the server thread when the
 * test body suspends), translates the resulting [LauncherResult] into [TestResult].
 *
 * Caller is responsible for choosing what thread to call this on. The plan calls this from
 * a worker thread launched by [SpecRunnerCoordinator.startRun]; the engine itself dispatches
 * test bodies onto McDispatchers.Server via RedstoneTestSpec's CoroutineDispatcherFactory.
 */
object EngineDrivenRun {
    fun run(spec: RedstoneSpec, originPos: BlockPos, level: ServerLevel): TestResult {
        val source = KtsSpecEmitter.emit(spec)
        val klass: KClass<out Spec> = KtsSpecLoader.loadSpec(source, "${spec.id}.spec.kts")

        RedstoneTestSpecContext.bind(originPos, level)
        val launcherResult: LauncherResult = try {
            launchKotest(
                sourceSet = "runtime",
                reportsDir = reportsDir(level),
                specs = listOf(klass),
            )
        } finally {
            RedstoneTestSpecContext.clear()
        }
        return toTestResult(spec.id, launcherResult)
    }

    private fun reportsDir(level: ServerLevel): Path {
        val saveRoot = level.server.getWorldPath(LevelResource.ROOT)
        val dir = saveRoot.resolve("redstonespecs-reports")
        Files.createDirectories(dir)
        return dir
    }

    private fun toTestResult(specId: String, lr: LauncherResult): TestResult {
        val checks: List<TickCheck> = if (lr.failed == 0) {
            listOf(TickCheck(SimTime.START, "spec '$specId'", expected = "ok", actual = "ok", pass = true))
        } else {
            lr.errors.map { e ->
                TickCheck(SimTime.START, e.name, expected = "(see test message)", actual = e.message, pass = false)
            }
        }
        return TestResult(specId, System.currentTimeMillis(), checks)
    }
}
