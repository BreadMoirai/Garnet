package com.breadmoirai.redstonespecs.testing.launcher

import io.kotest.engine.TestEngineLauncher
import io.kotest.core.config.AbstractProjectConfig
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories

/**
 * Launches the Kotest engine on the calling thread. Sets per-source-set output directories
 * for Kotest's reports and Kensa's HTML before invoking the engine.
 *
 * Specs are discovered automatically from the launching thread's classpath via Kotest's
 * built-in classpath scan.
 *
 * @param sourceSet "gametest" | "clientTest" | "test" — used to scope report directories.
 * @param reportsDir Base directory for reports (e.g., `build/reports/redstonespecs/<sourceSet>`).
 */
fun launchKotest(sourceSet: String, reportsDir: Path): LauncherResult {
    reportsDir.createDirectories()
    val kensaDir = reportsDir.resolve("kensa").also { it.createDirectories() }

    System.setProperty("kensa.report.dir", kensaDir.absolutePathString())

    val collector = ResultCollector()
    val config = object : AbstractProjectConfig() {
        override val parallelism: Int = 1
    }

    TestEngineLauncher()
        .withProjectConfig(config)
        .withExtensions(collector)
        .launch()

    return collector.result
}
