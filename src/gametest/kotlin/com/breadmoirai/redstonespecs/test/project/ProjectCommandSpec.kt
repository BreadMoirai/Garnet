package com.breadmoirai.redstonespecs.test.project

import com.breadmoirai.redstonespecs.config.SharedSettings
import com.breadmoirai.redstonespecs.project.ProjectCommand
import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectServerContext
import com.breadmoirai.redstonespecs.project.ProjectSession
import com.breadmoirai.redstonespecs.project.walk
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import net.minecraft.commands.CommandSourceStack
import kotlin.io.path.createDirectories

class ProjectCommandSpec : RedstoneTestSpec({

    test("/redstonespecs managed without root configured sends an error message") {
        // Save and restore the global SharedSettings.projectRootPath since the gametest server is shared.
        val prior = SharedSettings.projectRootPath
        SharedSettings.projectRootPath = ""
        try {
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.clear(this)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ProjectCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs project", source)
                rc shouldBe 0
                // No tree snapshot should have been sent.
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ProjectTreeSnapshotS2C>().isEmpty() shouldBe true
            }
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("/redstonespecs managed with context sends a ProjectTreeSnapshotS2C") {
        withTempRoot("project-cmd-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ProjectCommand.register(dispatcher)

                val source = player.createCommandSourceStack()
                val rc = dispatcher.execute("redstonespecs project", source)
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"

                ProjectServerContext.clear(this)
            }
        }
    }

    test("/redstonespecs managed falls back to SharedSettings.projectRootPath when no server context") {
        withTempRoot("project-cmd-fallback") { tmp ->
            val folder = tmp.resolve("cfg").also { it.createDirectories() }
            writeStub(folder, "a")
            val prior = SharedSettings.projectRootPath
            SharedSettings.projectRootPath = tmp.toAbsolutePath().toString()
            try {
                onServer {
                    val player = makeMockServerPlayer(this)
                    ProjectServerContext.clear(this)
                    drainPayloads(player)
                    val dispatcher = CommandDispatcher<CommandSourceStack>()
                    ProjectCommand.register(dispatcher)

                    val source = player.createCommandSourceStack()
                    val rc = dispatcher.execute("redstonespecs project", source)
                    (rc > 0) shouldBe true

                    val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                    snap.root.walk().map { it.first }.toList() shouldContain "cfg/a.spec.kts"
                }
            } finally {
                SharedSettings.projectRootPath = prior
            }
        }
    }

    test("/redstonespecs managed snapshot carries intermediates and the player's active subpath") {
        withTempRoot("project-cmd-session") { tmp ->
            // Tree: parent/ (intermediate) → parent/leaf/a.spec.kts
            val parent = tmp.resolve("parent").also { it.createDirectories() }
            val leaf = parent.resolve("leaf").also { it.createDirectories() }
            writeStub(leaf, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                ProjectSession.setActive(player.uuid, "parent/leaf")
                drainPayloads(player)
                val dispatcher = CommandDispatcher<CommandSourceStack>()
                ProjectCommand.register(dispatcher)

                val rc = dispatcher.execute("redstonespecs project", player.createCommandSourceStack())
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                val paths = snap.root.walk().map { it.first }.toList()
                paths shouldContain "parent"
                paths shouldContain "parent/leaf/a.spec.kts"
                snap.currentSubpath shouldBe "parent/leaf"

                ProjectSession.clear(player.uuid)
                ProjectServerContext.clear(this)
            }
        }
    }
})
