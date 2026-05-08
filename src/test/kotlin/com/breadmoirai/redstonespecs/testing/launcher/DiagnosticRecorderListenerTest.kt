package com.breadmoirai.redstonespecs.testing.launcher

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty

class DiagnosticRecorderListenerTest : FunSpec({
    test("snapshot is empty before any tests run") {
        val listener = DiagnosticRecorderListener()
        listener.snapshot().shouldBeEmpty()
    }

    // Coverage gap: the afterTest contract (set ThreadLocal → call afterTest → assert
    // snapshot()) cannot be tested without constructing a Kotest TestCase, which requires
    // a live Spec instance and a descriptor hierarchy. Kotest 5.x exposes no public
    // factory for TestCase outside of the engine; mocking the type is equally involved.
    //
    // The behaviour IS exercised end-to-end by EngineDrivenRun when a redstone spec runs:
    // runRedstoneSpec sets recordingThreadLocal, the listener's afterTest drains it into
    // byTestName, and the resulting snapshot feeds LauncherResult.recordings.
    //
    // Adding a second test here would require either: (a) pulling in a full Kotest engine
    // fixture (test-within-test), or (b) reflectively calling afterTest with a manually
    // constructed TestCase. Neither provides enough value over the integration path to
    // justify the complexity.
})
