package com.breadmoirai.redstonespecs.runner

import com.breadmoirai.redstonespecs.testing.launcher.LauncherResult
import com.breadmoirai.redstonespecs.testing.launcher.TestFailureRecord
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.UUID

class EngineDrivenRunToTestResultTest : FunSpec({
    test("toTestResult passes-only when failed == 0 and no recording") {
        val lr = LauncherResult(passed = 1, failed = 0, errors = emptyList())
        val result = EngineDrivenRun.toTestResult("spec-A", lr)
        result.specId shouldBe "spec-A"
        result.checks shouldHaveSize 1
        result.checks[0].pass shouldBe true
        result.recording.shouldBeNull()
    }

    test("toTestResult maps failures to failing TickChecks") {
        val lr = LauncherResult(
            passed = 0, failed = 1,
            errors = listOf(TestFailureRecord("test-name", "boom", null)),
        )
        val result = EngineDrivenRun.toTestResult("spec-B", lr)
        result.checks shouldHaveSize 1
        result.checks[0].pass shouldBe false
        result.checks[0].label shouldBe "test-name"
        result.checks[0].actual shouldBe "boom"
    }

    test("toTestResult attaches the only recording") {
        val rec = makeMinimalRecordingNoBootstrap()
        val lr = LauncherResult(
            passed = 1, failed = 0, errors = emptyList(),
            recordings = mapOf("test-name" to rec),
        )
        val result = EngineDrivenRun.toTestResult("spec-C", lr)
        result.recording shouldBe rec
    }

    test("toTestResult takes first and warns when multiple recordings present") {
        val rec1 = makeMinimalRecordingNoBootstrap()
        val rec2 = makeMinimalRecordingNoBootstrap()
        // LinkedHashMap preserves insertion order so rec1 is "first"
        val recs = linkedMapOf("test-1" to rec1, "test-2" to rec2)
        val lr = LauncherResult(passed = 2, failed = 0, errors = emptyList(), recordings = recs)
        val result = EngineDrivenRun.toTestResult("spec-D", lr)
        // Should take the first recording and not throw
        result.recording shouldBe rec1
        result.checks shouldHaveSize 1
        result.checks[0].pass shouldBe true
    }
})

/**
 * Constructs a [StateRecording] with no [BlockState] entries, so no MC bootstrap is needed.
 * The initialSnapshot is empty; the recording carries identity metadata only.
 */
private fun makeMinimalRecordingNoBootstrap(): StateRecording = StateRecording(
    specId = UUID.randomUUID(),
    timestamp = 12345L,
    initialSnapshot = emptyMap(),
    changes = emptyList(),
)
