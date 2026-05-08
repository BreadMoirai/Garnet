package com.breadmoirai.redstonespecs.testing.launcher

import com.breadmoirai.redstonespecs.runner.StateRecording
import com.breadmoirai.redstonespecs.testing.runner.RecordingHolder
import io.kotest.core.listeners.TestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the [StateRecording] published by [runRedstoneSpec] (via [RecordingHolder])
 * for each leaf test, keyed by test name.
 */
class DiagnosticRecorderListener : TestListener {
    private val byTestName = ConcurrentHashMap<String, StateRecording>()

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        if (testCase.type != TestType.Test) return
        val rec = currentCoroutineContext()[RecordingHolder]?.recording ?: return
        byTestName[testCase.name.testName] = rec
    }

    fun snapshot(): Map<String, StateRecording> = byTestName.toMap()
}
