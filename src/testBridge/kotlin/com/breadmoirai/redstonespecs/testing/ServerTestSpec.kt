@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.redstonespecs.testing

import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.core.concurrency.CoroutineDispatcherFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import kotlinx.coroutines.withContext

/**
 * Base class for specs that run test bodies and hooks on [McDispatchers.Server] (the server thread).
 *
 * Subclasses extend this instead of [FunSpec] directly; no `withContext` / `onServer` wrapper
 * is needed in individual test bodies or lifecycle hooks — the dispatcher is installed at the
 * spec level via Kotest's [CoroutineDispatcherFactory] hook.
 *
 * Implementation note: Approach 1 (coroutineDispatcherFactory field on Spec) was used.
 * Kotest 5.9.1 exposes `setCoroutineDispatcherFactory` on the Spec base class, which wraps
 * every test's coroutine context (body + per-test before/afterTest hooks) with the supplied
 * factory's `withDispatcher` block.
 */
abstract class ServerTestSpec(body: ServerTestSpec.() -> Unit = {}) : FunSpec() {
    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(McDispatchers.Server) { block() }

            override fun close() = Unit
        }
        body()
    }
}
