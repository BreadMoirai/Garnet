package com.breadmoirai.garnet.test.editor

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.editor.command.EditorCommand
import com.breadmoirai.garnet.editor.data.EditorRoot
import com.breadmoirai.garnet.editor.world.EditorServerContext
import com.breadmoirai.garnet.editor.data.EditorSession
import com.breadmoirai.garnet.editor.data.walk
import com.breadmoirai.garnet.editor.network.EditorTreeSnapshotS2C
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.core.async.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import net.minecraft.commands.CommandSourceStack
import kotlin.io.path.createDirectories

class EditorCommandSpec : GarnetTestSpec({

    test("/garnet editor without root configured sends an error message") {
        // Save and restore the global SharedSettings.projectRootPath since the gametest server is shared.
        val prior = SharedSettings.projectRootPath
        SharedSettings.projectRootPath = ""
        try {
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.clear(this)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                EditorCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("garnet editor", source)
                rc shouldBe 0
                // No tree snapshot should have been sent.
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<EditorTreeSnapshotS2C>().isEmpty() shouldBe true
            }
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("/garnet editor with context sends a EditorTreeSnapshotS2C") {
        withTempRoot("project-cmd-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                EditorCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("garnet editor", source)
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>().single()
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"

                EditorServerContext.clear(this)
            }
        }
    }

    test("/garnet editor falls back to SharedSettings.projectRootPath when no server context") {
        withTempRoot("project-cmd-fallback") { tmp ->
            val folder = tmp.resolve("cfg").also { it.createDirectories() }
            writeStub(folder, "a")
            val prior = SharedSettings.projectRootPath
            SharedSettings.projectRootPath = tmp.toAbsolutePath().toString()
            try {
                onServer {
                    val player = makeMockServerPlayer(this)
                    EditorServerContext.clear(this)
                    drainPayloads(player)
                    val dispatcher = CommandDispatcher<CommandSourceStack>()
                    EditorCommand.register(dispatcher)

                    val source = player.createCommandSourceStack()
                    val rc = dispatcher.execute("garnet editor", source)
                    (rc > 0) shouldBe true

                    val snap = drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>().single()
                    snap.root.walk().map { it.first }.toList() shouldContain "cfg/a.spec.kts"
                }
            } finally {
                SharedSettings.projectRootPath = prior
            }
        }
    }

    test("/garnet editor snapshot carries intermediates and the player's active subpath") {
        withTempRoot("project-cmd-session") { tmp ->
            // Tree: parent/ (intermediate) → parent/leaf/a.spec.kts
            val parent = tmp.resolve("parent").also { it.createDirectories() }
            val leaf = parent.resolve("leaf").also { it.createDirectories() }
            writeStub(leaf, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                EditorServerContext.set(this, EditorServerContext(EditorRoot(tmp)))
                EditorSession.setActive(player.uuid, "parent/leaf")
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                EditorCommand.register(dispatcher)

                val rc = dispatcher.execute("garnet editor", player.createCommandSourceStack())
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<EditorTreeSnapshotS2C>().single()
                val paths = snap.root.walk().map { it.first }.toList()
                paths shouldContain "parent"
                paths shouldContain "parent/leaf/a.spec.kts"
                snap.currentSubpath shouldBe "parent/leaf"

                EditorSession.clear(player.uuid)
                EditorServerContext.clear(this)
            }
        }
    }
})
