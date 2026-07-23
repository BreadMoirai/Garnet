package com.breadmoirai.redstonespecs.managed

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito
import java.nio.file.Path

/**
 * Unit coverage for [ManagedDimLifecycle.releaseServerState] (UC-MAN-08.d). The three
 * server-scoped holders are keyed by [MinecraftServer], so a mock server is a sufficient key;
 * no live world is needed. The world-materializing paths (`placeAll`/`saveFolder`) are covered
 * by the gametest `ManagedDimSpec` instead.
 */
class ManagedLifecycleReleaseTest : FunSpec({

    val root = ManagedRoot(Path.of("/managed-release-root").toAbsolutePath())

    test("UC-MAN-08.d: releaseServerState disposes registry, world, and context") {
        val server = Mockito.mock(MinecraftServer::class.java)
        try {
            ManagedServerContext.set(server, ManagedServerContext(root))
            ManagedWorld.set(server, ManagedWorld(root))
            ManagedDimRegistry.of(server).getOrAssignRegion("set")

            // preconditions: all three holders populated for this server
            ManagedServerContext.get(server).shouldNotBeNull()
            ManagedWorld.get(server).shouldNotBeNull()
            ManagedDimRegistry.of(server).regionOriginOf("set").shouldNotBeNull()

            ManagedDimLifecycle.releaseServerState(server)

            ManagedServerContext.get(server).shouldBeNull()
            ManagedWorld.get(server).shouldBeNull()
            // after dispose, `of` returns a fresh registry with no prior assignment
            ManagedDimRegistry.of(server).regionOriginOf("set").shouldBeNull()
        } finally {
            ManagedServerContext.clear(server)
            ManagedWorld.clear(server)
            ManagedDimRegistry.dispose(server)
        }
    }
})
