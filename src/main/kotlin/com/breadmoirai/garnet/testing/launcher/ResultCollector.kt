package com.breadmoirai.garnet.testing.launcher

import io.kotest.core.listeners.TestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType

data class TestFailureRecord(val name: String, val message: String, val cause: Throwable?)

data class LauncherResult(
    val passed: Int,
    val failed: Int,
    val errors: List<TestFailureRecord>,
    val recordings: Map<String, com.breadmoirai.garnet.runner.StateRecording> = emptyMap(),
) {
    val total: Int get() = passed + failed
    fun summary(): String = if (failed == 0) {
        "All $total tests passed"
    } else {
        val sample = errors.take(5).joinToString("\n  ") { "${it.name}: ${it.message}" }
        "$failed/$total failed:\n  $sample" + if (errors.size > 5) "\n  ... (${errors.size - 5} more)" else ""
    }
}

internal class ResultCollector : TestListener {
    private var passed = 0
    private var failed = 0
    private val errors = mutableListOf<TestFailureRecord>()

    @Volatile var result: LauncherResult = LauncherResult(0, 0, emptyList())
        private set

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        // Skip container tests (DescribeSpec contexts, etc.) — only count leaves.
        if (testCase.type != TestType.Test) return
        when (result) {
            is TestResult.Success -> passed++
            is TestResult.Failure -> {
                failed++
                errors.add(TestFailureRecord(testCase.name.testName, result.errorOrNull?.message ?: "(no message)", result.errorOrNull))
            }
            is TestResult.Error -> {
                failed++
                errors.add(TestFailureRecord(testCase.name.testName, result.errorOrNull?.message ?: "(error)", result.errorOrNull))
            }
            else -> {} // Ignored, Pending, etc.
        }
        this.result = LauncherResult(passed, failed, errors.toList())
    }
}
