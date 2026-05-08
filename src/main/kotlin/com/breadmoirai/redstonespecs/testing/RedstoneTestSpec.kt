@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.redstonespecs.testing

import com.breadmoirai.redstonespecs.testing.core.McDispatchers
import io.kotest.core.concurrency.CoroutineDispatcherFactory
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestCase
import kotlinx.coroutines.withContext
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel

/**
 * Base class for specs whose test bodies and lifecycle hooks run on the server thread.
 * Used by both shipped `.spec.kts` files (loaded at runtime) and dev tests in `src/gametest/`,
 * `src/clientTest/`, and `src/test/`.
 */
abstract class RedstoneTestSpec(body: RedstoneTestSpec.() -> Unit = {}) : FunSpec() {

    /**
     * World-relative origin of this spec's run. Bound by EngineDrivenRun (Plan D).
     * Throws if accessed before Plan D wires it to a thread-local context.
     */
    open val originPos: BlockPos
        get() = error("originPos is not bound; this RedstoneTestSpec must be launched via EngineDrivenRun (Plan D)")

    /**
     * Server level for this spec's run. Bound by EngineDrivenRun (Plan D).
     * Throws if accessed before Plan D wires it to a thread-local context.
     */
    open val level: ServerLevel
        get() = error("level is not bound; this RedstoneTestSpec must be launched via EngineDrivenRun (Plan D)")

    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(McDispatchers.Server) { block() }

            override fun close() = Unit
        }
        body()
    }
}
