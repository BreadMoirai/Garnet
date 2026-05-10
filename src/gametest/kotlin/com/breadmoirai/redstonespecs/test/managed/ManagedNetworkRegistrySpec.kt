package com.breadmoirai.redstonespecs.test.managed

import com.breadmoirai.redstonespecs.managed.ManagedDimLifecycle
import com.breadmoirai.redstonespecs.managed.ManagedDimRegistry
import com.breadmoirai.redstonespecs.managed.ManagedFolderTree
import com.breadmoirai.redstonespecs.managed.ManagedRoot
import com.breadmoirai.redstonespecs.managed.ManagedServerContext
import com.breadmoirai.redstonespecs.managed.ManagedSession
import com.breadmoirai.redstonespecs.managed.ManagedWorld
import com.breadmoirai.redstonespecs.network.managed.LoadManagedFolderC2S
import com.breadmoirai.redstonespecs.network.managed.ManagedErrorS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedFolderLoadedS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedNetworkRegistry
import com.breadmoirai.redstonespecs.network.managed.ManagedSaveReportS2C
import com.breadmoirai.redstonespecs.network.managed.ManagedTreeSnapshotS2C
import com.breadmoirai.redstonespecs.network.managed.NewManagedSpecC2S
import com.breadmoirai.redstonespecs.test.drainPayloads
import com.breadmoirai.redstonespecs.test.makeMockServerPlayer
import com.breadmoirai.redstonespecs.test.withTempRoot
import com.breadmoirai.redstonespecs.testing.RedstoneTestSpec
import com.breadmoirai.redstonespecs.testing.server.onServer
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

class ManagedNetworkRegistrySpec : RedstoneTestSpec({

    test("handleLoadFolder rejects path traversal with ManagedErrorS2C") {
        withTempRoot("managed-net-traversal") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                drainPayloads(player) // discard any pre-existing payloads

                ManagedNetworkRegistry.handleLoadFolder(this, player, LoadManagedFolderC2S("../escape"))

                val payloads = drainPayloads(player)
                val err = payloads.filterIsInstance<ManagedErrorS2C>().single()
                err.reason.shouldContain("escapes root")

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleLoadFolder happy path sends ManagedFolderLoadedS2C and sets session") {
        withTempRoot("managed-net-load-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            writeStub(folder, "b")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                drainPayloads(player)

                ManagedNetworkRegistry.handleLoadFolder(this, player, LoadManagedFolderC2S("set"))

                val loaded = drainPayloads(player).filterIsInstance<ManagedFolderLoadedS2C>().single()
                loaded.subpath shouldBe "set"
                loaded.loadedSpecIds.toSet() shouldBe setOf("a", "b")
                ManagedSession.get(player.uuid)?.activeSubpath shouldBe "set"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleSaveNow returns formatted ManagedSaveReportS2C") {
        withTempRoot("managed-net-save") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            writeStub(folder, "a")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                val root = ManagedRoot(tmp)
                ManagedDimLifecycle.placeAll(this, root)
                val world = ManagedWorld.get(this).shouldNotBeNull()
                val abs = world.absoluteCellOrigin(this, "set", "a").shouldNotBeNull()
                val level = ManagedDimRegistry.of(this).managedLevel()
                val bounds = world.perFolder["set"]!!["a"]!!.spec.bounds
                clearCellVolume(level, abs, bounds)
                ManagedDimLifecycle.placeFolder(this, root, "set")
                level.setBlock(abs.offset(1, 1, 1), net.minecraft.world.level.block.Blocks.GOLD_BLOCK.defaultBlockState(), 2)
                drainPayloads(player)

                ManagedNetworkRegistry.handleSaveNow(this, player)

                val report = drainPayloads(player).filterIsInstance<ManagedSaveReportS2C>().single()
                report.perSpec.any { it.startsWith("a|saved=true") } shouldBe true

                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec without active session returns 'no folder selected'") {
        withTempRoot("managed-net-new-no-session") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedSession.clear(player.uuid)
                drainPayloads(player)

                ManagedNetworkRegistry.handleNewSpec(this, player, NewManagedSpecC2S("fresh"))

                val err = drainPayloads(player).filterIsInstance<ManagedErrorS2C>().single()
                err.reason shouldBe "no folder selected"

                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleNewSpec with active session creates file and sends ManagedFolderLoadedS2C") {
        withTempRoot("managed-net-new-ok") { tmp ->
            val folder = tmp.resolve("set").also { it.createDirectories() }
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                ManagedDimLifecycle.placeAll(this, ManagedRoot(tmp))
                ManagedSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ManagedNetworkRegistry.handleNewSpec(this, player, NewManagedSpecC2S("fresh"))

                folder.resolve("fresh.spec.kts").exists() shouldBe true
                val loaded = drainPayloads(player).filterIsInstance<ManagedFolderLoadedS2C>().single()
                loaded.loadedSpecIds shouldContain "fresh"

                ManagedSession.clear(player.uuid)
                ManagedWorld.clear(this)
                ManagedServerContext.clear(this)
            }
        }
    }

    test("handleUnload clears session and sends empty save report") {
        withTempRoot("managed-net-unload") { tmp ->
            tmp.resolve("set").createDirectories()
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedSession.setActive(player.uuid, "set")
                drainPayloads(player)

                ManagedNetworkRegistry.handleUnload(this, player)

                ManagedSession.get(player.uuid).let { it == null } shouldBe true
                val report = drainPayloads(player).filterIsInstance<ManagedSaveReportS2C>().single()
                report.perSpec shouldBe emptyList()
            }
        }
    }

    test("handleListTree sends snapshot matching ManagedFolderTree.scan") {
        withTempRoot("managed-net-list") { tmp ->
            val subA = tmp.resolve("set-a").also { it.createDirectories() }
            writeStub(subA, "x")
            val subB = tmp.resolve("set-b").also { it.createDirectories() }
            writeStub(subB, "y")
            writeStub(subB, "z")
            onServer {
                val player = makeMockServerPlayer(this)
                ManagedServerContext.set(this, ManagedServerContext(ManagedRoot(tmp)))
                drainPayloads(player)

                ManagedNetworkRegistry.handleListTree(this, player)

                val snap = drainPayloads(player).filterIsInstance<ManagedTreeSnapshotS2C>().single()
                val expected = ManagedFolderTree.scan(ManagedRoot(tmp))
                snap.leaves.map { it.subpath }.toSet() shouldBe expected.leaves.map { it.subpath }.toSet()
                snap.leaves.single { it.subpath == "set-b" }.specCount shouldBe 2

                ManagedServerContext.clear(this)
            }
        }
    }
})
