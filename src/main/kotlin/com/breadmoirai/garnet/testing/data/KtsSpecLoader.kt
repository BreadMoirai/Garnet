package com.breadmoirai.garnet.testing.data

import com.breadmoirai.garnet.core.spec.GarnetSpec
import java.nio.file.Path
import kotlin.io.path.readText
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
        // Pin the script's runtime classloader to spec.GarnetSpec's loader so that
        // type identity is shared between host and script.
        jvm {
            baseClassLoader(GarnetSpec::class.java.classLoader)
        }
    }

    /**
     * Evaluates a `.spec.kts` source and returns the [GarnetSpec] value.
     *
     * New-style scripts (from [com.breadmoirai.garnet.playback.recorder.RecordingDslEmitter])
     * end with a `garnetSpec(...) { ... }` expression whose return value is extracted
     * directly from [ResultValue.Value].
     */
    fun loadGarnetSpec(source: String, name: String = "spec.kts"): GarnetSpec {
        val eval = evalOrThrow(source, name)
        val rv = eval.returnValue
        when (rv) {
            is ResultValue.Error -> throw rv.error
            is ResultValue.NotEvaluated -> error("$name: script was not evaluated")
            else -> { /* continue */ }
        }
        val value = (rv as? ResultValue.Value)?.value
            ?: error("$name: script did not produce a GarnetSpec value (got: $rv). " +
                "Ensure the script ends with a `garnetSpec(...) { ... }` expression.")
        return value as? GarnetSpec
            ?: error("$name: script result is not a spec.GarnetSpec (got: ${value::class.qualifiedName}). " +
                "Ensure the script ends with a `garnetSpec(...) { ... }` expression from com.breadmoirai.garnet.core.spec.")
    }

    /** Loads a `.spec.kts` file and returns its [GarnetSpec] value. */
    fun loadFileAsGarnetSpec(path: Path): GarnetSpec =
        loadGarnetSpec(path.readText(), name = path.fileName.toString())

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
}
