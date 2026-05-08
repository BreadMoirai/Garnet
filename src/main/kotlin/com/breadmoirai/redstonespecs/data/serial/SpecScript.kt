package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.redstonespecs.data.dsl.*",
        "com.breadmoirai.redstonespecs.data.Phase",
        "com.breadmoirai.redstonespecs.data.SimTime",
        // Testing surface: lets .spec.kts name RedstoneTestSpec, runRedstoneSpec,
        // SpecLiteralCapture, and kotest matchers without explicit imports.
        "com.breadmoirai.redstonespecs.testing.RedstoneTestSpec",
        "com.breadmoirai.redstonespecs.testing.runner.runRedstoneSpec",
        "com.breadmoirai.redstonespecs.data.serial.SpecLiteralCapture",
        "com.breadmoirai.redstonespecs.testing.server.awaitTicks",
        "com.breadmoirai.redstonespecs.testing.server.awaitTickEnd",
        "com.breadmoirai.redstonespecs.testing.server.spawnStructure",
        "io.kotest.matchers.shouldBe",
    )
    jvm {
        // Anchor to RedstoneSpec's classloader so the script's RedstoneSpec
        // identity matches the host's RedstoneSpec identity. With
        // dependenciesFromCurrentContext the test/run environment can pick
        // a different loader (mod jar vs. system) and the cast fails.
        dependenciesFromClassContext(RedstoneSpec::class, wholeClasspath = true)
    }
})

@KotlinScript(
    fileExtension = "spec.kts",
    compilationConfiguration = SpecScriptCompilationConfig::class,
)
abstract class SpecScript
