package com.breadmoirai.redstonespecs.testing.launcher

import com.breadmoirai.redstonespecs.runner.StateRecording
import io.kotest.core.listeners.TestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult
import io.kotest.core.test.TestType
import java.util.concurrent.ConcurrentHashMap

/**
 * Collects the [StateRecording] published by [runRedstoneSpec] (via [recordingThreadLocal])
 * for each leaf test, keyed by test name.
 */
class DiagnosticRecorderListener : TestListener {
    private val byTestName = ConcurrentHashMap<String, StateRecording>()

    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        if (testCase.type != TestType.Test) return
        val rec = recordingThreadLocal.get() ?: return
        byTestName[testCase.name.testName] = rec
        recordingThreadLocal.remove()
    }

    fun snapshot(): Map<String, StateRecording> = byTestName.toMap()

    companion object {
        /** Set by [com.breadmoirai.redstonespecs.testing.runner.runRedstoneSpec] after capture. */
        val recordingThreadLocal: ThreadLocal<StateRecording?> = ThreadLocal()
    }
}
