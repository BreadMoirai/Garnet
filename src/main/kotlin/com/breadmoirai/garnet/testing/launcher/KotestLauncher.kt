package com.breadmoirai.garnet.testing.launcher

import io.kotest.core.spec.Spec
import io.kotest.engine.TestEngineLauncher
import io.kotest.core.config.AbstractProjectConfig
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.reflect.KClass

/**
 * Launches the Kotest engine on the calling thread.
 *
 * If [specs] is non-empty, those specs are passed directly to the engine (no classpath scan).
 * If empty, the engine uses classpath scanning to discover specs automatically.
 *
 * @param sourceSet "gametest" | "clientTest" | "test" — used to scope report directories.
 * @param reportsDir Base directory for reports (e.g., `build/reports/garnet/<sourceSet>`).
 * @param specs Optional explicit list of spec classes. Pass these to avoid classpath scanning.
 */
fun launchKotest(
    sourceSet: String,
    reportsDir: Path,
    specs: List<KClass<out Spec>> = emptyList(),
): LauncherResult {
    reportsDir.createDirectories()

    // Disable Kotest's classpath autoscan: it walks the entire MC classpath looking for
    // @AutoScan extensions, which is slow and unnecessary for our explicit-spec setup.
    System.setProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
    System.setProperty("kotest.framework.classpath.scanning.config.disable", "true")

    val collector = ResultCollector()
    val diagListener = DiagnosticRecorderListener()
    val config = object : AbstractProjectConfig() {
        override val parallelism: Int = 1
    }

    val launcher = TestEngineLauncher()
        .withProjectConfig(config)
        .withExtensions(collector, diagListener)

    if (specs.isNotEmpty()) {
        launcher.withClasses(specs).launch()
    } else {
        launcher.launch()
    }

    return collector.result.copy(recordings = diagListener.snapshot())
}
