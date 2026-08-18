package com.breadmoirai.garnet.editor.workspace.world

import com.breadmoirai.garnet.editor.explorer.data.EditorRoot
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import net.minecraft.server.MinecraftServer
import org.mockito.Mockito
import java.nio.file.Path

/**
 * Unit coverage for [EditorDimLifecycle.releaseServerState] (UC-MAN-08.d). The three
 * server-scoped holders are keyed by [MinecraftServer], so a mock server is a sufficient key;
 * no live world is needed. The world-materializing paths (`placeAll`/`saveFolder`) are covered
 * by the gametest `EditorDimSpec` instead.
 */
class EditorLifecycleReleaseTest : FunSpec({

    val root = EditorRoot(Path.of("/managed-release-root").toAbsolutePath())

    test("UC-MAN-08.d: releaseServerState disposes registry, world, and context") {
        val server = Mockito.mock(MinecraftServer::class.java)
        try {
            EditorServerContext.set(server, EditorServerContext(root))
            EditorWorld.set(server, EditorWorld(root))
            EditorDimRegistry.of(server).getOrAssignRegion("set")

            // preconditions: all three holders populated for this server
            EditorServerContext.get(server).shouldNotBeNull()
            EditorWorld.get(server).shouldNotBeNull()
            EditorDimRegistry.of(server).regionOriginOf("set").shouldNotBeNull()

            EditorDimLifecycle.releaseServerState(server)

            EditorServerContext.get(server).shouldBeNull()
            EditorWorld.get(server).shouldBeNull()
            // after dispose, `of` returns a fresh registry with no prior assignment
            EditorDimRegistry.of(server).regionOriginOf("set").shouldBeNull()
        } finally {
            EditorServerContext.clear(server)
            EditorWorld.clear(server)
            EditorDimRegistry.dispose(server)
        }
    }
})
