package com.breadmoirai.garnet.persistence

import com.breadmoirai.garnet.dsl.GarnetSpec
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.garnet.dsl.*",
        "net.minecraft.core.Vec3i",
        // Testing surface: lets .spec.kts name GarnetTestSpec, runGarnetSpec,
        // and kotest matchers without explicit imports.
        "com.breadmoirai.garnet.testing.GarnetTestSpec",
        "com.breadmoirai.garnet.testing.runner.runGarnetSpec",
        "com.breadmoirai.garnet.testing.server.awaitTicks",
        "com.breadmoirai.garnet.testing.server.awaitTickEnd",
        "com.breadmoirai.garnet.testing.server.spawnStructure",
        "io.kotest.matchers.shouldBe",
    )
    jvm {
        // Anchor to dsl.GarnetSpec's classloader so the script's GarnetSpec
        // identity matches the host's GarnetSpec identity. With
        // dependenciesFromCurrentContext the test/run environment can pick
        // a different loader (mod jar vs. system) and the cast fails.
        dependenciesFromClassContext(GarnetSpec::class, wholeClasspath = true)
    }
})

@KotlinScript(
    fileExtension = "spec.kts",
    compilationConfiguration = SpecScriptCompilationConfig::class,
)
abstract class SpecScript
