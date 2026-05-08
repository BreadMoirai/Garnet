package com.breadmoirai.redstonespecs.testing.runner

import com.breadmoirai.redstonespecs.runner.StateRecording
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-test coroutine-context slot for the diagnostic [StateRecording] published by
 * [runRedstoneSpec]. Installed by [RedstoneTestSpec]'s coroutineDispatcherFactory,
 * which wraps both the test body and per-test lifecycle hooks (afterTest), so the
 * holder is visible to [com.breadmoirai.redstonespecs.testing.launcher.DiagnosticRecorderListener.afterTest]
 * regardless of thread scheduling.
 */
class RecordingHolder : AbstractCoroutineContextElement(Key) {
    var recording: StateRecording? = null

    companion object Key : CoroutineContext.Key<RecordingHolder>
}
