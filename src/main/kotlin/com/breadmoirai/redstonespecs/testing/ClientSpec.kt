@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.redstonespecs.testing

import com.breadmoirai.redstonespecs.testing.runner.RecordingHolder
import io.kotest.core.concurrency.CoroutineDispatcherFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Base class for Kotest specs in the `clientTest` source set.
 *
 * Test bodies run on `Dispatchers.Default` (a kotlinx-coroutines pool), not on
 * the server thread. This is the key difference from [RedstoneTestSpec], which
 * is server-thread-first (correct for gametest, wrong for client tests):
 *
 * - A worker-pool thread can sleep freely without blocking server ticks or
 *   client ticks, so polling helpers like `waitForClientScreen` work without
 *   deadlock.
 * - Server-side mutations hop explicitly via `onServer { … }`.
 * - Calls that already wrap themselves (e.g. `runRedstoneSpec`) switch threads
 *   internally — call them directly.
 *
 * A `RecordingHolder` is installed in the coroutine context so `runRedstoneSpec`
 * can attach the recording on completion (same as `RedstoneTestSpec`).
 *
 * `ClientGameTestContext.waitForScreen` / `waitFor` / `waitTick` still assert
 * the Fabric test thread and will throw if called from a `ClientSpec` test body.
 * Use the polling helpers in `ClientTestSupport.kt` instead.
 */
abstract class ClientSpec(body: ClientSpec.() -> Unit = {}) : FunSpec() {
    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(Dispatchers.Default + RecordingHolder()) { block() }

            override fun close() = Unit
        }
        body()
    }
}
