package com.breadmoirai.redstonespecs.data.serial

import com.breadmoirai.redstonespecs.data.RedstoneSpec
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.script.experimental.api.EvaluationResult
import kotlin.script.experimental.api.ResultValue
import kotlin.script.experimental.api.ResultWithDiagnostics
import kotlin.script.experimental.api.ScriptEvaluationConfiguration
import kotlin.script.experimental.host.toScriptSource
import kotlin.script.experimental.jvmhost.BasicJvmScriptingHost

object KtsSpecLoader {
    private val host = BasicJvmScriptingHost()
    private val evalConfig = ScriptEvaluationConfiguration { /* defaults */ }

    fun loadFile(path: Path): RedstoneSpec =
        loadString(path.readText(), name = path.fileName.toString())

    fun loadString(source: String, name: String = "spec.kts"): RedstoneSpec {
        val scriptSource = source.toScriptSource(name)
        val result = host.eval(scriptSource, SpecScriptCompilationConfig, evalConfig)
        return when (result) {
            is ResultWithDiagnostics.Success -> extractSpec(result.value, name)
            is ResultWithDiagnostics.Failure -> {
                val msg = result.reports.joinToString("\n") { "  ${it.severity}: ${it.message}" }
                error("Failed to load $name:\n$msg")
            }
        }
    }

    private fun extractSpec(eval: EvaluationResult, name: String): RedstoneSpec {
        val rv = eval.returnValue
        return when (rv) {
            is ResultValue.Value -> rv.value as? RedstoneSpec
                ?: error("$name: last expression must be RedstoneSpec, got ${rv.type}")
            is ResultValue.Unit -> error("$name: script must end with redstoneSpec(...) expression")
            is ResultValue.Error -> throw rv.error
            ResultValue.NotEvaluated -> error("$name: script not evaluated")
        }
    }
}
