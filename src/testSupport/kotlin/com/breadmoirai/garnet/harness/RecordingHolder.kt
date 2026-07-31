package com.breadmoirai.garnet.harness

import com.breadmoirai.garnet.runner.StateRecording
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-test coroutine-context slot for the diagnostic [StateRecording] published by
 * [runGarnetSpec]. Installed by [GarnetTestSpec]'s coroutineDispatcherFactory,
 * which wraps both the test body and per-test lifecycle hooks (afterTest), so the
 * holder is visible to [com.breadmoirai.garnet.harness.launcher.DiagnosticRecorderListener.afterTest]
 * regardless of thread scheduling.
 */
class RecordingHolder : AbstractCoroutineContextElement(Key) {
    var recording: StateRecording? = null

    companion object Key : CoroutineContext.Key<RecordingHolder>
}
