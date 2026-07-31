package com.breadmoirai.garnet.test.project

import com.breadmoirai.garnet.config.SharedSettings
import com.breadmoirai.garnet.project.ProjectCommand
import com.breadmoirai.garnet.project.ProjectRoot
import com.breadmoirai.garnet.project.ProjectServerContext
import com.breadmoirai.garnet.project.ProjectSession
import com.breadmoirai.garnet.project.walk
import com.breadmoirai.garnet.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.garnet.test.drainPayloads
import com.breadmoirai.garnet.test.makeMockServerPlayer
import com.breadmoirai.garnet.test.withTempRoot
import com.breadmoirai.garnet.harness.GarnetTestSpec
import com.breadmoirai.garnet.mc.onServer
import com.mojang.brigadier.CommandDispatcher
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import net.minecraft.commands.CommandSourceStack
import kotlin.io.path.createDirectories

class ProjectCommandSpec : GarnetTestSpec({

    test("/garnet managed without root configured sends an error message") {
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
                val rc = dispatcher.execute("garnet project", source)
                rc shouldBe 0
                // No tree snapshot should have been sent.
                val payloads = drainPayloads(player)
                payloads.filterIsInstance<ProjectTreeSnapshotS2C>().isEmpty() shouldBe true
            }
        } finally {
            SharedSettings.projectRootPath = prior
        }
    }

    test("/garnet managed with context sends a ProjectTreeSnapshotS2C") {
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
                val rc = dispatcher.execute("garnet project", source)
                (rc > 0) shouldBe true

                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                snap.root.walk().map { it.first }.toList() shouldContain "set/a.spec.kts"

                ProjectServerContext.clear(this)
            }
        }
    }

    test("/garnet managed falls back to SharedSettings.projectRootPath when no server context") {
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
                    val rc = dispatcher.execute("garnet project", source)
                    (rc > 0) shouldBe true

                    val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                    snap.root.walk().map { it.first }.toList() shouldContain "cfg/a.spec.kts"
                }
            } finally {
                SharedSettings.projectRootPath = prior
            }
        }
    }

    test("/garnet managed snapshot carries intermediates and the player's active subpath") {
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

                val rc = dispatcher.execute("garnet project", player.createCommandSourceStack())
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
