package com.breadmoirai.redstonespecs.test.project

import com.breadmoirai.redstonespecs.project.ProjectDimLifecycle
import com.breadmoirai.redstonespecs.project.ProjectDimRegistry
import com.breadmoirai.redstonespecs.project.ProjectFolderTree
import com.breadmoirai.redstonespecs.project.ProjectRoot
import com.breadmoirai.redstonespecs.project.ProjectServerContext
import com.breadmoirai.redstonespecs.project.ProjectSession
import com.breadmoirai.redstonespecs.project.ProjectWorld
import com.breadmoirai.redstonespecs.network.project.LoadProjectFolderC2S
import com.breadmoirai.redstonespecs.network.project.ProjectErrorS2C
import com.breadmoirai.redstonespecs.network.project.ProjectFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.project.ProjectNetworkRegistry
import com.breadmoirai.redstonespecs.network.project.ProjectSaveReportS2C
import com.breadmoirai.redstonespecs.network.project.ProjectTreeSnapshotS2C
import com.breadmoirai.redstonespecs.network.project.NewProjectSpecC2S
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ProjectNetworkRegistrySpec : RedstoneTestSpec({

    test("handleLoadFolder rejects path traversal with ProjectErrorS2C") {
        withTempRoot("project-net-traversal") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                ProjectDimLifecycle.placeAll(this, ProjectRoot(tmp))
                drainPayloads(player) // discard any pre-existing payloads

                ProjectNetworkRegistry.handleLoadFolder(this, player, LoadProjectFolderC2S("../escape"))

                val payloads = drainPayloads(player)
                val err = payloads.filterIsInstance<ProjectErrorS2C>().single()
                err.reason.shouldContain("escapes root")

                ProjectSession.clear(player.uuid)
                ProjectWorld.clear(this)
                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleLoadFolder happy path sends ProjectFolderLoadedS2C and sets session") {
        withTempRoot("project-net-load-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                ProjectDimLifecycle.placeAll(this, ProjectRoot(tmp))
                drainPayloads(player)

                ProjectNetworkRegistry.handleLoadFolder(this, player, LoadProjectFolderC2S("set"))

                val loaded = drainPayloads(player).filterIsInstance<ProjectFolderLoadedS2C>().single()
                loaded.subpath shouldBe "set"
                loaded.loadedSpecIds.toSet() shouldBe setOf("a", "b")
                ProjectSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ProjectSession.clear(player.uuid)
                ProjectWorld.clear(this)
                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleSaveNow returns formatted ProjectSaveReportS2C") {
        withTempRoot("project-net-save") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                val root = ProjectRoot(tmp)
                ProjectDimLifecycle.placeAll(this, root)
                val world = ProjectWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ProjectDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ProjectDimLifecycle.placeFolder(this, root, "set")
                level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                ProjectNetworkRegistry.handleSaveNow(this, player)

                val report = drainPayloads(player).filterIsInstance<ProjectSaveReportS2C>().single()
                report.perSpec.any { it.startsWith("a|saved=true") } shouldBe true

                ProjectWorld.clear(this)
                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec without active session returns 'no folder selected'") {
        withTempRoot("project-net-new-no-session") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                ProjectSession.clear(player.uuid)
                drainPayloads(player)

                ProjectNetworkRegistry.handleNewSpec(this, player, NewProjectSpecC2S("fresh"))

                val err = drainPayloads(player).filterIsInstance<ProjectErrorS2C>().single()
                err.reason shouldBe "no folder selected"

                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec with active session creates file and sends ProjectFolderLoadedS2C") {
        withTempRoot("project-net-new-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                ProjectDimLifecycle.placeAll(this, ProjectRoot(tmp))
                ProjectSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ProjectNetworkRegistry.handleNewSpec(this, player, NewProjectSpecC2S("fresh"))

                folder.resolve("fresh.spec.kts").exists() shouldBe true
                val loaded = drainPayloads(player).filterIsInstance<ProjectFolderLoadedS2C>().single()
                loaded.loadedSpecIds shouldContain "fresh"

                ProjectSession.clear(player.uuid)
                ProjectWorld.clear(this)
                ProjectServerContext.clear(this)
            }
        }
    }

    test("handleUnload clears session and sends empty save report") {
        withTempRoot("project-net-unload") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ProjectNetworkRegistry.handleUnload(this, player)

                ProjectSession.get(player.uuid).let { it == null } shouldBe true
                val report = drainPayloads(player).filterIsInstance<ProjectSaveReportS2C>().single()
                report.perSpec shouldBe emptyList()
            }
        }
    }

    test("ungraceful disconnect clears the player's managed session") {
        onServer {
            val player = makeMockServerPlayer(this)
            ProjectSession.setActive(player.uuid, "set")
            ProjectSession.get(player.uuid)?.activeSubpath shouldBe "set"

            // No Unload click: the player just drops. Fire the real server-side disconnect
            // event and assert the mod's DISCONNECT listener released the session slot so it
            // doesn't linger into a reconnect.
            ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player.connection, this)

            ProjectSession.get(player.uuid).shouldBeNull()
        }
    }

    test("handleListTree sends snapshot matching ProjectFolderTree.scan") {
        withTempRoot("project-net-list") { tmp ->
            val subA = tmp.resolve("set-a").also { it.createDirectories() }
            writeStub(subA, "x")
            val subB = tmp.resolve("set-b").also { it.createDirectories() }
            writeStub(subB, "y")
            writeStub(subB, "z")
            onServer {
                val player = makeMockServerPlayer(this)
                ProjectServerContext.set(this, ProjectServerContext(ProjectRoot(tmp)))
                drainPayloads(player)

                ProjectNetworkRegistry.handleListTree(this, player)

                val snap = drainPayloads(player).filterIsInstance<ProjectTreeSnapshotS2C>().single()
                val expected = ProjectFolderTree.scan(ProjectRoot(tmp))
                snap.leaves.map { it.subpath }.toSet() shouldBe expected.leaves.map { it.subpath }.toSet()
                snap.leaves.single { it.subpath == "set-b" }.specCount shouldBe 2

                ProjectServerContext.clear(this)
            }
        }
    }
})
