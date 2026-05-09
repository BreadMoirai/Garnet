package com.breadmoirai.redstonespecs.persistence

import com.breadmoirai.redstonespecs.dsl.RedstoneSpec
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
        // Pin the script's runtime classloader to dsl.RedstoneSpec's loader so that
        // type identity is shared between host and script.
        jvm {
            baseClassLoader(RedstoneSpec::class.java.classLoader)
        }
    }

    /**
     * Evaluates a `.spec.kts` source and returns the declared [Spec] subclass.
     *
     * The old emitted form declares `class XSpec : RedstoneTestSpec(...)` as a nested class
     * of the script object. This function locates that class via reflection on the script
     * instance's declared nested classes.
     */
    fun loadSpec(source: String, name: String = "spec.kts"): KClass<out Spec> {
        val eval = evalOrThrow(source, name)
        return findFirstSpecClass(eval, name)
    }

    /**
     * Evaluates a `.spec.kts` source and returns the [RedstoneSpec] value.
     *
     * New-style scripts (from [com.breadmoirai.redstonespecs.runner.RecordingDslEmitter])
     * end with a `redstoneSpec(...) { ... }` expression whose return value is extracted
     * directly from [ResultValue.Value].
     */
    fun loadRedstoneSpec(source: String, name: String = "spec.kts"): RedstoneSpec {
        val eval = evalOrThrow(source, name)
        val rv = eval.returnValue
        when (rv) {
            is ResultValue.Error -> throw rv.error
            is ResultValue.NotEvaluated -> error("$name: script was not evaluated")
            else -> { /* continue */ }
        }
        val value = (rv as? ResultValue.Value)?.value
            ?: error("$name: script did not produce a RedstoneSpec value (got: $rv). " +
                "Ensure the script ends with a `redstoneSpec(...) { ... }` expression.")
        return value as? RedstoneSpec
            ?: error("$name: script result is not a dsl.RedstoneSpec (got: ${value::class.qualifiedName}). " +
                "Ensure the script ends with a `redstoneSpec(...) { ... }` expression from com.breadmoirai.redstonespecs.dsl.")
    }

    /** Loads a `.spec.kts` file and returns the declared [Spec] class. */
    fun loadFile(path: Path): KClass<out Spec> =
        loadSpec(path.readText(), name = path.fileName.toString())

    /** Loads a `.spec.kts` file and returns its [RedstoneSpec] value. */
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
        when (rv) {
            is ResultValue.Error -> throw rv.error
            is ResultValue.NotEvaluated -> error("$name: script was not evaluated")
            else -> { /* continue */ }
        }
        val scriptInstance = rv.scriptInstance
            ?: error("$name: script produced no instance (returnValue=$rv)")

        val specClasses = scriptInstance.javaClass.declaredClasses
            .filter { Spec::class.java.isAssignableFrom(it) }
        return when (specClasses.size) {
            0 -> error("$name: no Spec class declared in script (expected `class XSpec : RedstoneTestSpec(...)`)")
            1 -> specClasses.single().kotlin as KClass<out Spec>
            else -> error("$name: expected exactly 1 Spec subclass, found ${specClasses.size}: " +
                "${specClasses.map { it.simpleName }}")
        }
    }
}
