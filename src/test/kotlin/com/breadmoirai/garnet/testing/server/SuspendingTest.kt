package com.breadmoirai.garnet.testing.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito.mock

class SuspendingTest : FunSpec({

    test("take(n).last() resolves after n emissions") {
        runTest {
            val flow = MutableSharedFlow<MinecraftServer>(
                replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val server = mock(MinecraftServer::class.java)

            val deferred = async { flow.take(3).last() }
            // Yield so the consumer attaches before we emit.
            yield()
            flow.tryEmit(server) shouldBe true
            yield()
            flow.tryEmit(server) shouldBe true
            yield()
            flow.tryEmit(server) shouldBe true

            deferred.await() shouldBe server
        }
    }

    test("first(predicate) returns the matching emission") {
        runTest {
            val flow = MutableSharedFlow<MinecraftServer>(
                replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
            )
            val a = mock(MinecraftServer::class.java)
            val b = mock(MinecraftServer::class.java)

            val deferred = async { flow.first { it === b } }
            yield()
            flow.tryEmit(a)
            yield()
            flow.tryEmit(b)
            yield()

            deferred.await() shouldBe b
        }
    }

    test("awaitTicks rejects n=0") {
        runTest {
            val ex = runCatching { awaitTicks(0) }.exceptionOrNull()
            (ex is IllegalArgumentException) shouldBe true
        }
    }
})
