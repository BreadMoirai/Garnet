package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import io.kotest.core.spec.Spec
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.reflect.KClass
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvm.baseClassLoader
import kotlin.script.experimental.jvm.jvm
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object KtsSpecLoader {
    private val host = BasicJvmScriptingHost()
    private val evalConfig = ScriptEvaluationConfiguration {
        // Pin the script's runtime classloader to RedstoneSpec's loader so that
        // type identity is shared between host and script.
        jvm {
            baseClassLoader(RedstoneSpec::class.java.classLoader)
        }
    }

    /**
     * Evaluates a `.spec.kts` source and returns the declared [Spec] subclass.
     *
     * The emitted form declares `class XSpec : RedstoneTestSpec(...)` as a nested class
     * of the script object. This function locates that class via reflection on the script
     * instance's declared nested classes.
     */
    fun loadSpec(source: String, name: String = "spec.kts"): KClass<out Spec> {
        val eval = evalOrThrow(source, name)
        return findFirstSpecClass(eval, name)
    }

    /**
     * Evaluates a `.spec.kts` source and returns the inner [RedstoneSpec] literal.
     * Used by the in-game editor to inspect the spec without running any tests.
     */
    fun loadRedstoneSpec(source: String, name: String = "spec.kts"): RedstoneSpec {
        val klass = loadSpec(source, name)
        return SpecLiteralCapture.captureFrom(klass)
            ?: error("$name: could not extract RedstoneSpec literal from script (SpecLiteralCapture.record was never called)")
    }

    /** Loads a `.spec.kts` file and returns the declared [Spec] class. */
    fun loadFile(path: Path): KClass<out Spec> =
        loadSpec(path.readText(), name = path.fileName.toString())

    /** Loads a `.spec.kts` file and returns its inner [RedstoneSpec] literal. */
    fun loadFileAsRedstoneSpec(path: Path): RedstoneSpec =
        loadRedstoneSpec(path.readText(), name = path.fileName.toString())

    // ── internal helpers ──────────────────────────────────────────────────────

    private fun evalOrThrow(source: String, name: String): EvaluationResult {
        val result = host.eval(source.toScriptSource(name), SpecScriptCompilationConfig, evalConfig)
        return when (result) {
            is ResultWithDiagnostics.Success -> result.value
            is ResultWithDiagnostics.Failure -> {
                val msg = result.reports.joinToString("\n") { "  ${it.severity}: ${it.message}" }
                error("Failed to load $name:\n$msg")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findFirstSpecClass(eval: EvaluationResult, name: String): KClass<out Spec> {
        // After eval, scriptInstance is a property on the base ResultValue class.
        // The class declared inside the .kts (e.g. `class FooSpec : RedstoneTestSpec(...)`)
        // appears as a declared nested class on the script object's Class.
        val rv = eval.returnValue
        if (rv is ResultValue.Error) throw rv.error
        val scriptInstance = rv.scriptInstance
            ?: error("$name: script produced no instance (returnValue=${rv::class.simpleName})")

        val specClass = scriptInstance.javaClass.declaredClasses
            .firstOrNull { Spec::class.java.isAssignableFrom(it) }
            ?: error(
                "$name: no Spec subclass declared in script. " +
                    "Expected `class XSpec : RedstoneTestSpec(...)`. " +
                    "Declared classes: ${scriptInstance.javaClass.declaredClasses.map { it.simpleName }}"
            )
        return specClass.kotlin as KClass<out Spec>
    }
}
