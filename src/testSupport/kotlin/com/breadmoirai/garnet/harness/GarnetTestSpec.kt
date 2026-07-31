@file:OptIn(io.kotest.common.ExperimentalKotest::class)

package com.breadmoirai.garnet.harness

import com.breadmoirai.garnet.mc.McDispatchers
import com.breadmoirai.garnet.harness.RecordingHolder
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
abstract class GarnetTestSpec(body: GarnetTestSpec.() -> Unit = {}) : FunSpec() {

    /**
     * World-relative origin of this spec's run. Read from [GarnetTestSpecContext],
     * which EngineDrivenRun binds before instantiating the Spec class.
     */
    val originPos: BlockPos get() = GarnetTestSpecContext.current().originPos

    /**
     * Server level for this spec's run. Read from [GarnetTestSpecContext],
     * which EngineDrivenRun binds before instantiating the Spec class.
     */
    val level: ServerLevel get() = GarnetTestSpecContext.current().level

    init {
        coroutineDispatcherFactory = object : CoroutineDispatcherFactory {
            override suspend fun <T> withDispatcher(testCase: TestCase, block: suspend () -> T): T =
                withContext(McDispatchers.Server + RecordingHolder()) { block() }

            override fun close() = Unit
        }
        body()
    }
}
