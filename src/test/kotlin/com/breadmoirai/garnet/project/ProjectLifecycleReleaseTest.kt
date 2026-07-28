package com.breadmoirai.garnet.project

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito
import java.nio.file.Path

/**
 * Unit coverage for [ProjectDimLifecycle.releaseServerState] (UC-MAN-08.d). The three
 * server-scoped holders are keyed by [MinecraftServer], so a mock server is a sufficient key;
 * no live world is needed. The world-materializing paths (`placeAll`/`saveFolder`) are covered
 * by the gametest `ProjectDimSpec` instead.
 */
class ProjectLifecycleReleaseTest : FunSpec({

    val root = ProjectRoot(Path.of("/managed-release-root").toAbsolutePath())

    test("UC-MAN-08.d: releaseServerState disposes registry, world, and context") {
        val server = Mockito.mock(MinecraftServer::class.java)
        try {
            ProjectServerContext.set(server, ProjectServerContext(root))
            ProjectWorld.set(server, ProjectWorld(root))
            ProjectDimRegistry.of(server).getOrAssignRegion("set")

            // preconditions: all three holders populated for this server
            ProjectServerContext.get(server).shouldNotBeNull()
            ProjectWorld.get(server).shouldNotBeNull()
            ProjectDimRegistry.of(server).regionOriginOf("set").shouldNotBeNull()

            ProjectDimLifecycle.releaseServerState(server)

            ProjectServerContext.get(server).shouldBeNull()
            ProjectWorld.get(server).shouldBeNull()
            // after dispose, `of` returns a fresh registry with no prior assignment
            ProjectDimRegistry.of(server).regionOriginOf("set").shouldBeNull()
        } finally {
            ProjectServerContext.clear(server)
            ProjectWorld.clear(server)
            ProjectDimRegistry.dispose(server)
        }
    }
})
