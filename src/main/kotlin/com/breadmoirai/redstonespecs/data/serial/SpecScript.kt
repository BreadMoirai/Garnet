package com.breadmoirai.redstonespecs.data.serial

import kotlin.script.experimental.annotations.KotlinScript
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.api.defaultImports
import kotlin.script.experimental.jvm.dependenciesFromCurrentContext
import kotlin.script.experimental.jvm.jvm

object SpecScriptCompilationConfig : ScriptCompilationConfiguration({
    defaultImports(
        "com.breadmoirai.redstonespecs.data.dsl.*",
        "com.breadmoirai.redstonespecs.data.Phase",
        "com.breadmoirai.redstonespecs.data.SimTime",
    )
    jvm {
        dependenciesFromCurrentContext(wholeClasspath = true)
    }
})

@KotlinScript(
    fileExtension = "spec.kts",
    compilationConfiguration = SpecScriptCompilationConfig::class,
)
abstract class SpecScript
