package com.breadmoirai.garnet.testing.data

import com.breadmoirai.garnet.core.spec.GarnetSpec
import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromClassContext
import kotlin.script.experimental.jvm.jvm

object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.garnet.core.spec.*",
        "net.minecraft.core.Vec3i",
    )
    jvm {
        // Anchor to spec.GarnetSpec's classloader so the script's GarnetSpec
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
